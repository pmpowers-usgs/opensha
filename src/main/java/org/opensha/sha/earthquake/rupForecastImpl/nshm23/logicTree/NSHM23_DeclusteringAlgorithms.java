package org.opensha.sha.earthquake.rupForecastImpl.nshm23.logicTree;

import java.io.IOException;
import java.io.InputStream;
import java.text.DecimalFormat;

import org.opensha.commons.data.CSVFile;
import org.opensha.commons.data.xyz.GriddedGeoDataSet;
import org.opensha.commons.geo.GriddedRegion;
import org.opensha.commons.geo.Location;
import org.opensha.commons.logicTree.Affects;
import org.opensha.commons.logicTree.DoesNotAffect;
import org.opensha.commons.logicTree.LogicTreeBranch;
import org.opensha.commons.logicTree.LogicTreeNode;
import org.opensha.sha.earthquake.faultSysSolution.FaultSystemRupSet;
import org.opensha.sha.earthquake.faultSysSolution.FaultSystemSolution;
import org.opensha.sha.earthquake.faultSysSolution.modules.GridSourceProvider;
import org.opensha.sha.earthquake.rupForecastImpl.nshm23.util.NSHM23_RegionLoader.SeismicityRegions;

import com.google.common.base.Preconditions;

@DoesNotAffect(FaultSystemRupSet.SECTS_FILE_NAME)
@DoesNotAffect(FaultSystemRupSet.RUP_SECTS_FILE_NAME)
@DoesNotAffect(FaultSystemRupSet.RUP_PROPS_FILE_NAME)
@DoesNotAffect(FaultSystemSolution.RATES_FILE_NAME)
@DoesNotAffect(GridSourceProvider.ARCHIVE_GRID_REGION_FILE_NAME)
@DoesNotAffect(GridSourceProvider.ARCHIVE_MECH_WEIGHT_FILE_NAME)
@Affects(GridSourceProvider.ARCHIVE_SUB_SEIS_FILE_NAME)
@Affects(GridSourceProvider.ARCHIVE_UNASSOCIATED_FILE_NAME)
public enum NSHM23_DeclusteringAlgorithms implements LogicTreeNode {
	
	GK("Gardner-Knopoff", "GK", 1d),
	NN("Nearest-Neighbor", "NN", 1d),
	REAS("Reasenberg", "Reas", 1d),
	AVERAGE("Average", "Average", 0d);
	
	private String name;
	private String shortName;
	private double weight;

	private NSHM23_DeclusteringAlgorithms(String name, String shortName, double weight) {
		this.name = name;
		this.shortName = shortName;
		this.weight = weight;
	}

	@Override
	public String getShortName() {
		return shortName;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public double getNodeWeight(LogicTreeBranch<?> fullBranch) {
		return weight;
	}

	@Override
	public String getFilePrefix() {
		return name();
	}

}