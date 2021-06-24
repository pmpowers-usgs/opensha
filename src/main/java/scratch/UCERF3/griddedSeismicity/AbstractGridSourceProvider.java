package scratch.UCERF3.griddedSeismicity;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.opensha.commons.calc.magScalingRelations.magScalingRelImpl.WC1994_MagLengthRelationship;
import org.opensha.commons.data.CSVFile;
import org.opensha.commons.geo.GriddedRegion;
import org.opensha.commons.geo.Location;
import org.opensha.commons.geo.json.Feature;
import org.opensha.commons.geo.json.FeatureCollection;
import org.opensha.commons.util.modules.ArchivableModule;
import org.opensha.commons.util.modules.helpers.CSV_BackedModule;
import org.opensha.commons.util.modules.helpers.FileBackedModule;
import org.opensha.sha.earthquake.ProbEqkSource;
import org.opensha.sha.earthquake.param.BackgroundRupType;
import org.opensha.sha.earthquake.rupForecastImpl.PointSource13b;
import org.opensha.sha.earthquake.rupForecastImpl.WGCEP_UCERF_2_Final.griddedSeis.Point2Vert_FaultPoisSource;
import org.opensha.sha.magdist.IncrementalMagFreqDist;
import org.opensha.sha.magdist.SummedMagFreqDist;
import org.opensha.sha.util.FocalMech;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.Maps;

import scratch.UCERF3.utils.GardnerKnopoffAftershockFilter;

/**
 * Add comments here
 *
 * 
 * @author Peter Powers
 * @version $Id:$
 */
public abstract class AbstractGridSourceProvider implements GridSourceProvider, ArchivableModule {

	private final WC1994_MagLengthRelationship magLenRel = new WC1994_MagLengthRelationship();
	private double ptSrcCutoff = 6.0;
	
	public static double SOURCE_MIN_MAG_CUTOFF = 5.05;

	protected AbstractGridSourceProvider() {
	}
	
	@Override
	public String getName() {
		return "Grid Source Provider";
	}
	
	@Override
	public int size() {
		return getGriddedRegion().getNodeCount();
	}

	private static final double[] DEPTHS = new double[] {5.0, 1.0};
	
	@Override
	public ProbEqkSource getSource(int idx, double duration,
			boolean filterAftershocks, BackgroundRupType bgRupType) {
		Location loc = getGriddedRegion().locationForIndex(idx);
		IncrementalMagFreqDist mfd = getNodeMFD(idx, SOURCE_MIN_MAG_CUTOFF);
		if (filterAftershocks) scaleMFD(mfd);
		
		double fracStrikeSlip = getFracStrikeSlip(idx);
		double fracNormal = getFracNormal(idx);
		double fracReverse = getFracReverse(idx);

		switch (bgRupType) {
		case CROSSHAIR:
			return new Point2Vert_FaultPoisSource(loc, mfd, magLenRel, duration,
					ptSrcCutoff, fracStrikeSlip, fracNormal,
					fracReverse, true);
		case FINITE:
			return new Point2Vert_FaultPoisSource(loc, mfd, magLenRel, duration,
					ptSrcCutoff, fracStrikeSlip, fracNormal,
					fracReverse, false);
		case POINT:
			Map<FocalMech, Double> mechMap = Maps.newHashMap();
			mechMap.put(FocalMech.STRIKE_SLIP, fracStrikeSlip);
			mechMap.put(FocalMech.REVERSE, fracReverse);
			mechMap.put(FocalMech.NORMAL, fracNormal);
			return new PointSource13b(loc, mfd, duration, DEPTHS, mechMap);

		default:
			throw new IllegalStateException("Unknown Background Rup Type: "+bgRupType);
		}
		
	}
	
	
	
	
	public ProbEqkSource getSourceSubseismoOnly(int idx, double duration,
			boolean filterAftershocks, BackgroundRupType bgRupType) {
		Location loc = getGriddedRegion().locationForIndex(idx);
		IncrementalMagFreqDist origMFD = getNodeSubSeisMFD(idx);
		if(origMFD == null)
			return null;
		IncrementalMagFreqDist mfd = trimMFD(origMFD, SOURCE_MIN_MAG_CUTOFF);
		if (filterAftershocks) scaleMFD(mfd);
		
		double fracStrikeSlip = getFracStrikeSlip(idx);
		double fracNormal = getFracNormal(idx);
		double fracReverse = getFracReverse(idx);

		switch (bgRupType) {
		case CROSSHAIR:
			return new Point2Vert_FaultPoisSource(loc, mfd, magLenRel, duration,
					ptSrcCutoff, fracStrikeSlip, fracNormal,
					fracReverse, true);
		case FINITE:
			return new Point2Vert_FaultPoisSource(loc, mfd, magLenRel, duration,
					ptSrcCutoff, fracStrikeSlip, fracNormal,
					fracReverse, false);
		case POINT:
			Map<FocalMech, Double> mechMap = Maps.newHashMap();
			mechMap.put(FocalMech.STRIKE_SLIP, fracStrikeSlip);
			mechMap.put(FocalMech.REVERSE, fracReverse);
			mechMap.put(FocalMech.NORMAL, fracNormal);
			return new PointSource13b(loc, mfd, duration, DEPTHS, mechMap);

		default:
			throw new IllegalStateException("Unknown Background Rup Type: "+bgRupType);
		}
		
	}

