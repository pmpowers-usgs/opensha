package org.opensha.commons.calc.magScalingRelations.magScalingRelImpl;

/**
 * 
 * 
 * LOWER, UPPER on basis on magnitude.
 */

public enum Simplified2021FaultRegime {
	STRIKE_SLIP,
	REVERSE_FAULTING,
	NORMAL_FAULTING,
	SUBDUCTION_INTERFACE,
	CRUSTAL,
	LOWER,
	UPPER,
	NONE;
   
    public static Simplified2021FaultRegime fromRake(double rake) {
        if (Double.isNaN(rake)) {
            return NONE;
        } else if ((rake <= 45 && rake >= -45) || rake >= 135 || rake <= -135) {
            return STRIKE_SLIP;
        } else if (rake > 0) {
            return REVERSE_FAULTING;
        } else {
            return NORMAL_FAULTING;
        }
    }
    
    public static Simplified2021FaultRegime fromRegime(String regime) {
    	if (regime.compareToIgnoreCase("interface")==0){
    		return SUBDUCTION_INTERFACE;
    	} else {
    		return CRUSTAL;
    	}
    }
    
    public static Simplified2021FaultRegime fromEpistemicBound(String epistemicBound) {
    	if (epistemicBound.compareToIgnoreCase("lower")==0){
    		return LOWER;
    	} else if (epistemicBound.compareToIgnoreCase("upper")==0) {
    		return UPPER;
    	} else {
    		return NONE;
    	}
    }
}
