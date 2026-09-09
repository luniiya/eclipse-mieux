package org.eclipse.mieux.mcp.server.ui;

import java.util.List;
import java.util.Map;

import org.eclipse.mieux.mcp.server.json.Json;
import org.eclipse.mieux.mcp.server.registry.Tool;
import org.eclipse.mieux.mcp.server.registry.ToolExecutionException;
import org.eclipse.mieux.mcp.server.registry.ToolRegistry;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.swt.widgets.Widget;

/** MCP tool implementations backed by the SWT widget tree. */
final class UiTools {

	private UiTools() {
	}

	static void registerAll(ToolRegistry registry, UiAutomation automation) {
		registry.register(new ListWindowsTool(automation));
		registry.register(new SnapshotTool(automation));
		registry.register(new ClickTool(automation));
		registry.register(new SetTextTool(automation));
		registry.register(new SetCheckedTool(automation));
		registry.register(new SelectTool(automation));
		registry.register(new ExpandTool(automation));
		registry.register(new InvokeMenuTool(automation));
	}

	private abstract static class UiTool implements Tool {
		final UiAutomation automation;

		UiTool(UiAutomation automation) {
			this.automation = automation;
		}

		@Override
		public final Object execute(Map<String, Object> arguments) throws ToolExecutionException {
			return automation.call(() -> executeOnUi(arguments));
		}

		abstract Object executeOnUi(Map<String, Object> arguments) throws Exception;
	}

	private static final class ListWindowsTool extends UiTool {
		ListWindowsTool(UiAutomation automation) { super(automation); }
		@Override public String name() { return "ui_list_windows"; }
		@Override public String description() { return "List open Eclipse windows and get shell ids for ui_snapshot."; }
		@Override public Map<String, Object> inputSchema() { return objectSchema(Map.of()); }
		@Override Object executeOnUi(Map<String, Object> arguments) { return automation.listWindows(); }
	}

	private static final class SnapshotTool extends UiTool {
		SnapshotTool(UiAutomation automation) { super(automation); }
		@Override public String name() { return "ui_snapshot"; }
		@Override public String description() { return "Return a structured, text-only snapshot of an Eclipse window's widgets and menus."; }
		@Override public Map<String, Object> inputSchema() {
			return objectSchema(Map.of("shell_id", integerProperty("Shell id from ui_list_windows"),
					"maxDepth", integerProperty("Maximum widget-tree depth, from 0 to 30")), "shell_id");
		}
		@Override Object executeOnUi(Map<String, Object> arguments) throws Exception { return automation.snapshot(arguments); }
	}

	private static final class ClickTool extends UiTool {
		ClickTool(UiAutomation automation) { super(automation); }
		@Override public String name() { return "ui_click"; }
		@Override public String description() { return "Activate a button, toolbar item, menu item, tree item, table item, or control from the latest snapshot."; }
		@Override public Map<String, Object> inputSchema() { return objectSchema(Map.of("id", integerProperty("Widget id from the latest snapshot")), "id"); }
		@Override Object executeOnUi(Map<String, Object> arguments) throws Exception {
			long id = id(arguments);
			Widget widget = automation.requireObject(arguments);
			if (!isEnabled(widget)) throw new ToolExecutionException("Widget is disabled: " + id);
			click(widget);
			return Json.object("id", id, "clicked", true);
		}
	}

	private static final class SetTextTool extends UiTool {
		SetTextTool(UiAutomation automation) { super(automation); }
		@Override public String name() { return "ui_set_text"; }
		@Override public String description() { return "Set the value of a text field or editable combo box from the latest snapshot."; }
		@Override public Map<String, Object> inputSchema() { return objectSchema(Map.of("id", integerProperty("Text or combo id"), "text", stringProperty("New text")), "id", "text"); }
		@Override Object executeOnUi(Map<String, Object> arguments) throws Exception {
			long id = id(arguments);
			String text = string(arguments, "text");
			Widget widget = automation.requireObject(arguments);
			if (widget instanceof Text field) field.setText(text);
			else if (widget instanceof Combo combo) combo.setText(text);
			else throw new ToolExecutionException("Widget is not a text field or combo: " + id);
			return Json.object("id", id, "value", text);
		}
	}

