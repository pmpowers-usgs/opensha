package org.opensha.sha.earthquake.faultSysSolution.inversion;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.math3.stat.StatUtils;
import org.opensha.commons.data.function.HistogramFunction;
import org.opensha.sha.earthquake.faultSysSolution.FaultSystemRupSet;
import org.opensha.sha.earthquake.faultSysSolution.FaultSystemSolution;
import org.opensha.sha.earthquake.faultSysSolution.inversion.constraints.InversionConstraint;
import org.opensha.sha.earthquake.faultSysSolution.inversion.constraints.impl.MFDEqualityInversionConstraint;
import org.opensha.sha.earthquake.faultSysSolution.inversion.constraints.impl.MFDInequalityInversionConstraint;
import org.opensha.sha.earthquake.faultSysSolution.inversion.constraints.impl.RelativeBValueConstraint;
import org.opensha.sha.earthquake.faultSysSolution.inversion.constraints.impl.RupRateMinimizationConstraint;
import org.opensha.sha.earthquake.faultSysSolution.inversion.constraints.impl.SlipRateInversionConstraint;
import org.opensha.sha.earthquake.faultSysSolution.inversion.constraints.impl.SlipRateSegmentationConstraint;
import org.opensha.sha.earthquake.faultSysSolution.inversion.constraints.impl.SlipRateSegmentationConstraint.RateCombiner;
import org.opensha.sha.earthquake.faultSysSolution.inversion.constraints.impl.SlipRateSegmentationConstraint.Shaw07JumpDistSegModel;
import org.opensha.sha.earthquake.faultSysSolution.inversion.constraints.impl.TotalRateInversionConstraint;
import org.opensha.sha.earthquake.faultSysSolution.modules.AveSlipModule;
import org.opensha.sha.earthquake.faultSysSolution.modules.InitialSolution;
import org.opensha.sha.earthquake.faultSysSolution.modules.ModSectMinMags;
import org.opensha.sha.earthquake.faultSysSolution.modules.SectSlipRates;
import org.opensha.sha.earthquake.faultSysSolution.modules.SlipAlongRuptureModel;
import org.opensha.sha.earthquake.faultSysSolution.modules.WaterLevelRates;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.prob.Shaw07JumpDistProb;
import org.opensha.sha.earthquake.faultSysSolution.util.FaultSysTools;
import org.opensha.sha.magdist.GutenbergRichterMagFreqDist;
import org.opensha.sha.magdist.IncrementalMagFreqDist;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;

import scratch.UCERF3.analysis.FaultSystemRupSetCalc;
import scratch.UCERF3.inversion.InversionTargetMFDs;
import scratch.UCERF3.inversion.UCERF3InversionConfiguration;
import scratch.UCERF3.inversion.UCERF3InversionConfiguration.SlipRateConstraintWeightingType;
import scratch.UCERF3.simulatedAnnealing.SerialSimulatedAnnealing;
import scratch.UCERF3.simulatedAnnealing.SimulatedAnnealing;
import scratch.UCERF3.simulatedAnnealing.ThreadedSimulatedAnnealing;
import scratch.UCERF3.simulatedAnnealing.completion.CompletionCriteria;
import scratch.UCERF3.simulatedAnnealing.completion.IterationCompletionCriteria;
import scratch.UCERF3.simulatedAnnealing.completion.ProgressTrackingCompletionCriteria;
import scratch.UCERF3.simulatedAnnealing.completion.TimeCompletionCriteria;
import scratch.UCERF3.simulatedAnnealing.params.GenerationFunctionType;
import scratch.UCERF3.simulatedAnnealing.params.NonnegativityConstraintType;
import scratch.UCERF3.utils.MFD_InversionConstraint;

public class Inversions {
	
