package org.opensha.sha.earthquake.rupForecastImpl.nshm23.util;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.apache.commons.cli.CommandLine;
import org.opensha.commons.logicTree.LogicTreeBranch;
import org.opensha.sha.earthquake.faultSysSolution.FaultSystemRupSet;
import org.opensha.sha.earthquake.faultSysSolution.FaultSystemSolution;
import org.opensha.sha.earthquake.faultSysSolution.inversion.ClusterSpecificInversionSolver;
import org.opensha.sha.earthquake.faultSysSolution.inversion.InversionConfiguration;
import org.opensha.sha.earthquake.faultSysSolution.inversion.InversionConfigurationFactory;
import org.opensha.sha.earthquake.faultSysSolution.modules.ClusterRuptures;
import org.opensha.sha.earthquake.faultSysSolution.modules.ConnectivityClusters;
import org.opensha.sha.earthquake.faultSysSolution.modules.InitialSolution;
import org.opensha.sha.earthquake.faultSysSolution.modules.InversionMisfitProgress;
import org.opensha.sha.earthquake.faultSysSolution.modules.InversionMisfitStats;
import org.opensha.sha.earthquake.faultSysSolution.modules.InversionMisfits;
import org.opensha.sha.earthquake.faultSysSolution.modules.RuptureSubSetMappings;
import org.opensha.sha.earthquake.faultSysSolution.modules.WaterLevelRates;
import org.opensha.sha.earthquake.faultSysSolution.modules.ConnectivityClusters.ConnectivityClusterSolutionMisfits;
import org.opensha.sha.earthquake.faultSysSolution.modules.SolutionLogicTree.SolutionProcessor;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.plausibility.impl.prob.RuptureProbabilityCalc.BinaryRuptureProbabilityCalc;
import org.opensha.sha.earthquake.faultSysSolution.ruptures.util.ConnectivityCluster;
import org.opensha.sha.earthquake.rupForecastImpl.nshm23.NSHM23_InvConfigFactory;
import org.opensha.sha.earthquake.rupForecastImpl.nshm23.logicTree.SupraSeisBValues;

/**
 * 
 * @author kevin
 *
 */
public class ClassicModelInversionSolver extends ClusterSpecificInversionSolver {
	
	private AnalyticalSingleFaultInversionSolver analytical;
	private BinaryRuptureProbabilityCalc rupProbCalc;
	
	public ClassicModelInversionSolver(FaultSystemRupSet rupSet, LogicTreeBranch<?> branch) {
		analytical = new AnalyticalSingleFaultInversionSolver(branch.requireValue(SupraSeisBValues.class).bValue);
		rupProbCalc = NSHM23_InvConfigFactory.getExclusionModel(rupSet, branch, rupSet.requireModule(ClusterRuptures.class));
	}

	@Override
	protected BinaryRuptureProbabilityCalc getRuptureExclusionModel(FaultSystemRupSet rupSet,
			LogicTreeBranch<?> branch) {
		return rupProbCalc;
	}

	@Override
	protected boolean shouldInvert(ConnectivityCluster cluster) {
		// only run inversions when multiple parent sections are involved
		return cluster.getParentSectIDs().size() > 1;
	}

