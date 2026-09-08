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

import org.eclipse.e4.ui.workbench.renderers.swt.CTabRendering;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Path;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.graphics.Region;

/**
 * Gives editor/view tab items rounded top corners.
 * <p>
 * The e4 CSS engine has no {@code border-radius}-style property, and the
 * colors/gradients {@link CTabRendering} paints with are kept in
 * package-private fields we can't reach from here. Rather than reimplement
 * its tab-painting logic (text, icon, close button, dirty-indicator overlay,
 * hot/inactive alpha blending, ...), this clips the paint area to a
 * rounded-top path before delegating to {@code super.draw(...)} for the
 * individual tab items -- everything super paints outside that path is
 * simply cut away, leaving rounded corners with zero risk of drifting out of
 * sync with upstream's tab content rendering.
 * </p>
 */
public class RoundedTabRenderer extends CTabRendering {

	private static final int CORNER_RADIUS = 6;

	public RoundedTabRenderer(CTabFolder parent) {
		super(parent);
	}

	@Override
	protected void draw(int part, int state, Rectangle bounds, GC gc) {
		boolean isTabItem = part >= 0 && part < parent.getItemCount();
		if (!isTabItem || bounds.width <= 0 || bounds.height <= 0) {
			super.draw(part, state, bounds, gc);
			return;
		}

		gc.setAdvanced(true);
		gc.setAntialias(SWT.ON);

		Path clip = createTopRoundedPath(gc, bounds, CORNER_RADIUS);
		Region previousClipping = new Region(gc.getDevice());
		gc.getClipping(previousClipping);
		try {
			gc.setClipping(clip);
			super.draw(part, state, bounds, gc);
		} finally {
			gc.setClipping(previousClipping);
			previousClipping.dispose();
			clip.dispose();
		}
	}

	/** Rounded top-left/top-right corners, straight bottom edge (the tab merges into the content area below it). */
	private static Path createTopRoundedPath(GC gc, Rectangle bounds, int radius) {
		int r = Math.min(radius, Math.min(bounds.width, bounds.height) / 2);
		int x = bounds.x;
		int y = bounds.y;
		int width = bounds.width;
		int height = bounds.height;
		int d = r * 2;

		Path path = new Path(gc.getDevice());
		path.moveTo(x, y + height);
		path.lineTo(x, y + r);
		path.addArc(x, y, d, d, 180, 90);
		path.lineTo(x + width - r, y);
		path.addArc(x + width - d, y, d, d, 90, 90);
		path.lineTo(x + width, y + height);
		path.close();
		return path;
	}
}