	private static SlipRateInversionConstraint slipConstraint(FaultSystemRupSet rupSet, double regularSlipWeight, double normalizedSlipWeight) {
		AveSlipModule aveSlipModule = rupSet.requireModule(AveSlipModule.class);
		SlipAlongRuptureModel slipAlong = rupSet.requireModule(SlipAlongRuptureModel.class);
		double[] targetSlipRates = rupSet.requireModule(SectSlipRates.class).getSlipRates();
		
		SlipRateConstraintWeightingType weightingType;
		if (regularSlipWeight > 0 && normalizedSlipWeight > 0)
			weightingType = SlipRateConstraintWeightingType.BOTH;
		else if (regularSlipWeight > 0)
			weightingType = SlipRateConstraintWeightingType.UNNORMALIZED;
		else if (normalizedSlipWeight > 0)
			weightingType = SlipRateConstraintWeightingType.NORMALIZED_BY_SLIP_RATE;
		else
			return null;
		return new SlipRateInversionConstraint(normalizedSlipWeight, regularSlipWeight, weightingType, rupSet,
				aveSlipModule, slipAlong, targetSlipRates);
	}
	
	private static final GenerationFunctionType PERTURB_DEFAULT = GenerationFunctionType.VARIABLE_EXPONENTIAL_SCALE;
	private static final NonnegativityConstraintType NON_NEG_DEFAULT = NonnegativityConstraintType.TRY_ZERO_RATES_OFTEN;
	
	private static CompletionCriteria getCompletion(String value) {
		value = value.trim().toLowerCase();
		if (value.endsWith("h"))
			return TimeCompletionCriteria.getInHours(Long.parseLong(value.substring(0, value.length()-1)));
		if (value.endsWith("m"))
			return TimeCompletionCriteria.getInMinutes(Long.parseLong(value.substring(0, value.length()-1)));
		if (value.endsWith("s"))
			return TimeCompletionCriteria.getInSeconds(Long.parseLong(value.substring(0, value.length()-1)));
		if (value.endsWith("i"))
			value = value.substring(0, value.length()-1);
		return new IterationCompletionCriteria(Long.parseLong(value));
	}
	
	private static SimulatedAnnealing configSA(CommandLine cmd, FaultSystemRupSet rupSet, InversionInputGenerator inputs) {
		inputs.columnCompress();
		
		int threads = FaultSysTools.getNumThreads(cmd);
		SimulatedAnnealing sa;
		if (threads > 1) {
			int avgThreads = 0;
			if (cmd.hasOption("avg-threads"))
				avgThreads = Integer.parseInt(cmd.getOptionValue("avg-threads"));
			CompletionCriteria avgCompletion = null;
			if (avgThreads > 0) {
				Preconditions.checkArgument(cmd.hasOption("avg-completion"),
						"Averaging enabled but --avg-completion <value> not specified");
				avgCompletion = getCompletion(cmd.getOptionValue("avg-completion"));
				if (avgCompletion == null)
					throw new IllegalArgumentException("Must supply averaging sub-completion time");
			}
			CompletionCriteria subCompletion;
			if (cmd.hasOption("sub-completion"))
				subCompletion = getCompletion(cmd.getOptionValue("sub-completion"));
			else
				subCompletion = TimeCompletionCriteria.getInSeconds(1);
			
			if (avgCompletion != null) {
				int threadsPerAvg = (int)Math.ceil((double)threads/(double)avgThreads);
				Preconditions.checkState(threadsPerAvg < threads);
				Preconditions.checkState(threadsPerAvg > 0);
				
				int threadsLeft = threads;
				
				// arrange lower-level (actual worker) SAs
				List<SimulatedAnnealing> tsas = new ArrayList<>();
				while (threadsLeft > 0) {
					int myThreads = Integer.min(threadsLeft, threadsPerAvg);
					if (myThreads > 1)
						tsas.add(new ThreadedSimulatedAnnealing(inputs.getA(), inputs.getD(),
								inputs.getInitialSolution(), 0d, inputs.getA_ineq(), inputs.getD_ineq(), myThreads, subCompletion));
					else
						tsas.add(new SerialSimulatedAnnealing(inputs.getA(), inputs.getD(),
								inputs.getInitialSolution(), 0d, inputs.getA_ineq(), inputs.getD_ineq()));
					threadsLeft -= myThreads;
				}
				sa = new ThreadedSimulatedAnnealing(tsas, avgCompletion);
				((ThreadedSimulatedAnnealing)sa).setAverage(true);
			} else {
				sa = new ThreadedSimulatedAnnealing(inputs.getA(), inputs.getD(),
						inputs.getInitialSolution(), 0d, inputs.getA_ineq(), inputs.getD_ineq(), threads, subCompletion);
			}
		} else {
			sa = new SerialSimulatedAnnealing(inputs.getA(), inputs.getD(), inputs.getInitialSolution(), 0d,
					inputs.getA_ineq(), inputs.getD_ineq());
		}
		sa.setConstraintRanges(inputs.getConstraintRowRanges());
		
		GenerationFunctionType perturb = PERTURB_DEFAULT;
		if (cmd.hasOption("perturb"))
			perturb = GenerationFunctionType.valueOf(cmd.getOptionValue("perturb"));
		if (perturb == GenerationFunctionType.VARIABLE_EXPONENTIAL_SCALE || perturb == GenerationFunctionType.VARIABLE_NO_TEMP_DEPENDENCE) {
			// compute variable basis
			System.out.println("Computing variable perturbation basis:");
			IncrementalMagFreqDist targetMFD;
			if (rupSet.hasModule(InversionTargetMFDs.class)) {
				targetMFD = rupSet.getModule(InversionTargetMFDs.class).getTotalOnFaultSupraSeisMFD();
			} else {
				// infer target MFD from slip rates
				System.out.println("\tInferring target GR from slip rates");
				GutenbergRichterMagFreqDist gr = inferTargetGRFromSlipRates(rupSet, 1d);
				targetMFD = gr;
			}
			double[] basis = UCERF3InversionConfiguration.getSmoothStartingSolution(rupSet, targetMFD);
			System.out.println("Perturbation-basis range: ["+(float)StatUtils.min(basis)+", "+(float)StatUtils.max(basis)+"]");
			sa.setVariablePerturbationBasis(basis);
		}
		sa.setPerturbationFunc(perturb);
		
		NonnegativityConstraintType nonneg = NON_NEG_DEFAULT;
		if (cmd.hasOption("non-negativity"))
			nonneg = NonnegativityConstraintType.valueOf(cmd.getOptionValue("non-negativity"));
		sa.setNonnegativeityConstraintAlgorithm(nonneg);
		
		return sa;
	}
	
