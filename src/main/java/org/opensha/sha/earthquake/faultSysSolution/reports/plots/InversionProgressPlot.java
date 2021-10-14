package org.opensha.sha.earthquake.faultSysSolution.reports.plots;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.opensha.commons.util.Interpolate;
import org.opensha.commons.util.MarkdownUtils;
import org.opensha.commons.util.MarkdownUtils.TableBuilder;
import org.opensha.commons.util.modules.OpenSHA_Module;
import org.opensha.sha.earthquake.faultSysSolution.FaultSystemSolution;
import org.opensha.sha.earthquake.faultSysSolution.inversion.sa.SimulatedAnnealing;
import org.opensha.sha.earthquake.faultSysSolution.inversion.sa.ThreadedSimulatedAnnealing;
import org.opensha.sha.earthquake.faultSysSolution.inversion.sa.completion.AnnealingProgress;
import org.opensha.sha.earthquake.faultSysSolution.modules.InitialSolution;
import org.opensha.sha.earthquake.faultSysSolution.modules.WaterLevelRates;
import org.opensha.sha.earthquake.faultSysSolution.reports.AbstractSolutionPlot;
import org.opensha.sha.earthquake.faultSysSolution.reports.ReportMetadata;

public class InversionProgressPlot extends AbstractSolutionPlot {

	@Override
	public String getName() {
		return "Simulated Annealing Energy";
	}