	private static final class SetCheckedTool extends UiTool {
		SetCheckedTool(UiAutomation automation) { super(automation); }
		@Override public String name() { return "ui_set_checked"; }
		@Override public String description() { return "Set the checked/selected state of a checkbox, radio, toggle, menu item, or toolbar item."; }
		@Override public Map<String, Object> inputSchema() { return objectSchema(Map.of("id", integerProperty("Checkable widget id"), "checked", Json.object("type", "boolean")), "id", "checked"); }
		@Override Object executeOnUi(Map<String, Object> arguments) throws Exception {
			long id = id(arguments);
			Object value = arguments.get("checked");
			if (!(value instanceof Boolean checked)) throw new ToolExecutionException("checked must be a boolean");
			Widget widget = automation.requireObject(arguments);
			if (widget instanceof Button button) button.setSelection(checked);
			else if (widget instanceof MenuItem item) item.setSelection(checked);
			else if (widget instanceof ToolItem item) item.setSelection(checked);
			else throw new ToolExecutionException("Widget is not checkable: " + id);
			return Json.object("id", id, "checked", checked);
		}
	}

	private static final class SelectTool extends UiTool {
		SelectTool(UiAutomation automation) { super(automation); }
		@Override public String name() { return "ui_select"; }
		@Override public String description() { return "Select an item in a combo, list, tree, or table using index or visible text."; }
		@Override public Map<String, Object> inputSchema() {
			return objectSchema(Map.of("id", integerProperty("Selectable widget or item id"), "index", integerProperty("Zero-based item index"), "text", stringProperty("Item text")), "id");
		}
		@Override Object executeOnUi(Map<String, Object> arguments) throws Exception {
			long id = id(arguments);
			Widget widget = automation.requireObject(arguments);
			int index = selectionIndex(arguments, widget);
			if (widget instanceof Combo combo) combo.select(index);
			else if (widget instanceof org.eclipse.swt.widgets.List list) list.select(index);
			else if (widget instanceof Tree tree) tree.setSelection(tree.getItem(index));
			else if (widget instanceof TreeItem item) item.getParent().setSelection(item);
			else if (widget instanceof Table table) table.setSelection(table.getItem(index));
			else if (widget instanceof TableItem item) item.getParent().setSelection(item);
			else throw new ToolExecutionException("Widget is not selectable: " + id);
			Widget parent = widget instanceof TreeItem item ? item.getParent() : widget instanceof TableItem item ? item.getParent() : widget;
			parent.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
			return Json.object("id", id, "index", index);
		}
	}

	private static final class ExpandTool extends UiTool {
		ExpandTool(UiAutomation automation) { super(automation); }
		@Override public String name() { return "ui_expand"; }
		@Override public String description() { return "Expand or collapse a tree node from the latest snapshot."; }
		@Override public Map<String, Object> inputSchema() { return objectSchema(Map.of("id", integerProperty("Tree item id"), "expanded", Json.object("type", "boolean")), "id"); }
		@Override Object executeOnUi(Map<String, Object> arguments) throws Exception {
			long id = id(arguments);
			Widget widget = automation.requireObject(arguments);
			if (!(widget instanceof TreeItem item)) throw new ToolExecutionException("Widget is not a tree item: " + id);
			Object requested = arguments.get("expanded");
			boolean expanded = requested == null || !(requested instanceof Boolean) ? !item.getExpanded() : ((Boolean) requested).booleanValue();
			item.setExpanded(expanded);
			return Json.object("id", id, "expanded", expanded);
		}
	}

