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
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Path;
import org.eclipse.swt.graphics.Rectangle;

/**
 * Gives editor/view tab items rounded top corners.
 * <p>
 * The e4 CSS engine has no {@code border-radius}-style property, and the
 * colors/gradients {@link CTabRendering} paints tabs with are kept in
 * package-private fields (and painted by package-private
 * {@code drawSelectedTab}/{@code drawUnselectedTab} methods) we can't reach
 * from a different package. A clip-then-delegate approach doesn't work
 * either: {@code CTabRendering.draw()} calls
 * {@code gc.setClipping((Rectangle) null)} right before it paints a tab item
 * (and its private paint helpers reset clipping again internally), so any
 * clip we set before calling {@code super.draw(...)} is silently discarded
 * and the tab still comes out square.
 * </p>
 * <p>
 * Instead: let {@code super.draw(...)} paint the full square tab exactly as
 * upstream does, then, <em>after</em> it returns, cut the two top corners
 * back down to the tab folder's background color using an even-odd path
 * (the {@code r x r} corner square minus the rounding quarter-disk). Because
 * the disk itself is never touched, we don't need to know what color or
 * gradient {@code super.draw()} actually used inside it -- only what's
 * behind the tab strip outside it ({@link CTabFolder#getBackground()}),
 * which this theme keeps flat.
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

		super.draw(part, state, bounds, gc);
		eraseTopCorners(gc, bounds, CORNER_RADIUS);
	}

	private void eraseTopCorners(GC gc, Rectangle bounds, int radius) {
		int r = Math.min(radius, Math.min(bounds.width, bounds.height) / 2);
		if (r <= 0) {
			return;
		}

		int previousAntialias = gc.getAntialias();
		int previousFillRule = gc.getFillRule();
		Color previousBackground = gc.getBackground();
		try {
			gc.setAntialias(SWT.ON);
			gc.setFillRule(SWT.FILL_EVEN_ODD);
			gc.setBackground(parent.getBackground());

			Path topLeft = cornerEraseMask(gc, bounds.x, bounds.y, r, 180);
			Path topRight = cornerEraseMask(gc, bounds.x + bounds.width - r, bounds.y, r, 270);
			try {
				gc.fillPath(topLeft);
				gc.fillPath(topRight);
			} finally {
				topLeft.dispose();
				topRight.dispose();
			}
		} finally {
			gc.setBackground(previousBackground);
			gc.setFillRule(previousFillRule);
			gc.setAntialias(previousAntialias);
		}
	}

	/**
	 * Even-odd mask for one {@code r x r} corner square at {@code (sx, sy)}:
	 * the square itself, plus the rounding quarter-disk pie slice as a second
	 * sub-path. Under {@link SWT#FILL_EVEN_ODD}, the overlap between the two
	 * cancels out, leaving only the square-minus-disk "outer" sliver filled
	 * -- exactly the corner pixels that need to disappear.
	 * <p>
	 * {@code arcStartAngle} is 180 for a top-left corner (disk center at
	 * {@code (sx + r, sy + r)}, i.e. the square's inner/opposite corner) or
	 * 270 for a top-right corner (disk center at {@code (sx, sy + r)}) --
	 * matching the arc angles already verified against SWT's
	 * {@code Path.addArc} convention elsewhere in this renderer.
	 * </p>
	 */
	private static Path cornerEraseMask(GC gc, int sx, int sy, int r, int arcStartAngle) {
		Path path = new Path(gc.getDevice());
		path.addRectangle(sx, sy, r, r);

		int d = r * 2;
		if (arcStartAngle == 180) {
			// Top-left: disk centered at the square's bottom-right corner.
			path.moveTo(sx + r, sy + r);
			path.lineTo(sx, sy + r);
			path.addArc(sx, sy, d, d, 180, 90);
			path.lineTo(sx + r, sy + r);
		} else {
			// Top-right: disk centered at the square's bottom-left corner.
			path.moveTo(sx, sy + r);
			path.lineTo(sx, sy);
			path.addArc(sx - r, sy, d, d, 270, 90);
			path.lineTo(sx, sy + r);
		}
		path.close();
		return path;
	}
}