	public ProbEqkSource getSourceTrulyOffOnly(int idx, double duration,
			boolean filterAftershocks, BackgroundRupType bgRupType) {
		Location loc = getGriddedRegion().locationForIndex(idx);
		IncrementalMagFreqDist origMFD = getNodeUnassociatedMFD(idx);
		if(origMFD == null)
			return null;
		IncrementalMagFreqDist mfd = trimMFD(origMFD, SOURCE_MIN_MAG_CUTOFF);
		if (filterAftershocks) scaleMFD(mfd);
		
		double fracStrikeSlip = getFracStrikeSlip(idx);
		double fracNormal = getFracNormal(idx);
		double fracReverse = getFracReverse(idx);

		switch (bgRupType) {
		case CROSSHAIR:
			return new Point2Vert_FaultPoisSource(loc, mfd, magLenRel, duration,
					ptSrcCutoff, fracStrikeSlip, fracNormal,
					fracReverse, true);
		case FINITE:
			return new Point2Vert_FaultPoisSource(loc, mfd, magLenRel, duration,
					ptSrcCutoff, fracStrikeSlip, fracNormal,
					fracReverse, false);
		case POINT:
			Map<FocalMech, Double> mechMap = Maps.newHashMap();
			mechMap.put(FocalMech.STRIKE_SLIP, fracStrikeSlip);
			mechMap.put(FocalMech.REVERSE, fracReverse);
			mechMap.put(FocalMech.NORMAL, fracNormal);
			return new PointSource13b(loc, mfd, duration, DEPTHS, mechMap);

		default:
			throw new IllegalStateException("Unknown Background Rup Type: "+bgRupType);
		}
		
	}

	
//	@Override
//	public void setAsPointSources(boolean usePoints) {
//		ptSrcCutoff = (usePoints) ? 10.0 : 6.0;
//	}


	@Override
	public IncrementalMagFreqDist getNodeMFD(int idx, double minMag) {
		return trimMFD(getNodeMFD(idx), minMag);
		
		// NOTE trimMFD clones the MFD returned by getNodeMFD so its safe for
		// subsequent modification; if this changes, then we need to review if
		// MFD is safe from alteration.
	}
	
	@Override
	public IncrementalMagFreqDist getNodeMFD(int idx) {
		
		IncrementalMagFreqDist nodeIndMFD = getNodeUnassociatedMFD(idx);
		IncrementalMagFreqDist nodeSubMFD = getNodeSubSeisMFD(idx);
		if (nodeIndMFD == null) return nodeSubMFD;
		if (nodeSubMFD == null) return nodeIndMFD;
		
		SummedMagFreqDist sumMFD = initSummedMFD(nodeIndMFD);
		sumMFD.addIncrementalMagFreqDist(nodeSubMFD);
		sumMFD.addIncrementalMagFreqDist(nodeIndMFD);
		return sumMFD;
	}
	
	private static SummedMagFreqDist initSummedMFD(IncrementalMagFreqDist model) {
		return new SummedMagFreqDist(model.getMinX(), model.getMaxX(),
			model.size());
	}


	/*
	 * Applies gardner Knopoff aftershock filter scaling to MFD in place.
	 */
	private static void scaleMFD(IncrementalMagFreqDist mfd) {
		double scale;
		for (int i=0; i<mfd.size(); i++) {
			scale = GardnerKnopoffAftershockFilter.scaleForMagnitude(mfd.getX(i));
			mfd.set(i, mfd.getY(i) * scale);
		}
	}

