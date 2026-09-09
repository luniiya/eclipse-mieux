package org.eclipse.mieux.vim.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.eclipse.jface.text.DefaultUndoManager;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.TextViewer;
import org.eclipse.mieux.vim.VimMode;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end behavioural tests for {@link VimMode}, driven directly through
 * its public {@link VimMode#verifyKey} entry point against a real SWT
 * {@link Display}/{@link Shell} and a real JFace {@link TextViewer} backed by
 * a real {@link Document} - none of the actual text-editing machinery is
 * mocked, only the surrounding {@code IEditorPart} (see
 * {@link FakeEditorPart}), which in the live product is supplied by a full
 * running workbench.
 *
 * <p>
 * A few tests reach {@code VimMode}'s private {@code executeExCommand},
 * {@code toggleDisabled} and {@code mode}/{@code lastSearch} fields via
 * reflection (see {@link #invokePrivate} / {@link #modeName}). In the real
 * editor those are driven by widget-level UI (the Spotlight ":" popup, a
 * mouse double-click on the mode badge) rather than key events on the main
 * editor widget, so they sit outside what {@code verifyKey} alone can
 * exercise - reflection is the pragmatic way to still test the logic
 * end-to-end without reconstructing that UI.
 */
class VimModeTest {

    private static Display display;

    private Shell shell;
    private TextViewer viewer;
    private VimMode vim;
    private FakeEditorPart.Recorder recorder;
    private FakeEditorPart.FakeStatusLine statusLine;

    @BeforeAll
    static void createDisplay() {
        display = Display.getDefault();
    }

    @AfterAll
    static void disposeDisplay() {
        if (display != null && !display.isDisposed()) {
            display.dispose();
        }
    }

    @BeforeEach
    @SuppressWarnings("removal") // DefaultUndoManager is deprecated for
    // removal, but it's still the only IUndoManager implementation JFace
    // text ships - there's no replacement to migrate to yet.
    void setUp() {
        shell = new Shell(display);
        shell.setSize(800, 600);
        viewer = new TextViewer(shell, SWT.NONE);
        viewer.setUndoManager(new DefaultUndoManager(1000));
        shell.layout();
    }

    @AfterEach
    void tearDown() {
        if (vim != null) {
            vim.dispose();
        }
        if (shell != null && !shell.isDisposed()) {
            shell.dispose();
        }
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private void openWith(String text) {
        IDocument doc = new Document(text);
        viewer.setDocument(doc);
        // In the real product, AbstractTextEditor calls this when the editor
        // becomes the active part - it's what actually connects the undo
        // manager (TextViewer.setUndoManager alone just stashes the field).
        // Without it, u/Ctrl+R would silently no-op here even though real
        // usage always has it wired up.
        viewer.activatePlugins();
        recorder = new FakeEditorPart.Recorder();
        statusLine = new FakeEditorPart.FakeStatusLine();
        IEditorPart editor = FakeEditorPart.create(viewer, statusLine, recorder);
        vim = new VimMode(editor, viewer);
    }

    private IDocument doc() {
        return viewer.getDocument();
    }

    private String text() {
        return doc().get();
    }

    private int caretOffset() {
        return viewer.getSelectedRange().x;
    }

    private VerifyEvent fireKey(char character, int keyCode, int stateMask) {
        Event e = new Event();
        e.widget = viewer.getTextWidget();
        e.character = character;
        e.keyCode = keyCode;
        e.stateMask = stateMask;
        e.doit = true;
        VerifyEvent ve = new VerifyEvent(e);
        vim.verifyKey(ve);
        return ve;
    }

    private void key(char c) {
        fireKey(c, c, SWT.NONE);
    }

    private void keys(String chars) {
        for (int i = 0; i < chars.length(); i++) {
            key(chars.charAt(i));
        }
    }

    private void esc() {
        fireKey((char) 0, SWT.ESC, SWT.NONE);
    }

    private void ctrl(char c) {
        fireKey(c, c, SWT.CTRL);
    }

    private void arrow(int keyCode) {
        fireKey((char) 0, keyCode, SWT.NONE);
    }

    private void enter() {
        fireKey((char) SWT.CR, SWT.CR, SWT.NONE);
    }

    /** Simulates real typing while in Insert mode: verifyKey deliberately lets these fall through untouched (same as native StyledText would handle a real keystroke), so we apply the edit + caret move directly against the real document/viewer instead of relying on a synthetic key event to do it. */
    private void typeInsert(String s) {
        int offset = caretOffset();
        try {
            doc().replace(offset, 0, s);
        } catch (org.eclipse.jface.text.BadLocationException e) {
            throw new RuntimeException(e);
        }
        viewer.setSelectedRange(offset + s.length(), 0);
    }

    private String modeName() throws Exception {
        Field f = VimMode.class.getDeclaredField("mode");
        f.setAccessible(true);
        return f.get(vim).toString();
    }

    private Object getPrivate(String field) throws Exception {
        Field f = VimMode.class.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(vim);
    }

    private void invokePrivate(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = VimMode.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        m.invoke(vim, args);
    }

    // ------------------------------------------------------------------
    // Motions
    // ------------------------------------------------------------------

    @Test
    void h_and_l_move_the_caret() {
        openWith("abc");
        key('l');
        key('l');
        assertEquals(2, caretOffset());
        key('h');
        assertEquals(1, caretOffset());
    }

    @Test
    void zero_and_dollar_jump_to_line_ends() {
        openWith("hello world");
        key('$');
        assertEquals(10, caretOffset());
        key('0');
        assertEquals(0, caretOffset());
    }

    @Test
    void word_motions_w_b_e() {
        openWith("foo bar baz");
        key('w');
        assertEquals(4, caretOffset());
        key('w');
        assertEquals(8, caretOffset());
        key('b');
        assertEquals(4, caretOffset());
        key('0');
        key('e');
        assertEquals(2, caretOffset());
    }

    @Test
    void gg_and_G_jump_to_first_and_last_line() {
        openWith("a\nb\nc");
        key('G');
        assertEquals(4, caretOffset()); // 'c' is the first non-blank of the last line
        keys("gg");
        assertEquals(0, caretOffset());
    }

    @Test
    void f_and_F_find_within_the_line() {
        openWith("abcXdefXghi");
        keys("f");
        key('X');
        assertEquals(3, caretOffset());
        keys("f");
        key('X');
        assertEquals(7, caretOffset());
        keys("F");
        key('X');
        assertEquals(3, caretOffset());
    }

    @Test
    void count_prefix_multiplies_a_motion() {
        openWith("abcdefghij");
        keys("3l");
        assertEquals(3, caretOffset());
    }

    // ------------------------------------------------------------------
    // Operators
    // ------------------------------------------------------------------

    @Test
    void dw_deletes_a_word() {
        openWith("foo bar");
        keys("dw");
        assertEquals("bar", text());
        assertEquals(0, caretOffset());
    }

    @Test
    void doubled_operator_dd_deletes_the_whole_line() {
        openWith("one\ntwo\nthree");
        keys("dd");
        assertEquals("two\nthree", text());
    }

    @Test
    void yy_then_p_duplicates_the_line_below() {
        openWith("one\ntwo");
        keys("yy");
        key('p');
        assertEquals("one\none\ntwo", text());
    }

    @Test
    void P_pastes_a_yanked_line_above() {
        openWith("one\ntwo\nthree");
        key('j'); // onto "two"
        keys("yy");
        key('k'); // back onto "one"
        key('P');
        assertEquals("two\none\ntwo\nthree", text());
    }

    @Test
    void x_and_X_delete_around_the_cursor() {
        openWith("abc");
        key('x');
        assertEquals("bc", text());
        openWith("abc");
        key('l');
        key('X');
        assertEquals("bc", text());
    }

    @Test
    void D_deletes_to_end_of_line() {
        openWith("hello world");
        keys("5l"); // caret onto the space at index 5
        key('D');
        assertEquals("hello", text());
    }

    @Test
    void C_changes_to_end_of_line_and_enters_insert() throws Exception {
        openWith("hello world");
        keys("5l");
        key('C');
        assertEquals("INSERT", modeName());
        typeInsert("!");
        esc();
        assertEquals("hello!", text());
        assertEquals("NORMAL", modeName());
    }

    @Test
    void r_replaces_the_character_under_the_cursor() {
        openWith("abc");
        key('r');
        key('Z');
        assertEquals("Zbc", text());
    }

    @Test
    void tilde_toggles_case() {
        openWith("aB");
        key('~');
        assertEquals("AB", text());
    }

    @Test
    void operator_plus_find_motion_dfX() {
        openWith("abcXdef");
        keys("d");
        keys("f");
        key('X');
        assertEquals("def", text());
    }

    // ------------------------------------------------------------------
    // Undo / redo
    // ------------------------------------------------------------------

    @Test
    void u_undoes_and_ctrl_r_redoes() {
        openWith("abc");
        key('x');
        assertEquals("bc", text());
        key('u');
        assertEquals("abc", text());
        ctrl('r');
        assertEquals("bc", text());
    }

    // ------------------------------------------------------------------
    // Insert mode entry points
    // ------------------------------------------------------------------

    @Test
    void i_inserts_before_the_cursor() throws Exception {
        openWith("bc");
        key('i');
        assertEquals("INSERT", modeName());
        typeInsert("a");
        esc();
        assertEquals("abc", text());
    }

    @Test
    void a_inserts_after_the_cursor() {
        openWith("bc");
        key('a');
        typeInsert("X");
        esc();
        assertEquals("bXc", text());
    }

    @Test
    void I_inserts_at_first_non_blank() {
        openWith("  bc");
        key('I');
        typeInsert("a");
        esc();
        assertEquals("  abc", text());
    }

    @Test
    void A_inserts_at_end_of_line() {
        openWith("bc");
        key('A');
        typeInsert("X");
        esc();
        assertEquals("bcX", text());
    }

    @Test
    void o_and_O_open_new_lines() {
        openWith("one\ntwo");
        key('o');
        typeInsert("mid");
        esc();
        assertEquals("one\nmid\ntwo", text());
    }

    // ------------------------------------------------------------------
    // Visual mode
    // ------------------------------------------------------------------

    @Test
    void visual_mode_selection_is_visible_and_deletable() throws Exception {
        openWith("hello world");
        key('v');
        assertEquals("VISUAL", modeName());
        keys("lll");
        org.eclipse.swt.graphics.Point sel = viewer.getSelectedRange();
        assertEquals(0, sel.x);
        assertEquals(4, sel.y); // "hell" selected
        key('d');
        assertEquals("o world", text());
        assertEquals("NORMAL", modeName());
    }

    @Test
    void arrow_keys_extend_the_visual_selection_instead_of_collapsing_it() {
        // Regression test for the reported bug: arrow keys used to fall
        // through to StyledText's native caret movement and silently cancel
        // whatever selection Visual mode had just set up.
        openWith("hello");
        key('v');
        arrow(SWT.ARROW_RIGHT);
        arrow(SWT.ARROW_RIGHT);
        org.eclipse.swt.graphics.Point sel = viewer.getSelectedRange();
        assertEquals(0, sel.x);
        assertEquals(3, sel.y);
    }

    @Test
    void visual_line_mode_deletes_whole_lines() throws Exception {
        openWith("one\ntwo\nthree");
        key('V');
        assertEquals("VISUAL_LINE", modeName());
        key('j');
        key('d');
        assertEquals("three", text());
    }

    @Test
    void visual_block_ctrl_v_deletes_a_rectangle() throws Exception {
        openWith("abc\nabc\nabc");
        ctrl('v');
        assertEquals("VISUAL_BLOCK", modeName());
        keys("ll");
        key('j');
        key('j');
        key('x');
        assertEquals("\n\n", text());
        assertEquals("NORMAL", modeName());
    }

    @Test
    void visual_block_tilde_toggles_case_per_column() {
        openWith("abc\nabc");
        ctrl('v');
        keys("l"); // columns 0..1
        key('j'); // second line too
        key('~');
        assertEquals("ABc\nABc", text());
    }

    @Test
    void escape_from_visual_returns_to_normal_without_editing() throws Exception {
        openWith("hello");
        key('v');
        keys("ll");
        esc();
        assertEquals("NORMAL", modeName());
        assertEquals("hello", text());
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    @Test
    void slash_search_and_n_repeat() {
        openWith("foo bar foo baz foo");
        key('/');
        keys("foo");
        enter();
        assertEquals(8, caretOffset());
        key('n');
        assertEquals(16, caretOffset());
    }

    // ------------------------------------------------------------------
    // Ex-commands (via the Spotlight popup in the real editor; reached
    // directly here - see the class javadoc).
    // ------------------------------------------------------------------

    @Test
    void ex_command_goto_line() throws Exception {
        openWith("l1\nl2\nl3\nl4\nl5\nl6");
        invokePrivate("executeExCommand", new Class<?>[] { String.class }, ":5");
        assertEquals(4, doc().getLineOfOffset(caretOffset()));
    }

    @Test
    void ex_command_substitute_current_line_only() throws Exception {
        openWith("foo foo\nfoo foo");
        invokePrivate("executeExCommand", new Class<?>[] { String.class }, ":s/foo/bar/");
        assertEquals("bar foo\nfoo foo", text());
    }

    @Test
    void ex_command_substitute_whole_document_global() throws Exception {
        openWith("foo foo\nfoo foo");
        invokePrivate("executeExCommand", new Class<?>[] { String.class }, ":%s/foo/bar/g");
        assertEquals("bar bar\nbar bar", text());
    }

    @Test
    void ex_command_noh_clears_last_search() throws Exception {
        openWith("foo bar foo");
        key('/');
        keys("foo");
        enter();
        assertEquals("foo", getPrivate("lastSearch"));
        invokePrivate("executeExCommand", new Class<?>[] { String.class }, ":noh");
        assertNull(getPrivate("lastSearch"));
    }

    @Test
    void ex_command_w_saves_via_the_editor() throws Exception {
        openWith("x");
        invokePrivate("executeExCommand", new Class<?>[] { String.class }, ":w");
        assertTrue(recorder.saved);
    }

    @Test
    void ex_command_q_closes_via_the_workbench_page() throws Exception {
        openWith("x");
        invokePrivate("executeExCommand", new Class<?>[] { String.class }, ":q");
        assertTrue(recorder.closed);
    }

    // ------------------------------------------------------------------
    // DISABLED mode (the mode-badge double-click toggle)
    // ------------------------------------------------------------------

    @Test
    void disabling_vim_mode_stops_it_from_touching_keys() throws Exception {
        openWith("abc");
        invokePrivate("toggleDisabled", new Class<?>[0]);
        assertEquals("DISABLED", modeName());

        VerifyEvent ve = fireKey('x', 'x', SWT.NONE);
        assertTrue(ve.doit); // never claimed - stock Eclipse would handle it
        assertEquals("abc", text());

        invokePrivate("toggleDisabled", new Class<?>[0]);
        assertEquals("NORMAL", modeName());
        key('x');
        assertEquals("bc", text());
    }
}