	private static double getMinMagForMFD(FaultSystemRupSet rupSet) {
		if (rupSet.hasModule(ModSectMinMags.class))
			return StatUtils.min(rupSet.requireModule(ModSectMinMags.class).getMinMagForSections());
		return rupSet.getMinMag();
	}

	public static GutenbergRichterMagFreqDist inferTargetGRFromSlipRates(FaultSystemRupSet rupSet, double bValue) {
		double totMomentRate = rupSet.requireModule(SectSlipRates.class).calcTotalMomentRate();
		System.out.println("Inferring target G-R");
		HistogramFunction tempHist = HistogramFunction.getEncompassingHistogram(
				getMinMagForMFD(rupSet), rupSet.getMaxMag(), 0.1d);
		GutenbergRichterMagFreqDist gr = new GutenbergRichterMagFreqDist(
				tempHist.getMinX(), tempHist.size(), tempHist.getDelta(), totMomentRate, bValue);
		return gr;
	}
	
	public static MFD_InversionConstraint restrictMFDRange(MFD_InversionConstraint constr, double minMag, double maxMag) {
		IncrementalMagFreqDist orig = constr.getMagFreqDist();
		int minIndex = -1;
		int maxIndex = 0;
		for (int i=0; i<orig.size(); i++) {
			double mag = orig.getX(i);
			if (minIndex < 0 && (float)mag >= (float)minMag)
				minIndex = i;
			if ((float)mag < (float)maxMag)
				maxIndex = i;
		}
		Preconditions.checkState(minIndex >= 0 && minIndex >= maxIndex,
				"Could not restrict MFD to range [%s, %s]", minMag, maxMag);
		IncrementalMagFreqDist trimmed = new IncrementalMagFreqDist(
				orig.getX(minIndex), orig.getX(maxIndex), 1+maxIndex-minIndex);
		for (int i=0; i<trimmed.size(); i++) {
			int refIndex = i+minIndex;
			Preconditions.checkState((float)trimmed.getX(i) == (float)orig.getX(refIndex));
			trimmed.set(i, orig.getY(refIndex));
		}
		return new MFD_InversionConstraint(trimmed, constr.getRegion());
	}
	
