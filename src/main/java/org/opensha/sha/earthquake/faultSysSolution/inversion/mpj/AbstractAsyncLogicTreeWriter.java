package org.opensha.sha.earthquake.faultSysSolution.inversion.mpj;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.opensha.commons.logicTree.BranchWeightProvider;
import org.opensha.commons.logicTree.LogicTree;
import org.opensha.commons.logicTree.LogicTreeBranch;
import org.opensha.sha.earthquake.faultSysSolution.FaultSystemRupSet;
import org.opensha.sha.earthquake.faultSysSolution.FaultSystemSolution;
import org.opensha.sha.earthquake.faultSysSolution.modules.SolutionLogicTree;
import org.opensha.sha.earthquake.faultSysSolution.modules.SolutionLogicTree.SolutionProcessor;
import org.opensha.sha.earthquake.faultSysSolution.util.BranchAverageSolutionCreator;

import edu.usc.kmilner.mpj.taskDispatch.AsyncPostBatchHook;

public abstract class AbstractAsyncLogicTreeWriter extends AsyncPostBatchHook {

	private File outputDir;
	private SolutionProcessor processor;
	protected BranchWeightProvider weightProv;
	
	protected Map<String, BranchAverageSolutionCreator> baCreators;
	protected SolutionLogicTree.FileBuilder sltBuilder;
	
	private boolean[] dones;

	public AbstractAsyncLogicTreeWriter(File outputDir, SolutionProcessor processor, BranchWeightProvider weightProv) {
		super(1);
		this.outputDir = outputDir;
		this.processor = processor;
		this.weightProv = weightProv;
		this.baCreators = new HashMap<>();
		
		dones = new boolean[getNumTasks()];
	}
	
	public File getOutputFile(File resultsDir) {
		return new File(resultsDir.getParentFile(), resultsDir.getName()+".zip");
	}
	
	public File getBAOutputFile(File resultsDir, String baPrefix) {
		return baFile(resultsDir, baPrefix);
	}
	
	public abstract int getNumTasks();
	
	public abstract void debug(String message);
	
	public abstract LogicTreeBranch<?> getBranch(int calcIndex);
	
	public abstract FaultSystemSolution getSolution(LogicTreeBranch<?> branch, int calcIndex) throws IOException;
	
	public abstract void abortAndExit(Throwable t, int exitCode);

	@Override
	protected void batchProcessedAsync(int[] batch, int processIndex) {
		memoryDebug("AsyncLogicTree: beginning async call with batch size "
				+batch.length+" from "+processIndex+": "+getCountsString());
		
		synchronized (dones) {
			try {
				if (sltBuilder == null) {
					sltBuilder = new SolutionLogicTree.FileBuilder(processor, getOutputFile(outputDir));
					sltBuilder.setWeightProv(weightProv);
				}
				
				for (int index : batch) {
					dones[index] = true;
					
					doProcessIndex(index);
				}
			} catch (IOException ioe) {
				abortAndExit(ioe, 2);
			}
		}
		
		memoryDebug("AsyncLogicTree: exiting async process, stats: "+getCountsString());
	}

	protected void doProcessIndex(int index) throws IOException {
		LogicTreeBranch<?> branch = getBranch(index);
		
		debug("AsyncLogicTree: calcDone "+index+" = branch "+branch);
		
		FaultSystemSolution sol = getSolution(branch, index);
		if (sol == null)
			return;
		
		sltBuilder.solution(sol, branch);
		
		// now add in to branch averaged
		if (baCreators != null) {
			String baPrefix = getBA_prefix(branch);
			
			if (baPrefix == null) {
				debug("AsyncLogicTree won't branch average, all levels affect "+FaultSystemRupSet.RUP_PROPS_FILE_NAME);
			} else {
				if (!baCreators.containsKey(baPrefix))
					baCreators.put(baPrefix, new BranchAverageSolutionCreator(weightProv));
				BranchAverageSolutionCreator baCreator = baCreators.get(baPrefix);
				try {
					baCreator.addSolution(sol, branch);
				} catch (Exception e) {
					e.printStackTrace();
					System.err.flush();
					debug("AsyncLogicTree: Branch averaging failed for branch "+branch+", disabling averaging");
					baCreators = null;
				}
			}
		}
	}
	
	protected static String getBA_prefix(LogicTreeBranch<?> branch) {
		String baPrefix = null;
		boolean allAffect = true;
		for (int i=0; i<branch.size(); i++) {
			if (branch.getLevel(i).affects(FaultSystemRupSet.RUP_SECTS_FILE_NAME, true)) {
				if (baPrefix == null)
					baPrefix = "";
				else
					baPrefix += "_";
				baPrefix += branch.getValue(i).getFilePrefix();
			} else {
				allAffect = false;
			}
		}
		if (allAffect)
			return null;
		else if (baPrefix == null)
			return "";
		return baPrefix;
	}
	
	public static List<File> getBranchAverageSolutionFiles(File resultsDir, LogicTree<?> tree) {
		HashSet<String> commonPrefixes = new HashSet<>();
		
		for (LogicTreeBranch<?> branch : tree) {
			String prefix = getBA_prefix(branch);
			if (prefix != null)
				commonPrefixes.add(prefix);
		}
		
		List<File> ret = new ArrayList<>();
		for (String prefix : commonPrefixes)
			ret.add(baFile(resultsDir, prefix));
		
		return ret;
	}
	
	private static File baFile(File resultsDir, String baPrefix) {
		String prefix = resultsDir.getName();
		if (!baPrefix.isBlank())
			prefix += "_"+baPrefix;
		
		return new File(resultsDir.getParentFile(), prefix+"_branch_averaged.zip");
	}

	@Override
	public void shutdown() {
		super.shutdown();
		
		memoryDebug("AsyncLogicTree: finalizing logic tree zip");
		try {
			sltBuilder.build();
		} catch (IOException e) {
			memoryDebug("AsyncLogicTree: failed to build logic tree zip");
			e.printStackTrace();
		}
		
		if (baCreators != null && !baCreators.isEmpty()) {
			for (String baPrefix : baCreators.keySet()) {
				File baFile = getBAOutputFile(outputDir, baPrefix);
				memoryDebug("AsyncLogicTree: building "+baFile.getAbsolutePath());
				try {
					FaultSystemSolution baSol = baCreators.get(baPrefix).build();
					baSol.write(baFile);
				} catch (Exception e) {
					memoryDebug("AsyncLogicTree: failed to build BA for "+baFile.getAbsolutePath());
					e.printStackTrace();
					continue;
				}
			}
		}
	}
	
	private void memoryDebug(String info) {
		if (info == null || info.isBlank())
			info = "";
		else
			info += "; ";
	    
	    System.gc();
		Runtime rt = Runtime.getRuntime();
		long totalMB = rt.totalMemory() / 1024 / 1024;
		long freeMB = rt.freeMemory() / 1024 / 1024;
		long usedMB = totalMB - freeMB;
		debug(info+"mem t/u/f: "+totalMB+"/"+usedMB+"/"+freeMB);
	}

}
