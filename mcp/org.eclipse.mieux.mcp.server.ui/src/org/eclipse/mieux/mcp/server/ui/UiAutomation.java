package org.eclipse.mieux.mcp.server.ui;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.mieux.mcp.server.json.Json;
import org.eclipse.mieux.mcp.server.registry.ToolExecutionException;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Scale;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Slider;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.swt.widgets.Widget;

/** SWT-thread-confined snapshot and action implementation. */
final class UiAutomation {

	private final Display display = Display.getDefault();
	private final Map<Long, Widget> objects = new LinkedHashMap<>();
	private final IdentityHashMap<Widget, Long> ids = new IdentityHashMap<>();
	private long nextId;

	Object call(UiCall operation) throws ToolExecutionException {
		if (display == null || display.isDisposed()) {
			throw new ToolExecutionException("SWT display is not available");
		}
		AtomicReference<Object> result = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Runnable runnable = () -> {
			try {
				result.set(operation.run());
			} catch (Throwable t) {
				failure.set(t);
			}
		};
		if (Display.getCurrent() == display) {
			runnable.run();
		} else {
			display.syncExec(runnable);
		}
		Throwable error = failure.get();
		if (error != null) {
			if (error instanceof ToolExecutionException toolError) {
				throw toolError;
			}
			throw new ToolExecutionException("UI operation failed: " + message(error));
		}
		return result.get();
	}

	private static String message(Throwable error) {
		return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
	}

	Object listWindows() {
		beginSnapshot();
		List<Object> windows = new ArrayList<>();
		for (Shell shell : display.getShells()) {
			if (shell.isDisposed()) {
				continue;
			}
			long id = register(shell);
			windows.add(Json.object("id", id, "role", "window", "label", shell.getText(), "visible", shell.isVisible(),
					"active", display.getActiveShell() == shell));
		}
		return Json.object("windows", windows);
	}

	Object snapshot(Map<String, Object> arguments) throws ToolExecutionException {
		long oldId = requiredId(arguments, "shell_id");
		Widget old = objects.get(oldId);
		if (!(old instanceof Shell shell) || shell.isDisposed()) {
			throw new ToolExecutionException("Unknown or stale shell_id: " + oldId);
		}
		int maxDepth = integer(arguments.get("maxDepth"), 8);
		if (maxDepth < 0 || maxDepth > 30) {
			throw new ToolExecutionException("maxDepth must be between 0 and 30");
		}
		beginSnapshot();
		long shellId = register(shell);
		return Json.object("snapshot", nodeForShell(shell, shellId, maxDepth, 0));
	}

	Widget requireObject(Map<String, Object> arguments) throws ToolExecutionException {
		long id = requiredId(arguments, "id");
		Widget widget = objects.get(id);
		if (widget == null || widget.isDisposed()) {
			throw new ToolExecutionException("Unknown or stale widget id: " + id);
		}
		return widget;
	}

	private long requiredId(Map<String, Object> arguments, String name) throws ToolExecutionException {
		Object value = arguments.get(name);
		if (!(value instanceof Number number)) {
			throw new ToolExecutionException('"' + name + " must be a number");
		}
		return number.longValue();
	}

	private int integer(Object value, int defaultValue) throws ToolExecutionException {
		if (value == null) {
			return defaultValue;
		}
		if (!(value instanceof Number number)) {
			throw new ToolExecutionException("Expected a number");
		}
		return number.intValue();
	}

	private void beginSnapshot() {
		objects.clear();
		ids.clear();
		nextId = 1;
	}

	private long register(Widget widget) {
		Long existing = ids.get(widget);
		if (existing != null) {
			return existing.longValue();
		}
		long id = nextId++;
		ids.put(widget, id);
		objects.put(id, widget);
		return id;
	}

	private Map<String, Object> nodeForShell(Shell shell, long id, int maxDepth, int depth) {
		Map<String, Object> node = node(id, "window", shell.getText(), shell.isEnabled(), shell.isVisible());
		if (depth < maxDepth) {
			addControls(node, shell, maxDepth, depth + 1);
		}
		Menu menu = shell.getMenuBar();
		if (menu != null && !menu.isDisposed()) {
			node.put("menu", nodeForMenu(menu, maxDepth, depth + 1));
		}
		return node;
	}

	private void addControls(Map<String, Object> parent, Composite composite, int maxDepth, int depth) {
		List<Object> children = new ArrayList<>();
		for (Control control : composite.getChildren()) {
			if (control.isDisposed()) {
				continue;
			}
			long id = register(control);
			Map<String, Object> child = node(id, role(control), label(control), control.isEnabled(), control.isVisible());
			addState(child, control);
			if (depth < maxDepth) {
				if (control instanceof Tree tree) {
					addTreeItems(child, tree.getItems(), maxDepth, depth + 1);
				} else if (control instanceof Table table) {
					addTableItems(child, table.getItems());
				} else if (control instanceof ToolBar toolBar) {
					addToolItems(child, toolBar.getItems());
				} else if (control instanceof Composite childComposite) {
					addControls(child, childComposite, maxDepth, depth + 1);
				}
			}
			Menu context = control.getMenu();
			if (context != null && !context.isDisposed()) {
				child.put("menu", nodeForMenu(context, maxDepth, depth + 1));
			}
			children.add(child);
		}
		parent.put("children", children);
	}