	/*
	 * Utility to trim the supplied MFD to the supplied min mag and the maximum
	 * non-zero mag. This method makes the assumtions that the min mag of the 
	 * supplied mfd is lower then the mMin, and that mag bins are centered on
	 * 0.05.
	 */
	private static IncrementalMagFreqDist trimMFD(IncrementalMagFreqDist mfdIn, double mMin) {
		if (mfdIn == null)
			return new IncrementalMagFreqDist(mMin,mMin,1);
		// in GR nofix branches there are mfds with all zero rates
		double mMax = mfdIn.getMaxMagWithNonZeroRate();
		if (Double.isNaN(mMax)) {
			IncrementalMagFreqDist mfdOut = new IncrementalMagFreqDist(mMin,mMin,1);
			return mfdOut;
		}
		double delta = mfdIn.getDelta();
		// if delta get's slightly off, the inner part of this cast can be something like 0.99999999, which
		// will mess up the num calculation. pad by 0.1 to be safe before casting
		int num = (int) ((mMax - mMin) / delta + 0.1) + 1;
//		IncrementalMagFreqDist mfdOut = new IncrementalMagFreqDist(mMin, mMax, num);
		IncrementalMagFreqDist mfdOut = new IncrementalMagFreqDist(mMin, num, delta);
		for (int i=0; i<mfdOut.size(); i++) {
			double mag = mfdOut.getX(i);
			double rate = mfdIn.getY(mag);
			mfdOut.set(mag, rate);
		}
//		if ((float)mfdOut.getMaxX() != (float)mMax) {
//			System.out.println("MFD IN");
//			System.out.println(mfdIn);
//			System.out.println("MFD OUT");
//			System.out.println(mfdOut);
//		}
		Preconditions.checkState((float)mfdOut.getMaxX() == (float)mMax,
				"Bad trim! mMin=%s, mMax=%s, delta=%s, num=%s, outputMMax=%s",
				mMin, mMax, delta, num, mfdOut.getMaxX());
		return mfdOut;
//		try {
//			double mMax = mfdIn.getMaxMagWithNonZeroRate();
//			double delta = mfdIn.getDelta();
//			int num = (int) ((mMax - mMin) / delta) + 1;
////			IncrementalMagFreqDist mfdOut = new IncrementalMagFreqDist(mMin, mMax, num);
//			IncrementalMagFreqDist mfdOut = new IncrementalMagFreqDist(mMin, num, delta);
//			for (int i=0; i<mfdOut.size(); i++) {
//				double mag = mfdOut.getX(i);
//				double rate = mfdIn.getY(mag);
//				mfdOut.set(mag, rate);
//			}
//			return mfdOut;
//		} catch (Exception e) {
////			e.printStackTrace();
////			System.out.println("empty MFD");
//			IncrementalMagFreqDist mfdOut = new IncrementalMagFreqDist(mMin,mMin,1);
////			mfdOut.scaleToCumRate(mMin, 0.0);
//			return mfdOut;
//		}
	}

	@Override
	public void writeToArchive(ZipOutputStream zout, String entryPrefix) throws IOException {
		new Precomputed(this).writeToArchive(zout, entryPrefix);
	}

	@Override
	public void initFromArchive(ZipFile zip, String entryPrefix) throws IOException {
		throw new IllegalStateException("This should not be called (loaded as Precomputed instance)");
	}

	@Override
	public Class<? extends ArchivableModule> getLoadingClass() {
		return Precomputed.class;
	}

	private static class Precomputed extends AbstractGridSourceProvider implements ArchivableModule {
		
		private GriddedRegion region;
		private ImmutableMap<Integer, IncrementalMagFreqDist> nodeSubSeisMFDs;
		private ImmutableMap<Integer, IncrementalMagFreqDist> nodeUnassociatedMFDs;
		private double[] fracStrikeSlip;
		private double[] fracNormal;
		private double[] fracReverse;
		
		@SuppressWarnings("unused")
		private Precomputed() {
			// for serialization
		}

		private Precomputed(GridSourceProvider prov) {
			this.region = prov.getGriddedRegion();
			int nodeCount = region.getNodeCount();
			Builder<Integer, IncrementalMagFreqDist> subSeisBuilder = ImmutableMap.builder();
			Builder<Integer, IncrementalMagFreqDist> unassociatedBuilder = ImmutableMap.builder();
			fracStrikeSlip = new double[nodeCount];
			fracNormal = new double[nodeCount];
			fracReverse = new double[nodeCount];
			for (int i=0; i<nodeCount; i++) {
				IncrementalMagFreqDist subSeis = prov.getNodeSubSeisMFD(i);
				if (subSeis != null)
					subSeisBuilder.put(i, subSeis);
				IncrementalMagFreqDist unassociated = prov.getNodeUnassociatedMFD(i);
				if (unassociated != null)
					unassociatedBuilder.put(i, unassociated);
			}
			this.nodeSubSeisMFDs = subSeisBuilder.build();
			this.nodeUnassociatedMFDs = unassociatedBuilder.build();
		}

