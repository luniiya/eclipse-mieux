/*******************************************************************************
 * Copyright (c) 2026 eclipse-mieux contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which accompanies this
 * distribution, and is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.mieux.theme;

import java.awt.GraphicsEnvironment;

import org.eclipse.ui.IStartup;

/**
 * Warms up AWT's X11 graphics pipeline on a background thread at workbench
 * startup, instead of letting it initialize lazily on the UI thread.
 * <p>
 * SWT's SVG image support ({@code org.eclipse.swt.internal.image.SVGFileFormat}
 * / {@code org.eclipse.swt.svg.JSVGRasterizer}) rasterizes SVG icons (used for
 * some tab/toolbar icons) via {@code java.awt.image.BufferedImage.createGraphics()}
 * under the hood, which on Linux/GTK triggers a one-time, synchronous
 * {@code sun.awt.X11GraphicsEnvironment} class-load and native init the very
 * first time it runs. Observed live: a real 0.6s+ UI freeze
 * ({@code org.eclipse.ui.monitoring}) with the stack rooted in
 * {@code RoundedTabRenderer.draw} -&gt; {@code CTabFolderRenderer.drawUnselected}
 * -&gt; {@code GC.drawImage} -&gt; ... -&gt; {@code X11GraphicsEnvironment.<clinit>}
 * -&gt; {@code Class.forName} -- the very first tab repaint that happens to draw
 * an SVG icon pays this whole cold-start cost on the UI thread, which reads
 * as the workbench freezing/hanging right after launch.
 * <p>
 * Triggering the same class-load chain once, eagerly, on a plain background
 * thread before any real paint traffic starts, moves that one-time cost off
 * the UI thread entirely -- by the time a real paint event needs it, the
 * class is already loaded and the native environment already initialized.
 */
public class AwtWarmupStartup implements IStartup {

	@Override
	public void earlyStartup() {
		Thread warmup = new Thread(() -> {
			try {
				// Forces the same sun.awt.X11GraphicsEnvironment / SurfaceData /
				// RenderingEngine class-load chain that SVG icon rasterization
				// would otherwise trigger lazily on the UI thread.
				GraphicsEnvironment.getLocalGraphicsEnvironment();
			} catch (Throwable t) {
				// Best-effort warmup only -- if this fails (headless environment,
				// missing X11, etc.) the lazy path on the UI thread still runs as
				// a fallback, just without the benefit of this pre-warm. Nothing
				// to log here that the platform log doesn't already capture if the
				// lazy path fails too.
			}
		}, "mieux-theme-awt-warmup");
		warmup.setDaemon(true);
		warmup.start();
	}

}
