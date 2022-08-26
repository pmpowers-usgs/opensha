package org.opensha.sha.earthquake.rupForecastImpl.nshm23.gridded;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.opensha.commons.calc.magScalingRelations.magScalingRelImpl.WC1994_MagLengthRelationship;
import org.opensha.commons.data.CSVFile;
import org.opensha.commons.geo.GriddedRegion;
import org.opensha.commons.geo.Location;
import org.opensha.commons.util.modules.ArchivableModule;
import org.opensha.sha.earthquake.ProbEqkSource;
import org.opensha.sha.earthquake.faultSysSolution.modules.GridSourceProvider;
import org.opensha.sha.earthquake.faultSysSolution.modules.GridSourceProvider.Abstract;
import org.opensha.sha.earthquake.param.BackgroundRupType;
import org.opensha.sha.earthquake.rupForecastImpl.PointSource13b;
import org.opensha.sha.earthquake.rupForecastImpl.WGCEP_UCERF_2_Final.griddedSeis.Point2Vert_FaultPoisSource;
import org.opensha.sha.magdist.IncrementalMagFreqDist;
import org.opensha.sha.util.FocalMech;

/**
 * Abstract base class for an NSHM23 {@link GridSourceProvider}. This class handles serialization, averaging, and building
 * a source from an input MFD.
 * 
 * @author kevin
 * @see NSHM23_SingleRegionGridSourceProvider
 * @see Precomputed
 *
 */
public abstract class NSHM23_AbstractGridSourceProvider extends Abstract implements ArchivableModule {

	// TODO these are all from UCERF3 and may be changed
	public static final double DEFAULT_SOURCE_MIN_MAG_CUTOFF = 5.05;
	private static final double[] DEPTHS = new double[] {5.0, 1.0};
	private static final WC1994_MagLengthRelationship magLenRel = new WC1994_MagLengthRelationship();
	private static final double ptSrcCutoff = 6.0;
	
	public NSHM23_AbstractGridSourceProvider() {
		super(DEFAULT_SOURCE_MIN_MAG_CUTOFF);
	}

	@Override
	public String getName() {
		return "NSHM23 Grid Source Provider";
	}

	@Override
	public AveragingAccumulator<Abstract> averagingAccumulator() {
		return new AveragerImpl();
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

	@Override
	public void applyAftershockFilter(IncrementalMagFreqDist mfd) {
		doApplyAftershockFilter(mfd);
	}

	@Override
	protected ProbEqkSource buildSource(int gridIndex, IncrementalMagFreqDist mfd, double duration,
			BackgroundRupType bgRupType) {
		Location loc = getGriddedRegion().locationForIndex(gridIndex);
		
		double fracStrikeSlip = getFracStrikeSlip(gridIndex);
		double fracNormal = getFracNormal(gridIndex);
		double fracReverse = getFracReverse(gridIndex);

		return buildSource(mfd, duration, bgRupType, loc, fracStrikeSlip, fracNormal, fracReverse);
	}

	/**
	 * Builds NSHM23 source. TODO: currently uses UCERF3 implementations
	 * 
	 * @param mfd
	 * @param duration
	 * @param bgRupType
	 * @param loc
	 * @param fracStrikeSlip
	 * @param fracNormal
	 * @param fracReverse
	 * @return
	 */
	public static ProbEqkSource buildSource(IncrementalMagFreqDist mfd, double duration, BackgroundRupType bgRupType,
			Location loc, double fracStrikeSlip, double fracNormal, double fracReverse) {
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
			Map<FocalMech, Double> mechMap = new EnumMap<>(FocalMech.class);
			mechMap.put(FocalMech.STRIKE_SLIP, fracStrikeSlip);
			mechMap.put(FocalMech.REVERSE, fracReverse);
			mechMap.put(FocalMech.NORMAL, fracNormal);
			// TODO still the best implementation?
			return new PointSource13b(loc, mfd, duration, DEPTHS, mechMap);

		default:
			throw new IllegalStateException("Unknown Background Rup Type: "+bgRupType);
		}
	}
	
	private static void doApplyAftershockFilter(IncrementalMagFreqDist mfd) {
		// TODO do we apply G-K? do we throw an exception? do nothing?
	}
	
	private static class AveragerImpl extends GridSourceProvider.Averager<Abstract> {

		@Override
		public Class<Abstract> getType() {
			return Abstract.class;
		}

		@Override
		protected Abstract buildAverage(Abstract refGridProv, Map<Integer, IncrementalMagFreqDist> nodeSubSeisMFDs,
				Map<Integer, IncrementalMagFreqDist> nodeUnassociatedMFDs, double[] fracStrikeSlip, double[] fracNormal,
				double[] fracReverse) {
			return new Precomputed(refGridProv.getGriddedRegion(), nodeSubSeisMFDs, nodeUnassociatedMFDs,
					fracStrikeSlip, fracNormal, fracReverse);
		}
		
	}
	
	public static class Precomputed extends AbstractPrecomputed {
		
		private Precomputed() {
			super(DEFAULT_SOURCE_MIN_MAG_CUTOFF);
			// for deserialization
		}

		public Precomputed(GriddedRegion region, CSVFile<String> subSeisCSV, CSVFile<String> unassociatedCSV,
				CSVFile<String> mechCSV) {
			super(region, subSeisCSV, unassociatedCSV, mechCSV,
					DEFAULT_SOURCE_MIN_MAG_CUTOFF);
		}

		public Precomputed(GriddedRegion region, Map<Integer, IncrementalMagFreqDist> nodeSubSeisMFDs,
				Map<Integer, IncrementalMagFreqDist> nodeUnassociatedMFDs, double[] fracStrikeSlip, double[] fracNormal,
				double[] fracReverse) {
			super(region, nodeSubSeisMFDs, nodeUnassociatedMFDs, fracStrikeSlip, fracNormal, fracReverse,
					DEFAULT_SOURCE_MIN_MAG_CUTOFF);
		}

		public Precomputed(GridSourceProvider prov) {
			super(prov, DEFAULT_SOURCE_MIN_MAG_CUTOFF);
		}

		@Override
		public String getName() {
			return "Precomputed NSHM23 Grid Source Provider";
		}

		@Override
		public AveragingAccumulator<Abstract> averagingAccumulator() {
			return new AveragerImpl();
		}

		@Override
		public void applyAftershockFilter(IncrementalMagFreqDist mfd) {
			doApplyAftershockFilter(mfd);
		}

		@Override
		protected ProbEqkSource buildSource(int gridIndex, IncrementalMagFreqDist mfd, double duration,
				BackgroundRupType bgRupType) {
			Location loc = getGriddedRegion().locationForIndex(gridIndex);
			
			double fracStrikeSlip = getFracStrikeSlip(gridIndex);
			double fracNormal = getFracNormal(gridIndex);
			double fracReverse = getFracReverse(gridIndex);

			return NSHM23_AbstractGridSourceProvider.buildSource(mfd, duration, bgRupType, loc, fracStrikeSlip, fracNormal, fracReverse);
		}
		
	}

}