	private void addToolItems(Map<String, Object> parent, ToolItem[] items) {
		List<Object> children = new ArrayList<>();
		for (ToolItem item : items) {
			if (!item.isDisposed()) {
				long id = register(item);
				Map<String, Object> child = node(id, "toolitem", item.getText(), item.isEnabled(), item.getParent().isVisible());
				child.put("checked", item.getSelection());
				children.add(child);
			}
		}
		parent.put("children", children);
	}

	private void addTreeItems(Map<String, Object> parent, TreeItem[] items, int maxDepth, int depth) {
		List<Object> children = new ArrayList<>();
		for (TreeItem item : items) {
			if (item.isDisposed()) {
				continue;
			}
			long id = register(item);
			Map<String, Object> child = node(id, "treeitem", item.getText(), item.getGrayed() ? false : true,
					item.getParent().isVisible());
			child.put("expanded", item.getExpanded());
			child.put("checked", item.getChecked());
			if (depth < maxDepth && item.getExpanded()) {
				addTreeItems(child, item.getItems(), maxDepth, depth + 1);
			}
			children.add(child);
		}
		parent.put("children", children);
	}

	private void addTableItems(Map<String, Object> parent, TableItem[] items) {
		List<Object> children = new ArrayList<>();
		for (TableItem item : items) {
			if (!item.isDisposed()) {
				long id = register(item);
				Map<String, Object> child = node(id, "tableitem", item.getText(), item.getGrayed() ? false : true,
						item.getParent().isVisible());
				child.put("checked", item.getChecked());
				children.add(child);
			}
		}
		parent.put("children", children);
	}

	private Map<String, Object> nodeForMenu(Menu menu, int maxDepth, int depth) {
		long id = register(menu);
		Map<String, Object> node = node(id, "menu", "", menu.isEnabled(), menu.isVisible());
		List<Object> children = new ArrayList<>();
		if (depth <= maxDepth) {
			for (MenuItem item : menu.getItems()) {
				if (item.isDisposed()) {
					continue;
				}
				long itemId = register(item);
				Map<String, Object> child = node(itemId, "menuitem", item.getText(), item.isEnabled(), menu.isVisible());
				child.put("checked", item.getSelection());
				Menu submenu = item.getMenu();
				if (submenu != null && !submenu.isDisposed()) {
					child.put("children", nodeForMenu(submenu, maxDepth, depth + 1).get("children"));
				}
				children.add(child);
			}
		}
		node.put("children", children);
		return node;
	}

	private static Map<String, Object> node(long id, String role, String label, boolean enabled, boolean visible) {
		return Json.object("id", id, "role", role, "label", label == null ? "" : label, "enabled", enabled,
				"visible", visible);
	}

	private static String role(Control control) {
		if (control instanceof Button) return "button";
		if (control instanceof Text) return "text";
		if (control instanceof Combo) return "combo";
		if (control instanceof org.eclipse.swt.widgets.List) return "list";
		if (control instanceof Tree) return "tree";
		if (control instanceof Table) return "table";
		if (control instanceof Label) return "label";
		if (control instanceof ToolBar) return "toolbar";
		if (control instanceof TabFolder) return "tabfolder";
		if (control instanceof Group) return "group";
		if (control instanceof ProgressBar) return "progressbar";
		if (control instanceof Slider || control instanceof Scale) return "slider";
		if (control instanceof Spinner) return "spinner";
		return "composite";
	}

	private static String label(Control control) {
		if (control instanceof Button button) return button.getText();
		if (control instanceof Label label) return label.getText();
		if (control instanceof Group group) return group.getText();
		if (control instanceof Text text) return text.getMessage();
		if (control instanceof Combo combo) return combo.getText();
		return "";
	}

	private static void addState(Map<String, Object> node, Control control) {
		if (control instanceof Text text) {
			node.put("value", text.getText());
		} else if (control instanceof Combo combo) {
			node.put("value", combo.getText());
			node.put("selection", combo.getSelectionIndex());
			node.put("items", List.of(combo.getItems()));
		} else if (control instanceof org.eclipse.swt.widgets.List list) {
			node.put("selection", list.getSelectionIndex());
			node.put("items", List.of(list.getItems()));
		} else if (control instanceof Button button) {
			node.put("checked", button.getSelection());
		} else if (control instanceof Tree tree) {
			node.put("selection", tree.getSelectionCount());
		} else if (control instanceof Table table) {
			node.put("selection", table.getSelectionIndex());
		}
	}

	interface UiCall {
		Object run() throws Exception;
	}
}