		@Override
		public final IncrementalMagFreqDist getNodeUnassociatedMFD(int idx) {
			return nodeUnassociatedMFDs.get(idx);
		}

		@Override
		public final IncrementalMagFreqDist getNodeSubSeisMFD(int idx) {
			return nodeSubSeisMFDs.get(idx);
		}

		@Override
		public final GriddedRegion getGriddedRegion() {
			return region;
		}

		@Override
		public final double getFracStrikeSlip(int idx) {
			return fracStrikeSlip[idx];
		}

		@Override
		public final double getFracReverse(int idx) {
			return fracReverse[idx];
		}

		@Override
		public final double getFracNormal(int idx) {
			return fracNormal[idx];
		}
		
		private CSVFile<String> buildCSV(Map<Integer, IncrementalMagFreqDist> mfds) {
			IncrementalMagFreqDist xVals = null;
			
			for (IncrementalMagFreqDist mfd : mfds.values()) {
				if (mfd != null) {
					xVals = mfd;
					break;
				}
			}
			
			if (xVals == null)
				// no actual MFDs
				return null;
			CSVFile<String> csv = new CSVFile<>(true);
			List<String> header = new ArrayList<>();
			header.add("Node Index");
			header.add("Latitude");
			header.add("Longitude");
			for (int i=0; i<xVals.size(); i++)
				header.add(xVals.getX(i)+"");
			csv.addLine(header);
			
			final String empty = "";
			int nodeCount = region.getNodeCount();
			for (int i=0; i<nodeCount; i++) {
				IncrementalMagFreqDist mfd = mfds.get(i);
				Location loc = region.getLocation(i);
				List<String> line = new ArrayList<>(header.size());
				line.add(i+"");
				line.add(loc.getLatitude()+"");
				line.add(loc.getLongitude()+"");
				if (mfd == null) {
					while (line.size() < header.size())
						line.add(empty);
				} else {
					Preconditions.checkState(mfd.size() == xVals.size(),
							"MFD sizes inconsistent. Expected %s values, have %s", xVals.size(), mfd.size());
					for (int j=0; j<xVals.size(); j++) {
						Preconditions.checkState((float)mfd.getX(j) == (float)xVals.getX(j),
								"MFD x value mismatch for node %s value %s", i, j);
						line.add(mfd.getY(j)+"");
					}
				}
				csv.addLine(line);
			}
			return csv;
		}
		
		private CSVFile<String> buildWeightsCSV() {
			CSVFile<String> csv = new CSVFile<>(true);
			List<String> header = new ArrayList<>();
			header.add("Node Index");
			header.add("Latitude");
			header.add("Longitude");
			header.add("Fraction Strike-Slip");
			header.add("Fraction Reverse");
			header.add("Fraction Normal");
			csv.addLine(header);
			
			GriddedRegion region = getGriddedRegion();
			
			for (int i=0; i<region.getNodeCount(); i++) {
				Location loc = region.getLocation(i);
				List<String> line = new ArrayList<>(header.size());
				line.add(i+"");
				line.add(loc.getLatitude()+"");
				line.add(loc.getLongitude()+"");
				line.add(getFracStrikeSlip(i)+"");
				line.add(getFracReverse(i)+"");
				line.add(getFracNormal(i)+"");
				csv.addLine(line);
			}
			return csv;
		}

		@Override
		public void writeToArchive(ZipOutputStream zout, String entryPrefix) throws IOException {
			CSVFile<String> subSeisCSV = buildCSV(nodeSubSeisMFDs);
			CSVFile<String> unassociatedCSV = buildCSV(nodeUnassociatedMFDs);
			
			FileBackedModule.initEntry(zout, entryPrefix, "grid_region.geojson");
			Feature regFeature = region.toFeature();
			OutputStreamWriter writer = new OutputStreamWriter(zout);
			Feature.write(regFeature, writer);
			writer.flush();
			zout.flush();
			zout.closeEntry();
			
			if (subSeisCSV != null)
				CSV_BackedModule.writeToArchive(subSeisCSV, zout, entryPrefix, "grid_sub_seis_mfds.csv");
			if (unassociatedCSV != null)
				CSV_BackedModule.writeToArchive(unassociatedCSV, zout, entryPrefix, "grid_unassociated_mfds.csv");
			CSV_BackedModule.writeToArchive(buildWeightsCSV(), zout, entryPrefix, "grid_mech_weights.csv");
		}

