package org.eclipse.mieux.vim;

import java.util.IdentityHashMap;
import java.util.Map;

import org.eclipse.jface.text.ITextViewer;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;

/**
 * Attaches a {@link VimMode} to every text editor that gets opened, in every
 * workbench window, and tears it down again when the editor closes.
 */
public class VimEditorTracker implements IWindowListener, IPartListener2 {

	private static final VimEditorTracker INSTANCE = new VimEditorTracker();

	private final Map<IEditorPart, VimMode> attached = new IdentityHashMap<>();

	static VimEditorTracker getInstance() {
		return INSTANCE;
	}

	void hook(IWorkbenchWindow window) {
		if (window == null) {
			return;
		}
		window.getPartService().addPartListener(this);
		IWorkbenchPage page = window.getActivePage();
		if (page != null) {
			for (var ref : page.getEditorReferences()) {
				attachIfEditor(ref.getPart(false));
			}
		}
	}

	private void attachIfEditor(Object part) {
		if (!(part instanceof IEditorPart editor) || attached.containsKey(editor)) {
			return;
		}
		ITextViewer viewer = editor.getAdapter(ITextViewer.class);
		if (viewer == null) {
			return;
		}
		try {
			attached.put(editor, new VimMode(editor, viewer));
		} catch (RuntimeException e) {
			VimPlugin.log(e);
		}
	}

	private void detach(Object part) {
		if (part instanceof IEditorPart editor) {
			VimMode mode = attached.remove(editor);
			if (mode != null) {
				mode.dispose();
			}
		}
	}

	// IWindowListener

	@Override
	public void windowOpened(IWorkbenchWindow window) {
		hook(window);
	}

	@Override
	public void windowActivated(IWorkbenchWindow window) {
		// no-op
	}

	@Override
	public void windowDeactivated(IWorkbenchWindow window) {
		// no-op
	}

	@Override
	public void windowClosed(IWorkbenchWindow window) {
		// editors close individually and fire partClosed; nothing extra to do
	}

	// IPartListener2

	@Override
	public void partOpened(IWorkbenchPartReference partRef) {
		attachIfEditor(partRef.getPart(false));
	}

	@Override
	public void partVisible(IWorkbenchPartReference partRef) {
		attachIfEditor(partRef.getPart(false));
	}

	@Override
	public void partClosed(IWorkbenchPartReference partRef) {
		detach(partRef.getPart(false));
	}

	@Override
	public void partActivated(IWorkbenchPartReference partRef) {
		// no-op
	}

	@Override
	public void partBroughtToTop(IWorkbenchPartReference partRef) {
		// no-op
	}

	@Override
	public void partDeactivated(IWorkbenchPartReference partRef) {
		// no-op
	}

	@Override
	public void partHidden(IWorkbenchPartReference partRef) {
		// no-op
	}

	@Override
	public void partInputChanged(IWorkbenchPartReference partRef) {
		// no-op
	}
}