	@Override
	public FaultSystemSolution run(FaultSystemRupSet rupSet, InversionConfigurationFactory factory,
			LogicTreeBranch<?> branch, int threads, CommandLine cmd) throws IOException {
		// first do inversion-based solutions
		FaultSystemSolution inversionSol = super.run(rupSet, factory, branch, threads, cmd);
		if (inversionSol == null) {
			// simplest case, all analytical
			return analytical.run(rupSet, factory, branch, threads, cmd);
		}
		ConnectivityClusters clusters = rupSet.requireModule(ConnectivityClusters.class);
		// now add in analytical
		HashSet<Integer> analyticalSects = new HashSet<>();
		for (ConnectivityCluster cluster : clusters)
			if (!shouldInvert(cluster))
				analyticalSects.addAll(cluster.getSectIDs());
		System.out.println("Calculating analytical solution for "+analyticalSects.size()+"/"+rupSet.getNumSections()+" sections");
		if (analyticalSects.isEmpty()) {
			return inversionSol;
		} else {
			// build rupture set only with analytical sections
			FaultSystemRupSet analyticalRupSet = rupSet.getForSectionSubSet(analyticalSects, rupProbCalc);
			// inversion configuration for it (required for target MFDs, and then to calc misfits)
			InversionConfiguration config = factory .buildInversionConfig(analyticalRupSet, branch, threads);
			FaultSystemSolution analyticalSol = analytical.run(analyticalRupSet, config);
			double[] analyticalRates = analyticalSol.getRateForAllRups();
			
			int origNumRups = rupSet.getNumRuptures();
			
			double[] allInitialRates = inversionSol.hasModule(InitialSolution.class) ?
					Arrays.copyOf(inversionSol.getModule(InitialSolution.class).get(), origNumRups) : new double[origNumRups];
			double[] allRates = Arrays.copyOf(inversionSol.getRateForAllRups(), origNumRups);
			
			RuptureSubSetMappings mappings = analyticalRupSet.requireModule(RuptureSubSetMappings.class);
			for (int subsetRupIndex=0; subsetRupIndex<analyticalRupSet.getNumRuptures(); subsetRupIndex++) {
				int origRupIndex = mappings.getOrigRupID(subsetRupIndex);
				allRates[origRupIndex] = analyticalRates[subsetRupIndex];
				allInitialRates[origRupIndex] = analyticalRates[subsetRupIndex];
			}
			
			InversionMisfits inversionMisfits = inversionSol.requireModule(InversionMisfits.class);
			InversionMisfits analyticalMisfits = analyticalSol.requireModule(InversionMisfits.class);
			FaultSystemSolution combinedSol = new FaultSystemSolution(rupSet, allRates);
			
			if (analyticalSol.hasModule(WaterLevelRates.class) || inversionSol.hasModule(WaterLevelRates.class)) {
				WaterLevelRates inversionWaterLevel = inversionSol.getModule(WaterLevelRates.class);
				WaterLevelRates analyticalWaterLevel = analyticalSol.getModule(WaterLevelRates.class);
				
				if (analyticalWaterLevel == null) {
					// simple case
					combinedSol.addModule(inversionWaterLevel);
				} else {
					// need to map
					double[] newWaterLevel = inversionWaterLevel == null ?
							new double[origNumRups] : Arrays.copyOf(inversionWaterLevel.get(), origNumRups);
					for (int subsetRupIndex=0; subsetRupIndex<analyticalRupSet.getNumRuptures(); subsetRupIndex++) {
						int origRupIndex = mappings.getOrigRupID(subsetRupIndex);
						newWaterLevel[origRupIndex] = analyticalWaterLevel.get(subsetRupIndex);
					}
					combinedSol.addModule(new WaterLevelRates(newWaterLevel));
				}
			}
			combinedSol.addModule(new InitialSolution(allInitialRates));
			InversionMisfits combMisfits = InversionMisfits.appendSeparate(List.of(inversionMisfits, analyticalMisfits));
			combinedSol.addModule(combMisfits);
			combinedSol.addModule(combMisfits.getMisfitStats());
			if (inversionSol.hasModule(ConnectivityClusterSolutionMisfits.class)) {
				ConnectivityClusterSolutionMisfits clusterMisfits = inversionSol.requireModule(ConnectivityClusterSolutionMisfits.class);
				Map<ConnectivityCluster, InversionMisfitStats> clusterMisfitsMap = new HashMap<>();
				InversionMisfitProgress largestProgress = clusterMisfits.getLargestClusterMisfitProgress();
				for (int i=0; i<clusters.size(); i++) {
					ConnectivityCluster cluster = clusters.get(i);
					InversionMisfitStats misfits = clusterMisfits.getMisfitStats(i);
					clusterMisfitsMap.put(cluster, misfits);
				}
				combinedSol.addModule(new ConnectivityClusterSolutionMisfits(combinedSol, clusterMisfitsMap, largestProgress));
			}

			// attach any relevant modules before writing out
			SolutionProcessor processor = factory.getSolutionLogicTreeProcessor();

			if (processor != null)
				processor.processSolution(combinedSol, branch);
			
			return combinedSol;
		}
	}

	@Override
	public FaultSystemSolution run(FaultSystemRupSet rupSet, InversionConfiguration config, String info) {
		// see if it's a single-fault rupture set
		if (!NSHM23_InvConfigFactory.hasJumps(rupSet))
			return analytical.run(rupSet, config, info);
		return super.run(rupSet, config, info);
	}
	
}