package org.eclipse.mieux.vim.tests;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartSite;
import org.eclipse.ui.texteditor.IEditorStatusLine;

/**
 * A minimal {@link java.lang.reflect.Proxy}-based fake {@link IEditorPart}
 * for driving {@code VimMode} in tests without a running Eclipse workbench.
 * Unhandled interface methods return a type-appropriate default
 * (null/false/0/no-op) instead of throwing, so the large IEditorPart /
 * IWorkbenchPart method surface doesn't need to be hand-implemented -
 * only the handful of methods VimMode actually calls (getAdapter, doSave,
 * getSite().getPage().closeEditor(...)) are wired up.
 */
final class FakeEditorPart {

    private FakeEditorPart() {
    }

    /** Tracks what the fake editor was asked to do, for test assertions. */
    static final class Recorder {
        boolean saved;
        boolean closed;
    }

    /** Records every {@code IEditorStatusLine.setMessage} call's message text. */
    static final class FakeStatusLine implements IEditorStatusLine {
        String lastMessage = "";

        @Override
        public void setMessage(boolean error, String message, org.eclipse.swt.graphics.Image image) {
            lastMessage = message;
        }
    }

    static IEditorPart create(ITextOperationTarget opTarget, FakeStatusLine statusLine, Recorder recorder) {
        Map<String, Function<Object[], Object>> pageHandlers = new HashMap<>();
        pageHandlers.put("closeEditor", args -> {
            recorder.closed = true;
            return true;
        });
        Object page = proxy(IWorkbenchPage.class, pageHandlers);

        Map<String, Function<Object[], Object>> siteHandlers = new HashMap<>();
        siteHandlers.put("getPage", args -> page);
        Object site = proxy(IWorkbenchPartSite.class, siteHandlers);

        Map<String, Function<Object[], Object>> editorHandlers = new HashMap<>();
        editorHandlers.put("getAdapter", args -> {
            Class<?> want = (Class<?>) args[0];
            if (want == ITextOperationTarget.class) {
                return opTarget;
            }
            if (want == IEditorStatusLine.class) {
                return statusLine;
            }
            return null;
        });
        editorHandlers.put("doSave", args -> {
            recorder.saved = true;
            return null;
        });
        editorHandlers.put("getSite", args -> site);
        return (IEditorPart) proxy(IEditorPart.class, editorHandlers);
    }

    private static Object proxy(Class<?> iface, Map<String, Function<Object[], Object>> handlers) {
        InvocationHandler ih = (proxyInstance, method, args) -> {
            Function<Object[], Object> handler = handlers.get(method.getName());
            if (handler != null) {
                return handler.apply(args);
            }
            return defaultReturn(method.getReturnType());
        };
        return Proxy.newProxyInstance(FakeEditorPart.class.getClassLoader(), new Class<?>[] { iface }, ih);
    }

    private static Object defaultReturn(Class<?> returnType) {
        if (returnType == boolean.class) {
            return Boolean.FALSE;
        }
        if (returnType == int.class || returnType == long.class || returnType == short.class || returnType == byte.class) {
            return 0;
        }
        if (returnType == char.class) {
            return (char) 0;
        }
        if (returnType == float.class || returnType == double.class) {
            return 0.0;
        }
        return null;
    }
}