	private static final class InvokeMenuTool extends UiTool {
		InvokeMenuTool(UiAutomation automation) { super(automation); }
		@Override public String name() { return "ui_invoke_menu"; }
		@Override public String description() { return "Invoke a menu item from the latest snapshot."; }
		@Override public Map<String, Object> inputSchema() { return objectSchema(Map.of("id", integerProperty("Menu item id")), "id"); }
		@Override Object executeOnUi(Map<String, Object> arguments) throws Exception {
			long id = id(arguments);
			Widget widget = automation.requireObject(arguments);
			if (!(widget instanceof MenuItem item)) throw new ToolExecutionException("Widget is not a menu item: " + id);
			if (!item.isEnabled()) throw new ToolExecutionException("Menu item is disabled: " + id);
			click(item);
			return Json.object("id", id, "invoked", true);
		}
	}

	private static void click(Widget widget) {
		if (widget instanceof Button button) {
			int style = button.getStyle();
			if ((style & (SWT.CHECK | SWT.TOGGLE)) != 0) button.setSelection(!button.getSelection());
			else if ((style & SWT.RADIO) != 0) button.setSelection(true);
			button.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
		} else if (widget instanceof MenuItem item) {
			if ((item.getStyle() & (SWT.CHECK | SWT.RADIO)) != 0) item.setSelection(!item.getSelection());
			item.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
		} else if (widget instanceof ToolItem item) {
			if ((item.getStyle() & (SWT.CHECK | SWT.RADIO)) != 0) item.setSelection(!item.getSelection());
			item.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
		} else if (widget instanceof TreeItem item) {
			item.getParent().setSelection(item);
			item.getParent().notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
		} else if (widget instanceof TableItem item) {
			item.getParent().setSelection(item);
			item.getParent().notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
		} else if (widget instanceof Control control) {
			control.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
		} else {
			throw new IllegalArgumentException("Widget is not clickable: " + widget.getClass().getSimpleName());
		}
	}

	private static boolean isEnabled(Widget widget) {
		if (widget instanceof Control control) return control.isEnabled();
		if (widget instanceof MenuItem item) return item.isEnabled();
		if (widget instanceof ToolItem item) return item.isEnabled();
		return true;
	}

	private static int selectionIndex(Map<String, Object> arguments, Widget widget) throws ToolExecutionException {
		Object indexValue = arguments.get("index");
		if (indexValue instanceof Number number) return number.intValue();
		String text = string(arguments, "text");
		String[] items;
		if (widget instanceof Combo combo) {
			items = combo.getItems();
		} else if (widget instanceof org.eclipse.swt.widgets.List list) {
			items = list.getItems();
		} else if (widget instanceof Tree tree) {
			TreeItem[] treeItems = tree.getItems();
			items = new String[treeItems.length];
			for (int i = 0; i < treeItems.length; i++) items[i] = treeItems[i].getText();
		} else if (widget instanceof Table table) {
			TableItem[] tableItems = table.getItems();
			items = new String[tableItems.length];
			for (int i = 0; i < tableItems.length; i++) items[i] = tableItems[i].getText();
		} else {
			items = null;
		}
		if (items != null) for (int i = 0; i < items.length; i++) if (text.equals(items[i])) return i;
		throw new ToolExecutionException("No item named " + text);
	}

	private static long id(Map<String, Object> arguments) throws ToolExecutionException {
		Object value = arguments.get("id");
		if (!(value instanceof Number number)) throw new ToolExecutionException("id must be a number");
		return number.longValue();
	}

	private static String string(Map<String, Object> arguments, String name) throws ToolExecutionException {
		Object value = arguments.get(name);
		if (!(value instanceof String text)) throw new ToolExecutionException(name + " must be a string");
		return text;
	}

	private static Map<String, Object> objectSchema(Map<String, Object> properties, String... required) {
		return Json.object("type", "object", "properties", properties, "required", List.of(required), "additionalProperties", false);
	}

	private static Map<String, Object> integerProperty(String description) {
		return Json.object("type", "integer", "description", description);
	}

	private static Map<String, Object> stringProperty(String description) {
		return Json.object("type", "string", "description", description);
	}
}