	private static Options createOptions() {
		Options ops = new Options();

		ops.addOption(FaultSysTools.threadsOption());

		Option rupSetOption = new Option("rs", "rupture-set", true,
				"Path to Rupture Set zip file.");
		rupSetOption.setRequired(true);
		ops.addOption(rupSetOption);

		Option outputOption = new Option("o", "output-file", true,
				"Path where output Solution zip file will be written.");
		outputOption.setRequired(true);
		ops.addOption(outputOption);
		
		/*
		 *  Inversion configuration
		 */
		
		// slip rate constraint
		
		Option slipConstraint = new Option("sl", "slip-constraint", false, "Enables the slip-rate constraint.");
		slipConstraint.setRequired(false);
		ops.addOption(slipConstraint);
		
		Option slipWeight = new Option("sw", "slip-weight", true, "Sets weight for the slip-rate constraint.");
		slipWeight.setRequired(false);
		ops.addOption(slipWeight);
		
		Option normSlipWeight = new Option("nsw", "norm-slip-weight", true, "Sets weight for the normalized slip-rate constraint.");
		normSlipWeight.setRequired(false);
		ops.addOption(normSlipWeight);
		
		// MFD constraint
		
		Option mfdConstraint = new Option("mfd", "mfd-constraint", false, "Enables the MFD constraint. "
				+ "Must supply either --infer-target-gr or --mfd-total-rate, or Rupture Set must have "
				+ "InversionTargetMFDs module already attached.");
		mfdConstraint.setRequired(false);
		ops.addOption(mfdConstraint);
		
		Option mfdWeight = new Option("mw", "mfd-weight", true, "Sets weight for the MFD constraint.");
		mfdWeight.setRequired(false);
		ops.addOption(mfdWeight);
		
		Option mfdFromSlip = new Option("itgr", "infer-target-gr", false,
				"Flag to infer target MFD as a G-R from total deformation model moment rate.");
		mfdFromSlip.setRequired(false);
		ops.addOption(mfdFromSlip);
		
		Option grB = new Option("b", "b-value", true, "Gutenberg-Richter b-value.");
		grB.setRequired(false);
		ops.addOption(grB);
		
		Option mfdTotRate = new Option("mtr", "mfd-total-rate", true, "Total (cumulative) rate for the MFD constraint. "
				+ "By default, this will apply to the minimum magnitude from the rupture set, but another magnitude can "
				+ "be supplied with --mfd-min-mag");
		mfdTotRate.setRequired(false);
		ops.addOption(mfdTotRate);
		
		Option mfdMinMag = new Option("mmm", "mfd-min-mag", true, "Minimum magnitude for the MFD constraint "
				+ "(default is minimum magnitude of the rupture set), used with --mfd-total-rate.");
		mfdMinMag.setRequired(false);
		ops.addOption(mfdMinMag);
		
		// TODO add to docs
		Option mfdIneq = new Option("min", "mfd-ineq", false, "Flag to configure MFD constraints as inequality rather "
				+ "than equality constraints. Used in conjunction with --mfd-constraint. Use --mfd-transition-mag "
				+ "instead if you want to transition from equality to inequality constraints.");
		mfdIneq.setRequired(false);
		ops.addOption(mfdIneq);
		
		// TODO add to docs
		Option mfdTransMag = new Option("mtm", "mfd-transition-mag", true, "Magnitude at and above which the mfd "
				+ "constraint should be applied as a inequality, allowing a natural taper (default is equality only).");
		mfdTransMag.setRequired(false);
		ops.addOption(mfdTransMag);
		
		// TODO: add to docs
		Option relGRConstraint = new Option("rgr", "rel-gr-constraint", false, "Enables the relative Gutenberg-Richter "
				+ "constraint, which constraints the overal MFD to be G-R withought constraining the total event rate. "
				+ "The b-value will default to 1, override with --b-value <vlalue>. Set constraint weight with "
				+ "--mfd-weight <weight>, or configure as an inequality with --mfd-ineq.");
		relGRConstraint.setRequired(false);
		ops.addOption(relGRConstraint);
		
		// moment rate constraint
		
		// doesn't work well, and slip rate constraint handles moment anyway
//		Option momRateConstraint = new Option("mr", "moment-rate-constraint", false, "Enables the total moment-rate constraint. By default, "
//				+ "the slip-rate implied moment rate will be used, but you can supply your own target moment rate with --target-moment-rate.");
//		momRateConstraint.setRequired(false);
//		ops.addOption(momRateConstraint);
//		
//		Option mrWeight = new Option("mrw", "moment-rate-weight", true, "Sets weight for the moment-rate constraint.");
//		mrWeight.setRequired(false);
//		ops.addOption(mrWeight);
//		
//		Option momRate = new Option("tmr", "target-moment-rate", true, "Specifies a custom target moment-rate in N-m/yr"
//				+ " (must also supply --moment-rate-constraint option)");
//		momRate.setRequired(false);
//		ops.addOption(momRate);
		
		// event rate constraint
		
		Option eventRateConstraint = new Option("er", "event-rate-constraint", true, "Enables the total event-rate constraint"
				+ " with the supplied total event rate");
		eventRateConstraint.setRequired(false);
		ops.addOption(eventRateConstraint);
		
		Option erWeight = new Option("erw", "event-rate-weight", true, "Sets weight for the event-rate constraint.");
		erWeight.setRequired(false);
		ops.addOption(erWeight);
		
		// segmentation constraint
		
		Option slipSegConstraint = new Option("seg", "slip-seg-constraint", false,
				"Enables the slip-rate segmentation constraint.");
		slipSegConstraint.setRequired(false);
		ops.addOption(slipSegConstraint);
		
		Option normSlipSegConstraint = new Option("nseg", "norm-slip-seg-constraint", false,
				"Enables the normalized slip-rate segmentation constraint.");
		normSlipSegConstraint.setRequired(false);
		ops.addOption(normSlipSegConstraint);
		
		Option netSlipSegConstraint = new Option("ntseg", "net-slip-seg-constraint", false,
				"Enables the net (distance-binned) slip-rate segmentation constraint.");
		netSlipSegConstraint.setRequired(false);
		ops.addOption(netSlipSegConstraint);
		
		Option slipSegIneq = new Option("segi", "slip-seg-ineq", false,
				"Flag to make segmentation constraints an inequality constraint (only applies if segmentation rate is exceeded).");
		slipSegIneq.setRequired(false);
		ops.addOption(slipSegIneq);
		
		Option segR0 = new Option("r0", "shaw-r0", true,
				"Sets R0 in the Shaw (2007) jump-distance probability model in km"
				+ " (used for segmentation constraint). Default: "+(float)Shaw07JumpDistProb.R0_DEFAULT);
		segR0.setRequired(false);
		ops.addOption(segR0);
		
		Option slipSegWeight = new Option("segw", "slip-seg-weight", true,
				"Sets weight for the slip-rate segmentation constraint.");
		slipSegWeight.setRequired(false);
		ops.addOption(slipSegWeight);
		
		// minimization constraint
		
		// TODO add to docs
		Option minimizeBelowMin = new Option("mbs", "minimize-below-sect-min", false,
				"Flag to enable the minimzation constraint for rupture sets that have modified section minimum magnitudes."
				+ " If enabled, rates for all ruptures below those minimum magnitudes will be minimized.");
		minimizeBelowMin.setRequired(false);
		ops.addOption(minimizeBelowMin);
		
		// TODO add to docs
		Option mwWeight = new Option("mw", "minimize-weight", true, "Sets weight for the minimization constraint.");
		mwWeight.setRequired(false);
		ops.addOption(mwWeight);
		
		/*
		 *  Simulated Annealing parameters
		 */
		
		String complText = "If either no suffix or 'i' is appended, then it is assumed to be an iteration count. "
				+ "Specify times in hours, minutes, or seconds by appending 'h', 'm', or 's' respecively. Fractions are not allowed.";
		
		Option completionOption = new Option("c", "completion", true, "Total inversion completion criteria. "+complText);
		completionOption.setRequired(true);
		ops.addOption(completionOption);
		
		Option avgOption = new Option("at", "avg-threads", true, "Enables a top layer of threads that average results "
				+ "of worker threads at fixed intervals. Supply the number of averaging threads, which must be < threads. "
				+ "Default is no averaging, if enabled you must also supply --avg-completion <value>.");
		avgOption.setRequired(false);
		ops.addOption(avgOption);
		
		Option avgCompletionOption = new Option("ac", "avg-completion", true,
				"Interval between across-thread averaging. "+complText);
		avgCompletionOption.setRequired(false);
		ops.addOption(avgCompletionOption);
		
		Option subCompletionOption = new Option("sc", "sub-completion", true,
				"Interval between across-thread synchronization. "+complText+" Default: 1s");
		subCompletionOption.setRequired(false);
		ops.addOption(subCompletionOption);
		
		Option perturbOption = new Option("pt", "perturb", true, "Perturbation function. One of "
				+FaultSysTools.enumOptions(GenerationFunctionType.class)+". Default: "+PERTURB_DEFAULT.name());
		perturbOption.setRequired(false);
		ops.addOption(perturbOption);
		
		Option nonNegOption = new Option("nn", "non-negativity", true, "Non-negativity constraint. One of "
				+FaultSysTools.enumOptions(NonnegativityConstraintType.class)+". Default: "+NON_NEG_DEFAULT.name());
		nonNegOption.setRequired(false);
		ops.addOption(nonNegOption);
		
		return ops;
	}

