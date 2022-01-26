package org.opensha.commons.data.siteData.servlet.impl;

import java.io.File;
import java.io.IOException;

import org.opensha.commons.data.siteData.SiteData;
import org.opensha.commons.data.siteData.impl.CVM4i26BasinDepth;
import org.opensha.commons.data.siteData.impl.CVM_CCAi6BasinDepth;
import org.opensha.commons.data.siteData.servlet.AbstractSiteDataServlet;
import org.opensha.commons.util.ServerPrefUtils;

public class CVM_CCAi6BasinDepthTo1_0_Servlet extends
		AbstractSiteDataServlet<Double> {
	
	private static final File FILE = new File(ServerPrefUtils.SERVER_PREFS.getTomcatProjectDir(),
			CVM_CCAi6BasinDepth.DEPTH_1_0_FILE);
	
	public CVM_CCAi6BasinDepthTo1_0_Servlet() throws IOException {
		super(new CVM_CCAi6BasinDepth(SiteData.TYPE_DEPTH_TO_1_0, FILE, false));
	}
}
