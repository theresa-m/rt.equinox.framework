/*******************************************************************************
 * Copyright (c) 2009 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.osgi.internal.composite;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.security.AccessControlContext;
import java.util.*;
import org.eclipse.osgi.framework.adaptor.BundleData;
import org.eclipse.osgi.framework.debug.Debug;
import org.eclipse.osgi.framework.internal.core.*;
import org.eclipse.osgi.framework.internal.core.Framework;
import org.eclipse.osgi.framework.util.Headers;
import org.eclipse.osgi.internal.loader.BundleLoaderProxy;
import org.eclipse.osgi.service.resolver.*;
import org.osgi.framework.*;
import org.osgi.framework.Constants;
import org.osgi.service.composite.CompositeBundle;
import org.osgi.service.composite.CompositeConstants;

public class CompositeImpl extends BundleHost implements CompositeBundle, SynchronousBundleListener {
	final CompositeSystemBundle compositeSystemBundle;
	final CompositeInfo compositeInfo;
	final StartLevelManager startLevelManager;
	final List<BundleDescription> constituents = new ArrayList<BundleDescription>(0);
	final boolean setCompositeParent;

	public CompositeImpl(BundleData bundledata, Framework framework, boolean setCompositeParent) throws BundleException {
		super(bundledata, framework);
		this.setCompositeParent = setCompositeParent;
		compositeSystemBundle = new CompositeSystemBundle((BundleHost) framework.getBundle(0), framework);
		compositeInfo = createCompositeInfo(setCompositeParent);
		startLevelManager = new StartLevelManager(framework, bundledata.getBundleID(), compositeSystemBundle);
	}

	CompositeInfo getCompositeInfo() {
		return compositeInfo;
	}

	private CompositeInfo createCompositeInfo(boolean setParent) throws BundleException {
		Dictionary manifest = bundledata.getManifest();
		String importPackage = (String) manifest.get(CompositeConstants.COMPOSITE_PACKAGE_IMPORT_POLICY);
		String exportPackage = (String) manifest.get(CompositeConstants.COMPOSITE_PACKAGE_EXPORT_POLICY);
		String requireBundle = (String) manifest.get(CompositeConstants.COMPOSITE_BUNDLE_REQUIRE_POLICY);
		String importService = (String) manifest.get(CompositeConstants.COMPOSITE_SERVICE_IMPORT_POLICY);
		String exportService = (String) manifest.get(CompositeConstants.COMPOSITE_SERVICE_EXPORT_POLICY);

		ImportPackageSpecification[] imports = null;
		ImportPackageSpecification[] exports = null;
		BundleSpecification[] requires = null;
		Filter importServiceFilter = null;
		Filter exportServiceFilter = null;
		BundleContext systemContext = compositeSystemBundle.getBundleContext();
		try {
			importServiceFilter = importService == null ? null : systemContext.createFilter(importService);
			exportServiceFilter = exportService == null ? null : systemContext.createFilter(exportService);
		} catch (InvalidSyntaxException e) {
			throw new BundleException("Invalid service sharing policy.", BundleException.MANIFEST_ERROR, e); //$NON-NLS-1$
		}

		StateObjectFactory factory = StateObjectFactory.defaultFactory;
		Headers<String, String> builderManifest = new Headers<String, String>(4);
		builderManifest.put(Constants.BUNDLE_MANIFESTVERSION, "2"); //$NON-NLS-1$
		builderManifest.put(Constants.BUNDLE_SYMBOLICNAME, (String) manifest.get(Constants.BUNDLE_SYMBOLICNAME));
		if (importPackage != null)
			builderManifest.put(Constants.IMPORT_PACKAGE, importPackage);
		if (requireBundle != null)
			builderManifest.put(Constants.REQUIRE_BUNDLE, requireBundle);
		BundleDescription desc = factory.createBundleDescription(null, builderManifest, "", 0); //$NON-NLS-1$
		if (importPackage != null)
			imports = desc.getImportPackages();
		if (requireBundle != null)
			requires = desc.getRequiredBundles();

		if (exportPackage != null) {
			builderManifest.put(Constants.IMPORT_PACKAGE, exportPackage);
			desc = factory.createBundleDescription(null, builderManifest, "", 0); //$NON-NLS-1$
			exports = desc.getImportPackages();
		}

		// set the parent info
		CompositeInfo parentInfo = null;
		if (setParent) {
			long compositeID = bundledata.getCompositeID();
			if (compositeID == 0) // this is the root framework
				parentInfo = framework.getCompositeSupport().compositPolicy.getRootCompositeInfo();
			else
				parentInfo = ((CompositeImpl) framework.getBundle(bundledata.getCompositeID())).getCompositeInfo();
		}
		CompositeInfo result = new CompositeInfo(parentInfo, imports, exports, requires, importServiceFilter, exportServiceFilter);
		if (setParent) {
			// add the the composite info as a child of the parent.
			parentInfo.addChild(result);
		}
		return result;
	}

	public BundleContext getSystemBundleContext() {
		if (getState() == Bundle.UNINSTALLED)
			return null;
		return compositeSystemBundle.getBundleContext();
	}

	public void update() throws BundleException {
		throw new BundleException("Must update a composite with the update(Map) method.", BundleException.UNSUPPORTED_OPERATION);
	}

	public void update(InputStream in) throws BundleException {
		update();
	}

	public void update(Map<String, String> compositeManifest) throws BundleException {
		if (Debug.DEBUG && Debug.DEBUG_GENERAL) {
			Debug.println("update location " + bundledata.getLocation()); //$NON-NLS-1$
			Debug.println("   from: " + compositeManifest); //$NON-NLS-1$
		}
		framework.checkAdminPermission(this, AdminPermission.LIFECYCLE);
		// make a local copy of the manifest first
		compositeManifest = new HashMap<String, String>(compositeManifest);
		// make sure the manifest is valid
		CompositeSupport.validateCompositeManifest(compositeManifest);
		try {
			URL configURL = bundledata.getEntry(CompositeSupport.COMPOSITE_CONFIGURATION);
			Properties configuration = new Properties();
			configuration.load(configURL.openStream());
			// get an in memory input stream to jar content of the composite we want to install
			InputStream content = CompositeSupport.getCompositeInput(configuration, compositeManifest);
			// update with the new content
			super.update(content);
		} catch (IOException e) {
			throw new BundleException("Error creating composite bundle", e); //$NON-NLS-1$
		}
	}

	@Override
	protected void updateWorkerPrivileged(URLConnection source, AccessControlContext callerContext) throws BundleException {
		super.updateWorkerPrivileged(source, callerContext);
		// update the composite info with the new data.
		CompositeInfo updatedInfo = createCompositeInfo(false);
		compositeInfo.update(updatedInfo);
	}

	protected void startHook() {
		startLevelManager.initialize();
		startLevelManager.doSetStartLevel(1);
	}

	protected void stopHook() {
		startLevelManager.shutdown();
		startLevelManager.cleanup();
	}

	public void uninstallWorkerPrivileged() throws BundleException {
		// uninstall the composite first to invalidate the context
		super.uninstallWorkerPrivileged();
		Bundle[] bundles = framework.getBundles(getBundleId());
		// uninstall all the constituents 
		for (int i = 0; i < bundles.length; i++)
			if (bundles[i].getBundleId() != 0) // not the system bundle
				try {
					bundles[i].uninstall();
				} catch (BundleException e) {
					framework.publishFrameworkEvent(FrameworkEvent.ERROR, bundles[i], e);
				}
	}

	protected void close() {
		super.close();
		// remove the composite info from the parent
		compositeInfo.getParent().removeChild(compositeInfo);
		compositeSystemBundle.close();
	}

	public class CompositeSystemBundle extends InternalSystemBundle {
		private final BundleHost rootSystemBundle;

		public CompositeSystemBundle(BundleHost systemBundle, Framework framework) throws BundleException {
			super(systemBundle.getBundleData(), framework);
			this.rootSystemBundle = systemBundle;
			this.state = Bundle.STARTING; // Initial state must be STARTING for composite system bundle
		}

		protected BundleContextImpl createContext() {
			CompositeContext compositeContext = new CompositeContext(this);
			if (setCompositeParent)
				compositeContext.addBundleListener(CompositeImpl.this);
			return compositeContext;
		}

		public ServiceReference[] getRegisteredServices() {
			// TODO this is not scoped; do we care?
			return rootSystemBundle.getRegisteredServices();
		}

		public ServiceReference[] getServicesInUse() {
			return rootSystemBundle.getServicesInUse();
		}

		public void start(int options) throws BundleException {
			throw new BundleException("Cannot start a composite system bundle.", BundleException.UNSUPPORTED_OPERATION); //$NON-NLS-1$
		}

		public void start() throws BundleException {
			start(0);
		}

		public void stop(int options) throws BundleException {
			throw new BundleException("Cannot stop a composite system bundle.", BundleException.UNSUPPORTED_OPERATION); //$NON-NLS-1$
		}

		public void stop() throws BundleException {
			stop(0);
		}

		public void uninstall() throws BundleException {
			throw new BundleException("Cannot uninstall a composite system bundle.", BundleException.UNSUPPORTED_OPERATION); //$NON-NLS-1$
		}

		public void update(InputStream input) throws BundleException {
			throw new BundleException("Cannot update a composite system bundle.", BundleException.UNSUPPORTED_OPERATION); //$NON-NLS-1$
		}

		public void update() throws BundleException {
			update(null);
		}

		public long getCompositeId() {
			return CompositeImpl.this.getBundleId();
		}

		public BundleLoaderProxy getLoaderProxy() {
			return rootSystemBundle.getLoaderProxy();
		}

		@Override
		public void close() {
			super.close();
		}

	}

	public class CompositeContext extends BundleContextImpl {

		protected CompositeContext(BundleHost bundle) {
			super(bundle);
		}

		protected long getCompositeId() {
			return CompositeImpl.this.getBundleId();
		}

		protected void start() {
			// nothing;
		}

		protected void stop() {
			// nothing
		}
	}

	public StartLevelManager getStartLevelService() {
		return startLevelManager;
	}

	public BundleHost getSystemBundle() {
		return compositeSystemBundle;
	}

	@Override
	protected void load() {
		super.load();
		loadConstituents();
	}

	@Override
	protected void refresh() {
		super.refresh();
		loadConstituents();
	}

	private void loadConstituents() {
		synchronized (constituents) {
			constituents.clear();
			AbstractBundle[] bundles = framework.getBundles(getBundleId());
			for (int i = 0; i < bundles.length; i++) {
				if (bundles[i].getBundleId() == 0)
					continue;
				BundleDescription constituent = bundles[i].getBundleDescription();
				if (constituent != null)
					constituents.add(constituent);
			}
		}
	}

	public void bundleChanged(BundleEvent event) {
		if (event.getType() != BundleEvent.INSTALLED)
			return;
		synchronized (constituents) {
			AbstractBundle bundle = (AbstractBundle) event.getBundle();
			BundleDescription desc = bundle.getBundleDescription();
			if (desc != null)
				constituents.add(desc);
		}
	}

	BundleDescription[] getConstituentDescriptions() {
		synchronized (constituents) {
			return constituents.toArray(new BundleDescription[constituents.size()]);
		}
	}
}