		@Override
		public void initFromArchive(ZipFile zip, String entryPrefix) throws IOException {
			BufferedInputStream regionIS = FileBackedModule.getInputStream(zip, entryPrefix, "grid_region.geojson");
			InputStreamReader regionReader = new InputStreamReader(regionIS);
			Feature regFeature = Feature.read(regionReader);
			region = GriddedRegion.fromFeature(regFeature);
			
			Map<Integer, IncrementalMagFreqDist> nodeSubSeisMFDs = loadCSV(
					region, zip, entryPrefix, "grid_sub_seis_mfds.csv");
			Map<Integer, IncrementalMagFreqDist> nodeUnassociatedMFDs = loadCSV(
					region, zip, entryPrefix, "grid_unassociated_mfds.csv");
			if (nodeSubSeisMFDs == null)
				this.nodeSubSeisMFDs = ImmutableMap.of();
			else
				this.nodeSubSeisMFDs = ImmutableMap.copyOf(nodeSubSeisMFDs);
			if (nodeUnassociatedMFDs == null)
				this.nodeUnassociatedMFDs = ImmutableMap.of();
			else
				this.nodeUnassociatedMFDs = ImmutableMap.copyOf(nodeUnassociatedMFDs);
			
			// load mechanisms
			CSVFile<String> mechCSV = CSV_BackedModule.loadFromArchive(zip, entryPrefix, "grid_mech_weights.csv");
			Preconditions.checkState(mechCSV.getNumRows() == region.getNodeCount()+1,
					"Mechanism node count mismatch, expected %s, have %s", region.getNodeCount(), mechCSV.getNumRows()-1);
			fracStrikeSlip = new double[region.getNodeCount()];
			fracReverse = new double[region.getNodeCount()];
			fracNormal = new double[region.getNodeCount()];
			for (int i=0; i<region.getNodeCount(); i++) {
				int row = i+1;
				int index = mechCSV.getInt(row, 0);
				Preconditions.checkState(index == i, "Mechanism row indexes must be in order and 0-based");
				double lat = mechCSV.getDouble(row, 1);
				double lon = mechCSV.getDouble(row, 2);
				Location loc = region.getLocation(index);
				Preconditions.checkState((float)lat == (float)loc.getLatitude(), "Latitude mismatch at index %s: %s != %s",
						index, lat, loc.getLatitude());
				Preconditions.checkState((float)lon == (float)loc.getLongitude(), "Longitude mismatch at index %s: %s != %s",
						index, lon, loc.getLongitude());
				fracStrikeSlip[i] = mechCSV.getDouble(row, 3);
				fracReverse[i] = mechCSV.getDouble(row, 4);
				fracNormal[i] = mechCSV.getDouble(row, 5);
			}
		}
		
		private static Map<Integer, IncrementalMagFreqDist> loadCSV(GriddedRegion region,
				ZipFile zip, String entryPrefix, String fileName) throws IOException {
			String entryName = ArchivableModule.getEntryName(entryPrefix, fileName);
			Preconditions.checkNotNull(entryName, "entryName is null. prefix='%s', fileName='%s'", entryPrefix, fileName);
			ZipEntry entry = zip.getEntry(entryName);
			if (entry == null)
				return null;
			
			CSVFile<String> csv = CSVFile.readStream(new BufferedInputStream(zip.getInputStream(entry)), true);
			Map<Integer, IncrementalMagFreqDist> mfds = new HashMap<>();
			double minX = csv.getDouble(0, 3);
			double maxX = csv.getDouble(0, csv.getNumCols()-1);
			int numX = csv.getNumCols()-3;
			for (int row=1; row<csv.getNumRows(); row++) {
				int index = csv.getInt(row, 0);
				Preconditions.checkState(index >= 0 && index <= region.getNodeCount(),
						"Bad grid node index: %s (max=%s)", index, region.getNodeCount());
				double lat = csv.getDouble(row, 1);
				double lon = csv.getDouble(row, 2);
				Location loc = region.getLocation(index);
				Preconditions.checkState((float)lat == (float)loc.getLatitude(), "Latitude mismatch at index %s: %s != %s",
						index, lat, loc.getLatitude());
				Preconditions.checkState((float)lon == (float)loc.getLongitude(), "Longitude mismatch at index %s: %s != %s",
						index, lon, loc.getLongitude());
				if (csv.get(row, 3).isBlank())
					continue;
				IncrementalMagFreqDist mfd = new IncrementalMagFreqDist(minX, maxX, numX);
				for (int i=0; i<numX; i++)
					mfd.set(i, csv.getInt(row, 3+i));
				mfds.put(index, mfd);
			}
			return mfds;
		}
		
	}

}