	@Override
	public List<String> plot(FaultSystemSolution sol, ReportMetadata meta, File resourcesDir, String relPathToResources,
			String topLink) throws IOException {
		AnnealingProgress progress = sol.requireModule(AnnealingProgress.class);
		AnnealingProgress compProgress = null;
		if (meta.hasComparisonSol())
			compProgress = meta.comparison.sol.getModule(AnnealingProgress.class);
		
		List<String> lines = new ArrayList<>();
		
		long millis = progress.getTime(progress.size()-1);
		double secs = millis/1000d;
		double mins = secs/60d;
		double hours = mins/60d;
		long perturbs = progress.getNumPerturbations(progress.size()-1);
		long iters = progress.getIterations(progress.size()-1);
		double totalEnergy = progress.getEnergies(progress.size()-1)[0];

		int ips = (int)((double)iters/secs + 0.5);
		
		TableBuilder table = MarkdownUtils.tableBuilder();
		
		// will invert this table, so rows = columns here
		table.initNewLine();
		if (compProgress != null)
			table.addColumn("");
		table.addColumn("**Iterations**").addColumn("**Time**").addColumn("**Perturbations**").addColumn("**Total Energy**");
		table.finalizeLine().initNewLine();
		if (compProgress != null)
			table.addColumn("Primary");
		
		table.addColumn(countDF.format(iters)+" ("+countDF.format(ips)+" /sec)");
		table.addColumn(ThreadedSimulatedAnnealing.timeStr(millis));
		table.addColumn(countDF.format(perturbs));
		table.addColumn((float)totalEnergy);
		table.finalizeLine();
		
		long cperturbs = -1;
		if (compProgress != null) {
			long cmillis = compProgress.getTime(compProgress.size()-1);
			double csecs = cmillis/1000d;
			cperturbs = compProgress.getNumPerturbations(compProgress.size()-1);
			long citers = compProgress.getIterations(compProgress.size()-1);
			double ctotalEnergy = compProgress.getEnergies(compProgress.size()-1)[0];

			int cips = (int)((double)citers/csecs + 0.5);
			
			table.initNewLine().addColumn("Comparison");
			table.addColumn(countDF.format(citers)+" ("+countDF.format(cips)+" /sec)");
			table.addColumn(ThreadedSimulatedAnnealing.timeStr(cmillis));
			table.addColumn(countDF.format(cperturbs));
			table.addColumn((float)ctotalEnergy);
			table.finalizeLine();
		}
		
		lines.addAll(table.invert().build());
		lines.add("");
		
		lines.add(getSubHeading()+" Final Energies");
		lines.add(topLink); lines.add("");
		
		table = MarkdownUtils.tableBuilder();
		long deltaEachMillis;
		if (hours > 20)
			deltaEachMillis = 1000l*60l*60l*5l; // 5 hours
		else if (hours > 9)
			deltaEachMillis = 1000l*60l*60l*2l; // 2 hours
		else if (hours > 3)
			deltaEachMillis = 1000l*60l*60l*1l; // 1 hour
		else if (hours > 1)
			deltaEachMillis = 1000l*60l*30l; // 30 mins
		else if (mins > 30)
			deltaEachMillis = 1000l*60l*15l; // 15 mins
		else if (mins > 10)
			deltaEachMillis = 1000l*60l*5l; // 5 mins
		else
			deltaEachMillis = 1000l*60l*5l; // 1 min
		table.initNewLine().addColumn("Energy Type").addColumn("Final Energy ("
			+ThreadedSimulatedAnnealing.timeStr(progress.getTime(progress.size()-1))+")").addColumn("% of Total");
		List<Long> progressTimes = new ArrayList<>();
		List<Integer> progressIndexAfters = new ArrayList<>();
		int curIndex = 0;
		long maxTimeToInclude = (long)(millis*0.95d);
		for (long t=deltaEachMillis; t<maxTimeToInclude; t+=deltaEachMillis) {
			if (t < progress.getTime(0))
				continue;
			progressTimes.add(t);
			String str = "";
			if (t == deltaEachMillis)
				str = "After ";
			str += ThreadedSimulatedAnnealing.timeStr(t);
//			System.out.println(str+" at "+t);
			table.addColumn("_"+str+"_");
			while (curIndex < progress.size()) {
				long time = progress.getTime(curIndex);
				if (time >= t)
					break;
				curIndex++;
			}
			progressIndexAfters.add(curIndex);
		}
		table.finalizeLine();
		
		double[] finalEnergies = progress.getEnergies(progress.size()-1);
		List<String> types = progress.getEnergyTypes();
		for (int t=0; t<types.size(); t++) {
			table.initNewLine();
			table.addColumn("**"+types.get(t)+"**");
			if (t == 0)
				table.addColumn("**"+(float)finalEnergies[t]+"**").addColumn("");
			else
				table.addColumn((float)finalEnergies[t]).addColumn(percentDF.format(finalEnergies[t]/finalEnergies[0]));
			for (int i=0; i<progressTimes.size(); i++) {
				long time = progressTimes.get(i);
				int i1 = progressIndexAfters.get(i);
				double val;
				if (i1 == 0) {
					val = progress.getEnergies(i1)[t];
				} else if (i1 >= progress.size()) {
					val = progress.getEnergies(progress.size()-1)[t];
				} else {
					// interpolate
					int i0 = i1-1;
					double x1 = progress.getTime(i0);
					double x2 = progress.getTime(i1);
					double y1 = progress.getEnergies(i0)[t];
					double y2 = progress.getEnergies(i1)[t];
					val = Interpolate.findY(x1, y1, x2, y2, time);
				}
				String str = (float)val+"";
				if (i1 == 0 || i1 >= progress.size())
					str += "*";
				table.addColumn("_"+str+"_");
			}
			table.finalizeLine();
		}
		lines.addAll(table.build());
		
		// now plots
		String prefix = "sa_progress";
		SimulatedAnnealing.writeProgressPlots(progress, resourcesDir, prefix, sol.getRupSet().getNumRuptures(), compProgress);
		
		lines.add("");
		lines.add(getSubHeading()+" Energy Progress");
		lines.add(topLink); lines.add("");
		
		lines.add("![Energy vs Time]("+relPathToResources+"/"+prefix+"_energy_vs_time.png)");
		lines.add("");
		
		lines.add("![Energy vs Iterations]("+relPathToResources+"/"+prefix+"_energy_vs_iters.png)");
		lines.add("");
		
		lines.add("![Perturbations]("+relPathToResources+"/"+prefix+"_perturb_vs_iters.png)");
		
		lines.add("");
		lines.add(getSubHeading()+" Rate Distribution");
		lines.add(topLink); lines.add("");
		
		double[] rates = sol.getRateForAllRups();
		double[] ratesNoMin;
		if (sol.hasModule(WaterLevelRates.class))
			ratesNoMin = sol.getModule(WaterLevelRates.class).subtractFrom(rates);
		else
			ratesNoMin = rates;
		double[] initial = sol.hasModule(InitialSolution.class) ?
				sol.getModule(InitialSolution.class).get() : new double[rates.length];
		
		int numNonZero = 0;
		int numAboveWaterlevel = 0;
		for (int r=0; r<rates.length; r++) {
			if (rates[r] > 0) {
				numNonZero++;
				if (ratesNoMin[r] > 0)
					numAboveWaterlevel++;
			}
		}
		
		table = MarkdownUtils.tableBuilder();
		
		// will invert, rows = columns here
		table.initNewLine();
		if (compProgress != null)
			table.addColumn("");
		table.addColumn("**Non-zero ruptures**");
		boolean equivRups = compProgress != null && meta.primary.rupSet.isEquivalentTo(meta.comparison.rupSet);
		if (equivRups)
			table.addColumn("**Unique non-zero ruptures**").addColumn("**Rate of unique non-zero ruptures**");
		if (ratesNoMin != rates)
			table.addColumn("**Ruptures above water-level**");
		table.addColumn("**Avg. # perturbations per rupture**").addColumn("**Avg. # perturbations per perturbed rupture**");
		table.finalizeLine().initNewLine();
		if (compProgress != null)
			table.addColumn("Primary");
		
		table.addColumn(countDF.format(numNonZero)
				+" ("+percentDF.format((double)numNonZero/(double)rates.length)+")");
		if (equivRups) {
			double[] crates = meta.comparison.sol.getRateForAllRups();
			int uniqueNZ = 0;
			double uniqueRate = 0d;
			for (int r=0; r<rates.length; r++) {
				if (rates[r] > 0 && crates[r] == 0) {
					uniqueNZ++;
					uniqueRate += rates[r];
				}
			}
			table.addColumn(countDF.format(uniqueNZ)
					+" ("+percentDF.format((double)uniqueNZ/(double)rates.length)+")");
			table.addColumn((float)uniqueRate
					+" ("+percentDF.format((double)uniqueRate/(double)sol.getTotalRateForAllFaultSystemRups())+")");
		}
		if (ratesNoMin != rates)
			table.addColumn(countDF.format(numAboveWaterlevel)
				+" ("+percentDF.format((double)numAboveWaterlevel/(double)rates.length)+")");
		table.addColumn((float)(perturbs/(double)rates.length));
		table.addColumn((float)(perturbs/(double)numAboveWaterlevel));
		table.finalizeLine();
		
		if (compProgress != null) {
			double[] crates = meta.comparison.sol.getRateForAllRups();
			double[] cratesNoMin;
			if (meta.comparison.sol.hasModule(WaterLevelRates.class))
				cratesNoMin = meta.comparison.sol.getModule(WaterLevelRates.class).subtractFrom(crates);
			else
				cratesNoMin = crates;
			
			int cnumNonZero = 0;
			int cnumAboveWaterlevel = 0;
			for (int r=0; r<crates.length; r++) {
				if (crates[r] > 0) {
					cnumNonZero++;
					if (cratesNoMin[r] > 0)
						cnumAboveWaterlevel++;
				}
			}
			
			table.initNewLine().addColumn("Comparison");
			table.addColumn(countDF.format(cnumNonZero)
					+" ("+percentDF.format((double)cnumNonZero/(double)crates.length)+")");
			if (equivRups) {
				int uniqueNZ = 0;
				double uniqueRate = 0d;
				for (int r=0; r<rates.length; r++) {
					if (crates[r] > 0 && rates[r] == 0) {
						uniqueNZ++;
						uniqueRate += crates[r];
					}
				}
				table.addColumn(countDF.format(uniqueNZ)
						+" ("+percentDF.format((double)uniqueNZ/(double)crates.length)+")");
				table.addColumn((float)uniqueRate+" ("+percentDF.format((double)uniqueRate
						/(double)meta.comparison.sol.getTotalRateForAllFaultSystemRups())+")");
			}
			if (ratesNoMin != rates) {
				if (cratesNoMin != crates)
					table.addColumn(countDF.format(cnumAboveWaterlevel)
							+" ("+percentDF.format((double)cnumAboveWaterlevel/(double)crates.length)+")");
				else
					table.addColumn("");
			}
			table.addColumn((float)(cperturbs/(double)crates.length));
			table.addColumn((float)(cperturbs/(double)cnumAboveWaterlevel));
		}
		
		lines.addAll(table.invert().build());
		lines.add("");
		
		if (compProgress == null)
			SimulatedAnnealing.writeRateVsRankPlot(resourcesDir, prefix+"_rate_dist", ratesNoMin, rates, initial);
		else
			SimulatedAnnealing.writeRateVsRankPlot(resourcesDir, prefix+"_rate_dist", ratesNoMin, rates, initial,
					meta.comparison.sol.getRateForAllRups());
		lines.add("![Rate Distribution]("+relPathToResources+"/"+prefix+"_rate_dist.png)");
		lines.add("");
		lines.add("![Cumulative Rate Distribution]("+relPathToResources+"/"+prefix+"_rate_dist_cumulative.png)");
		
		return lines;
	}

	@Override
	public Collection<Class<? extends OpenSHA_Module>> getRequiredModules() {
		return List.of(AnnealingProgress.class);
	}

}