	public static void main(String[] args) {
		try {
			run(args);
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}
	}
	
	public static FaultSystemSolution run(String[] args) throws IOException {
		return run(args, null);
	}
	
	public static FaultSystemSolution run(String[] args, List<InversionConstraint> constraints) throws IOException {
		CommandLine cmd = FaultSysTools.parseOptions(createOptions(), args, Inversions.class);
		
		File rupSetFile = new File(cmd.getOptionValue("rupture-set"));
		FaultSystemRupSet rupSet = FaultSystemRupSet.load(rupSetFile);
		
		if (constraints == null)
			constraints = new ArrayList<>();
		else if (!constraints.isEmpty())
			constraints = new ArrayList<>(constraints);
		
		File outputFile = new File(cmd.getOptionValue("output-file"));
		
		if (cmd.hasOption("slip-constraint")) {
			double weight = 1d;
			double normWeight = 0d;
			
			if (cmd.hasOption("norm-slip-weight"))
				normWeight = Double.parseDouble(cmd.getOptionValue("norm-slip-weight"));
			if (cmd.hasOption("slip-weight"))
				weight = Double.parseDouble(cmd.getOptionValue("slip-weight"));
			
			SlipRateInversionConstraint constr = slipConstraint(rupSet, weight, normWeight);
			Preconditions.checkArgument(constr != null, "Must supply a positive slip rate weight");
			
			constraints.add(constr);
		}
		
		if (cmd.hasOption("mfd-constraint")) {
			double weight = 1d;

			if (cmd.hasOption("mfd-weight"))
				weight = Double.parseDouble(cmd.getOptionValue("mfd-weight"));
			
			double bValue = 1d;
			if (cmd.hasOption("b-value"))
				bValue = Double.parseDouble(cmd.getOptionValue("b-value"));
			
			InversionTargetMFDs targetMFDs;
			
			if (cmd.hasOption("infer-target-gr")) {
				IncrementalMagFreqDist targetMFD = inferTargetGRFromSlipRates(rupSet, bValue);
				
				List<MFD_InversionConstraint> mfdConstraints = List.of(new MFD_InversionConstraint(targetMFD, null));
				
				targetMFDs = new InversionTargetMFDs.Precomputed(rupSet, null, targetMFD, null, null, mfdConstraints, null);
			} else if (cmd.hasOption("mfd-total-rate")) {
				double minX = 0.1*Math.floor(getMinMagForMFD(rupSet)*10d);
				double minTargetMag = minX;
				if (cmd.hasOption("mfd-min-mag")) {
					minTargetMag = Double.parseDouble(cmd.getOptionValue("mfd-min-mag"));
					minX = Math.min(minX, minTargetMag);
				}
				
				Preconditions.checkArgument(cmd.hasOption("mfd-total-rate"),
						"MFD constraint enabled, but no --mfd-total-rate <rate> or --infer-target-gr");
				double totRate = Double.parseDouble(cmd.getOptionValue("mfd-total-rate"));
				
				HistogramFunction tempHist = HistogramFunction.getEncompassingHistogram(minX, rupSet.getMaxMag(), 0.1d);
				GutenbergRichterMagFreqDist targetGR = new GutenbergRichterMagFreqDist(
						tempHist.getMinX(), tempHist.getMaxX(), tempHist.size());
				targetGR.scaleToCumRate(minTargetMag, totRate);
				
				List<MFD_InversionConstraint> mfdConstraints = List.of(new MFD_InversionConstraint(targetGR, null));
				
				targetMFDs = new InversionTargetMFDs.Precomputed(rupSet, null, targetGR, null, null, mfdConstraints, null);
			} else {
				Preconditions.checkState(rupSet.hasModule(InversionTargetMFDs.class),
						"MFD Constraint enabled, but no target MFD specified. Rupture Set must either already have "
						+ "target MFDs attached, or MFD should be specified via --infer-target-gr or --mfd-total-rate <rate>.");
				targetMFDs = rupSet.requireModule(InversionTargetMFDs.class);
			}
			
			rupSet.addModule(targetMFDs);
			
			List<? extends MFD_InversionConstraint> mfdConstraints = targetMFDs.getMFD_Constraints();
			
			for (MFD_InversionConstraint constr : mfdConstraints)
				System.out.println("MFD Constraint for region "
						+(constr.getRegion() == null ? "null" : constr.getRegion().getName())
						+":\n"+constr.getMagFreqDist());
			
			if (cmd.hasOption("mfd-ineq")) {
				Preconditions.checkArgument(!cmd.hasOption("mfd-transition-mag"),
						"Can't specify both --mfd-transition-mag and --mfd-ineq");
				constraints.add(new MFDInequalityInversionConstraint(rupSet, weight, mfdConstraints));
			} else if (cmd.hasOption("mfd-transition-mag")) {
				double transMag = Double.parseDouble(cmd.getOptionValue("mfd-transition-mag"));
				List<MFD_InversionConstraint> eqConstrs = new ArrayList<>();
				List<MFD_InversionConstraint> ieqConstrs = new ArrayList<>();
				for (MFD_InversionConstraint constr : mfdConstraints) {
					eqConstrs.add(restrictMFDRange(constr, Double.NEGATIVE_INFINITY, transMag));
					ieqConstrs.add(restrictMFDRange(constr, transMag, Double.POSITIVE_INFINITY));
				}
				constraints.add(new MFDEqualityInversionConstraint(rupSet, weight, eqConstrs, null));
				constraints.add(new MFDInequalityInversionConstraint(rupSet, weight, ieqConstrs));
			} else {
				constraints.add(new MFDEqualityInversionConstraint(rupSet, weight, mfdConstraints, null));
			}
		}
		
		if (cmd.hasOption("rel-gr-constraint")) {
			double weight = 1d;

			if (cmd.hasOption("mfd-weight"))
				weight = Double.parseDouble(cmd.getOptionValue("mfd-weight"));
			
			double bValue = 1d;
			if (cmd.hasOption("b-value"))
				bValue = Double.parseDouble(cmd.getOptionValue("b-value"));
			
			boolean ineq = cmd.hasOption("mfd-ineq");
			
			constraints.add(new RelativeBValueConstraint(rupSet, bValue, weight, ineq));
		}
		
		// doesn't work well, and slip rate constraint handles moment anyway
//		if (cmd.hasOption("moment-rate-constraint")) {
//			double targetMomentRate;
//			if (cmd.hasOption("target-moment-rate"))
//				targetMomentRate = Double.parseDouble(cmd.getOptionValue("target-moment-rate"));
//			else
//				targetMomentRate = rupSet.requireModule(SectSlipRates.class).calcTotalMomentRate();
//			System.out.println("Target moment rate: "+targetMomentRate+" N-m/yr");
//			
//			double weight;
//			if (cmd.hasOption("moment-rate-weight"))
//				weight = Double.parseDouble(cmd.getOptionValue("moment-rate-weight"));
//			else
//				weight = 1e-5;
//			constraints.add(new TotalMomentInversionConstraint(rupSet, weight, targetMomentRate));
//		}
		
		if (cmd.hasOption("event-rate-constraint")) {
			double targetEventRate = Double.parseDouble(cmd.getOptionValue("event-rate-constraint"));
			System.out.println("Target event rate: "+targetEventRate+" /yr");
			
			double weight = 1d;
			if (cmd.hasOption("event-rate-weight"))
				weight = Double.parseDouble(cmd.getOptionValue("event-rate-weight"));
			constraints.add(new TotalRateInversionConstraint(rupSet, weight, targetEventRate));
		}
		
		if (cmd.hasOption("slip-seg-constraint") || cmd.hasOption("norm-slip-seg-constraint")
				|| cmd.hasOption("net-slip-seg-constraint")) {
			System.out.println("Adding slip rate segmentation constraints");
			double weight = 1d;
			if (cmd.hasOption("slip-seg-weight"))
				weight = Double.parseDouble(cmd.getOptionValue("slip-seg-weight"));
			
			double r0 = Shaw07JumpDistProb.R0_DEFAULT;
			if (cmd.hasOption("shaw-r0"))
				r0 = Double.parseDouble(cmd.getOptionValue("shaw-r0"));
			
			double a = 1d;
			Shaw07JumpDistSegModel segModel = new Shaw07JumpDistSegModel(a, r0);
			
			RateCombiner combiner = RateCombiner.MIN; // TODO: make selectable?
			
			boolean inequality = cmd.hasOption("slip-seg-ineq");
			
			boolean doNormalized = cmd.hasOption("norm-slip-seg-constraint");
			boolean doRegular = cmd.hasOption("slip-seg-constraint");
			boolean doNet = cmd.hasOption("net-slip-seg-constraint");
			
			if (doNet)
				constraints.add(new SlipRateSegmentationConstraint(
						rupSet, segModel, combiner, weight, true, inequality, true));
			if (doNormalized)
				constraints.add(new SlipRateSegmentationConstraint(
						rupSet, segModel, combiner, weight, true, inequality, false));
			if (doRegular)
				constraints.add(new SlipRateSegmentationConstraint(
						rupSet, segModel, combiner, weight, false, inequality, false));
		}
		
		if (cmd.hasOption("minimize-below-sect-min")) {
			Preconditions.checkState(rupSet.hasModule(ModSectMinMags.class),
					"Rupture set must have the ModSectMinMags module attached to enable the minimzation constraint.");
			
			double weight = 1d;
			if (cmd.hasOption("minimize-weight"))
				weight = Double.parseDouble(cmd.getOptionValue("minimize-weight"));
			
			ModSectMinMags modMinMags = rupSet.requireModule(ModSectMinMags.class);
			List<Integer> belowMinIndexes = new ArrayList<>();
			for (int r=0; r<rupSet.getNumRuptures(); r++)
				if (FaultSystemRupSetCalc.isRuptureBelowSectMinMag(rupSet, r, modMinMags))
					belowMinIndexes.add(r);
			System.out.println("Minimizing rates of "+belowMinIndexes.size()
				+" ruptures below the modified section minimum magnitudes");
			constraints.add(new RupRateMinimizationConstraint(weight, belowMinIndexes));
		}
		
		Preconditions.checkState(!constraints.isEmpty(), "No constraints specified.");
		
		CompletionCriteria completion = getCompletion(cmd.getOptionValue("completion"));
		if (completion == null)
			throw new IllegalArgumentException("Must supply total inversion time or iteration count");
		
		InversionInputGenerator inputs = new InversionInputGenerator(rupSet, constraints);
		inputs.generateInputs(true);
		
		ProgressTrackingCompletionCriteria progress = new ProgressTrackingCompletionCriteria(completion);
		
		SimulatedAnnealing sa = configSA(cmd, rupSet, inputs);
		
		System.out.println("Annealing!");
		sa.iterate(progress);
		
		System.out.println("DONE. Building solution...");
		double[] rawSol = sa.getBestSolution();
		double[] rates = inputs.adjustSolutionForWaterLevel(rawSol);
		
		FaultSystemSolution sol = new FaultSystemSolution(rupSet, rates);
		// add inversion progress
		sol.addModule(progress.getProgress());
		// add water level rates
		if (inputs.getWaterLevelRates() != null)
			sol.addModule(new WaterLevelRates(inputs.getWaterLevelRates()));
		if (inputs.hasInitialSolution())
			sol.addModule(new InitialSolution(inputs.getInitialSolution()));
		
		String info = "Fault System Solution generated with OpenSHA Fault System Tools ("
				+ "https://github.com/opensha/opensha-fault-sys-tools), using the following command:"
				+ "\n\nfst_inversion_runner.sh "+Joiner.on(" ").join(args);
		sol.setInfoString(info);
		
		// write solution
		sol.write(outputFile);
		
		return sol;
	}

}
