package org.opensha.sha.simulators;

import java.util.ArrayList;
import java.util.List;

public class RSQSimEvent extends SimulatorEvent {
	
	private List<RSQSimEventRecord> records;
	private double nextEventTime = Double.NaN;

	public RSQSimEvent(List<RSQSimEventRecord> records) {
		super(records);
		
		this.records = records;
	}
	
	/**
	 * This returns a complete list of element time of first slips for this event 
	 * (it loops over all the event records).  The results are in the same
	 * order as returned by getAllElementIDs().
	 * @return
	 */
	public double[] getAllElementTimes() {
		ArrayList<double[]> timeList = new ArrayList<double[]>();
		int totSize = 0;
		for(int r=0; r<this.size();r++) {
			double[] times = get(r).getElementTimeFirstSlips();
			totSize += times.length;
			timeList.add(times);
		}
		double[] times = new double[totSize];
		int index = 0;
		for (int i=0; i<timeList.size(); i++) {
			double[] recTimes = timeList.get(i);
			System.arraycopy(recTimes, 0, times, index, recTimes.length);
			index += recTimes.length;
		}
		return times;
	}
	
	public void setNextEventTime(double time) {
		this.nextEventTime = time;
	}
	
	public double getNextEventTime() {
		return nextEventTime;
	}
	
	public RSQSimEvent cloneNewTime(double timeSeconds, int newID) {
		RSQSimEvent o = new RSQSimEvent(records);
		o.time = timeSeconds;
		o.event_id = newID;
		o.magnitude = magnitude;
		o.duration = duration;
		return o;
	}

}
