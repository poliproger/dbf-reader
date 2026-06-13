package com.poliproger.dbfreader.editor;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonShortcuts;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.LightColors;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.Alarm;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.poliproger.dbfreader.DbfBundle;
import com.poliproger.dbfreader.model.DbfColumnDef;
import com.poliproger.dbfreader.model.DbfDocument;
import com.poliproger.dbfreader.model.DbfRow;
import com.poliproger.dbfreader.model.DbfTableModel;
import com.poliproger.dbfreader.ui.DbfTableSearch;
import com.poliproger.dbfreader.ui.FilterOnlyRowSorter;
import com.poliproger.dbfreader.ui.cell.CellSearchHighlighter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.RowFilter;
import javax.swing.event.DocumentEvent;
import javax.swing.event.TableModelEvent;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.util.Arrays;
import java.util.BitSet;

/**
 * The Cmd-F search bar over the DBF table: a hidden-by-default toolbar that, when activated,
 * highlights every cell whose displayed text matches the query and lets the user step through the
 * matches (with wrap-around) without hiding any rows. The current match is shown by selecting its
 * cell; the others are shaded by {@link com.poliproger.dbfreader.ui.cell.DbfCellRenderer} via the
 * {@link CellSearchHighlighter} this controller implements.
 */
final class DbfSearchController implements CellSearchHighlighter {

    /** Debounce so typing re-runs the (whole-table) match pass only once typing pauses. */
    private static final int SEARCH_DELAY_MS = 250;

    private final JBTable table;
    private final Alarm alarm;

    private final JPanel bar = new JPanel(new BorderLayout());
    private final SearchTextField searchField = new SearchTextField();
    private final JBLabel countLabel = new JBLabel();

    private boolean matchCase;
    private boolean regex;
    private boolean wholeWords;
    private boolean filter;

    /**
     * Matches in row-major order, each encoded by {@link DbfTableSearch#encode}. Sorted ascending (the
     * scan appends row-major), so {@link #isMatch} can binary-search it instead of keeping a parallel
     * boxed {@code Set} — important when a broad query matches a huge number of cells.
     */
    private long[] matches = new long[0];
    /**
     * Model rows that have at least one matching cell; drives the optional row filter. A {@link BitSet}
     * (rows are dense, {@code 0..rowCount}) avoids boxing one {@code Integer} per matching row.
     */
    private final BitSet matchRows = new BitSet();
    /** Non-null only while {@link #filter} is on and the bar is open; hides non-matching rows. */
    private TableRowSorter<DbfTableModel> sorter;
    private int currentIndex = -1;
    private long lastCurrentCell = -1;

    /**
     * Monotonic id of the most recently requested match pass, bumped on the EDT for every (re)compute,
     * model change and close. The background scan captures its id and is discarded — both early (the
     * scan polls this) and on completion (the apply step checks it) — once a newer request supersedes
     * it. {@code volatile}: written on the EDT, read on the scan thread.
     */
    private volatile long searchSeq;
    /**
     * The {@link #searchSeq} of the last result applied to {@link #matches} (EDT-only). When it equals
     * {@code searchSeq} the match set is final for the current query, so a single-cell edit can patch it
     * in place; otherwise a scan is still in flight and the edit falls back to a full rescan.
     */
    private long appliedSeq;
    /** Set when the parent editor is disposed; gates the background apply step. */
    private volatile boolean disposed;

    private final Color defaultFieldBackground;

    DbfSearchController(@NotNull JBTable table, @NotNull Disposable parent) {
        this.table = table;
        this.alarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, parent);
        this.defaultFieldBackground = searchField.getTextEditor().getBackground();
        Disposer.register(parent, () -> disposed = true);
        buildBar();
    }

    // ---- UI --------------------------------------------------------------------------------

    private void buildBar() {
        searchField.getTextEditor().getEmptyText().setText(DbfBundle.message("editor.search.placeholder"));
        searchField.getTextEditor().setColumns(24);
        searchField.addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                scheduleRecompute(true);
            }
        });
        registerFieldShortcuts();

        DefaultActionGroup options = new DefaultActionGroup();
        options.add(new OptionToggle(DbfBundle.message("editor.search.matchCase"), AllIcons.Actions.MatchCase) {
            @Override
            boolean get() {
                return matchCase;
            }

            @Override
            void set(boolean value) {
                matchCase = value;
            }
        });
        options.add(new OptionToggle(DbfBundle.message("editor.search.wholeWords"), AllIcons.Actions.Words) {
            @Override
            boolean get() {
                return wholeWords;
            }

            @Override
            void set(boolean value) {
                wholeWords = value;
            }
        });
        options.add(new OptionToggle(DbfBundle.message("editor.search.regex"), AllIcons.Actions.Regex) {
            @Override
            boolean get() {
                return regex;
            }

            @Override
            void set(boolean value) {
                regex = value;
            }
        });

        DefaultActionGroup filterGroup = new DefaultActionGroup();
        filterGroup.add(new OptionToggle(DbfBundle.message("editor.search.filter"), AllIcons.General.Filter) {
            @Override
            boolean get() {
                return filter;
            }

            @Override
            void set(boolean value) {
                filter = value;
            }
        });

        DefaultActionGroup nav = new DefaultActionGroup();
        nav.add(new BarAction(DbfBundle.message("editor.search.prev"), AllIcons.Actions.PreviousOccurence) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                findPrev();
            }
        });
        nav.add(new BarAction(DbfBundle.message("editor.search.next"), AllIcons.Actions.NextOccurence) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                findNext();
            }
        });

        DefaultActionGroup closeGroup = new DefaultActionGroup();
        closeGroup.add(new BarAction(DbfBundle.message("editor.search.close"), AllIcons.Actions.Close) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                close();
            }
        });

        JPanel mid = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        mid.add(toolbar(options).getComponent());
        mid.add(toolbar(filterGroup).getComponent());
        mid.add(toolbar(nav).getComponent());
        mid.add(countLabel);

        bar.setBorder(JBUI.Borders.empty(2, 8));
        bar.add(searchField, BorderLayout.WEST);
        bar.add(mid, BorderLayout.CENTER);
        bar.add(toolbar(closeGroup).getComponent(), BorderLayout.EAST);
        bar.setVisible(false);
    }

    private @NotNull ActionToolbar toolbar(@NotNull DefaultActionGroup group) {
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("DbfSearchBar", group, true);
        toolbar.setTargetComponent(searchField);
        return toolbar;
    }

    private void registerFieldShortcuts() {
        JComponent editor = searchField.getTextEditor();
        DumbAwareAction.create(e -> findNext()).registerCustomShortcutSet(CommonShortcuts.ENTER, editor);
        DumbAwareAction.create(e -> findPrev())
                .registerCustomShortcutSet(CustomShortcutSet.fromString("shift ENTER"), editor);
        DumbAwareAction.create(e -> close()).registerCustomShortcutSet(CommonShortcuts.ESCAPE, editor);
    }

    @NotNull JComponent getComponent() {
        return bar;
    }

    // ---- lifecycle / activation ------------------------------------------------------------

    void activate() {
        boolean wasHidden = !bar.isVisible();
        bar.setVisible(true);
        bar.revalidate();
        bar.repaint();
        searchField.getTextEditor().requestFocusInWindow();
        searchField.getTextEditor().selectAll();
        if (wasHidden) {
            lastCurrentCell = -1;
        }
        recompute(true);
    }

    void close() {
        alarm.cancelAllRequests();
        // Stop any in-flight background scan early (its result would be dropped anyway, but a large-file
        // scan should not keep running after the bar is gone).
        searchSeq++;
        bar.setVisible(false);
        bar.revalidate();
        bar.repaint();
        // Restore the full, unfiltered view; the toggle state is kept for the next activation.
        detachRowSorter();
        clearMatches();
        table.repaint();
        table.requestFocusInWindow();
    }

    /**
     * The table model changed: refresh matches if the bar is open. {@code e} is the originating event
     * (or {@code null} for a structural change the editor drives directly). A single committed cell
     * edit only changes that one cell's match state, so it is updated in place; anything else rescans.
     */
    void onModelChanged(@Nullable TableModelEvent e) {
        if (!bar.isVisible()) {
            return;
        }
        if (isSingleCellUpdate(e)) {
            // Update just the edited cell instead of rescanning the whole table — even off the EDT, a
            // large-file scan is wasteful for a one-cell change. (Doesn't touch searchSeq: there is no
            // background pass to invalidate, and an in-flight one stays valid — its snapshot shares this
            // row, whose value the scan reads live.)
            updateCellMatch(e.getFirstRow(), e.getColumn());
            return;
        }
        // Invalidate any in-flight scan at once: it ran on a now-stale snapshot, so its result must
        // not land. The debounce below restarts the scan against the changed model. Refreshes the
        // highlights/counter without yanking the selection away from what the user edits.
        searchSeq++;
        scheduleRecompute(false);
    }

    /** Whether {@code e} is a single committed cell edit ({@code fireTableCellUpdated}). */
    private static boolean isSingleCellUpdate(@Nullable TableModelEvent e) {
        return e != null
                && e.getType() == TableModelEvent.UPDATE
                && e.getColumn() != TableModelEvent.ALL_COLUMNS
                && e.getFirstRow() >= 0  // not HEADER_ROW (-1)
                && e.getFirstRow() == e.getLastRow();
    }

    // ---- search ----------------------------------------------------------------------------

    private void scheduleRecompute(boolean moveSelection) {
        alarm.cancelAllRequests();
        alarm.addRequest(() -> recompute(moveSelection), SEARCH_DELAY_MS);
    }

    /**
     * Re-runs the match pass and refreshes the highlights and counter. When {@code moveSelection} is
     * {@code true} (typing, activation, an option toggle) the selection jumps to the current match;
     * when {@code false} (a refresh triggered by an edit elsewhere in the table) the user's selection
     * is left where it is.
     *
     * <p>The scan itself (format + regex over every cell) runs off the EDT: on a large file it would
     * otherwise freeze the UI, and the debounce only delays its start, not its cost. The row references
     * and column defs are snapshotted here on the EDT so the scan reads a stable view while the user
     * may keep editing the live model; the result is applied back on the EDT, and a pass superseded by
     * a newer request is dropped via {@link #searchSeq}.
     */
    private void recompute(boolean moveSelection) {
        if (!bar.isVisible()) {
            return;
        }
        long seq = ++searchSeq;
        DbfTableModel model = (DbfTableModel) table.getModel();
        DbfDocument doc = model.getDocument();
        DbfTableSearch.Query query = currentQuery();

        // A blank query matches nothing and is cheap to apply; skip the background hop so clearing the
        // field updates instantly.
        if (query.text().isEmpty()) {
            applyResult(DbfTableSearch.Result.EMPTY, query, moveSelection, seq);
            return;
        }

        DbfRow[] rows = doc.getRows().toArray(new DbfRow[0]);
        DbfColumnDef[] defs = new DbfColumnDef[doc.getColumnCount()];
        for (int c = 0; c < defs.length; c++) {
            defs[c] = doc.getColumn(c);
        }
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            DbfTableSearch.Result result = DbfTableSearch.find(rows, defs, query, () -> searchSeq != seq);
            if (result == null) {
                return;  // superseded or aborted mid-scan; a newer pass will (or already did) apply
            }
            ApplicationManager.getApplication().invokeLater(
                    () -> {
                        if (!disposed && bar.isVisible() && searchSeq == seq) {
                            applyResult(result, query, moveSelection, seq);
                        }
                    },
                    ModalityState.any(), o -> disposed);
        });
    }

    private DbfTableSearch.Query currentQuery() {
        return new DbfTableSearch.Query(searchField.getText(), matchCase, regex, wholeWords);
    }

    /** Applies a completed match pass on the EDT: refreshes the matches, filter, counter and selection. */
    private void applyResult(@NotNull DbfTableSearch.Result result, @NotNull DbfTableSearch.Query query,
                             boolean moveSelection, long seq) {
        appliedSeq = seq;
        matches = result.matches();
        matchRows.clear();
        for (long cell : matches) {
            matchRows.set(DbfTableSearch.rowOf(cell));
        }
        applyFilter();
        refreshMatchState(query.text().isEmpty(), result.badPattern(), moveSelection);
    }

    /**
     * Refreshes the counter, current-match index, field-error shade and repaint from the current
     * {@link #matches}. Shared by the full-scan apply and the incremental single-cell update.
     */
    private void refreshMatchState(boolean blankQuery, boolean badPattern, boolean moveSelection) {
        if (matches.length == 0) {
            currentIndex = -1;
            setFieldError(!blankQuery);
            countLabel.setText(blankQuery ? "" : DbfBundle.message(
                    badPattern ? "editor.search.badRegex" : "editor.search.noMatches"));
        } else {
            setFieldError(false);
            currentIndex = indexAtOrAfter(lastCurrentCell);
            if (moveSelection) {
                selectCurrent();
            } else {
                countLabel.setText(DbfBundle.message("editor.search.count", currentIndex + 1, matches.length));
            }
        }
        table.repaint();
    }

    /**
     * Updates the match set for a single edited cell without rescanning the table. Falls back to a full
     * rescan if a scan is still in flight (the match set isn't final, so patching it would corrupt it).
     */
    private void updateCellMatch(int modelRow, int modelColumn) {
        if (searchSeq != appliedSeq) {
            searchSeq++;
            scheduleRecompute(false);
            return;
        }
        DbfTableSearch.Query query = currentQuery();
        if (query.text().isEmpty()) {
            return;  // nothing is highlighted; the edited cell repaints itself from the table event
        }
        DbfDocument doc = ((DbfTableModel) table.getModel()).getDocument();
        if (modelRow < 0 || modelRow >= doc.getRowCount() || modelColumn < 0 || modelColumn >= doc.getColumnCount()) {
            return;
        }
        boolean nowMatch = DbfTableSearch.matchesCell(
                doc.getRows().get(modelRow).get(modelColumn), doc.getColumn(modelColumn), query);
        long cell = DbfTableSearch.encode(modelRow, modelColumn);
        int idx = Arrays.binarySearch(matches, cell);
        boolean wasMatch = idx >= 0;
        if (nowMatch == wasMatch) {
            return;  // match set unchanged; the cell already repaints itself from the table event
        }
        boolean rowVisibilityChanged;
        if (nowMatch) {
            matches = insertAt(matches, -idx - 1, cell);
            rowVisibilityChanged = !matchRows.get(modelRow);
            matchRows.set(modelRow);
        } else {
            matches = removeAt(matches, idx);
            rowVisibilityChanged = !rowHasMatch(modelRow);
            if (rowVisibilityChanged) {
                matchRows.clear(modelRow);
            }
        }
        // Re-apply the filter only when this row just gained/lost its sole match (so it must appear or
        // hide); otherwise its visibility is unchanged and the O(rows) filter pass is unnecessary.
        if (filter && rowVisibilityChanged) {
            applyFilter();
        }
        refreshMatchState(false, false, false);
    }

    /** Whether {@link #matches} still has any cell in {@code modelRow} (matches are sorted row-major). */
    private boolean rowHasMatch(int modelRow) {
        int pos = lowerBound(matches, DbfTableSearch.encode(modelRow, 0));
        return pos < matches.length && DbfTableSearch.rowOf(matches[pos]) == modelRow;
    }

    /** Index of the first element {@code >= key} in the sorted array {@code arr}. */
    private static int lowerBound(long @NotNull [] arr, long key) {
        int idx = Arrays.binarySearch(arr, key);
        return idx >= 0 ? idx : -idx - 1;
    }

    private static long @NotNull [] insertAt(long @NotNull [] arr, int pos, long value) {
        long[] next = new long[arr.length + 1];
        System.arraycopy(arr, 0, next, 0, pos);
        next[pos] = value;
        System.arraycopy(arr, pos, next, pos + 1, arr.length - pos);
        return next;
    }

    private static long @NotNull [] removeAt(long @NotNull [] arr, int pos) {
        long[] next = new long[arr.length - 1];
        System.arraycopy(arr, 0, next, 0, pos);
        System.arraycopy(arr, pos + 1, next, pos, arr.length - pos - 1);
        return next;
    }

    /** First match at or after {@code anchor} (row-major), wrapping to the first match. */
    private int indexAtOrAfter(long anchor) {
        for (int i = 0; i < matches.length; i++) {
            if (matches[i] >= anchor) {
                return i;
            }
        }
        return 0;
    }

    void findNext() {
        if (matches.length == 0) {
            return;
        }
        currentIndex = (currentIndex + 1) % matches.length;
        selectCurrent();
    }

    void findPrev() {
        if (matches.length == 0) {
            return;
        }
        currentIndex = (currentIndex - 1 + matches.length) % matches.length;
        selectCurrent();
    }

    /**
     * Moves the table's cell selection to the current match and scrolls it into view. The current match
     * is shown by the selection itself (so it is highlighted uniformly for every column type, including
     * the Boolean checkbox cells the renderer does not paint); the other matches stay shaded by the
     * renderer.
     */
    private void selectCurrent() {
        long cell = matches[currentIndex];
        lastCurrentCell = cell;
        int viewRow = table.convertRowIndexToView(DbfTableSearch.rowOf(cell));
        int viewColumn = table.convertColumnIndexToView(DbfTableSearch.columnOf(cell));
        if (viewRow >= 0 && viewColumn >= 0) {
            table.changeSelection(viewRow, viewColumn, false, false);
            table.scrollRectToVisible(table.getCellRect(viewRow, viewColumn, true));
        }
        countLabel.setText(DbfBundle.message("editor.search.count", currentIndex + 1, matches.length));
        table.repaint();
    }

    private void clearMatches() {
        matches = new long[0];
        matchRows.clear();
        currentIndex = -1;
        countLabel.setText("");
        setFieldError(false);
    }

    /**
     * Applies or removes the row filter according to {@link #filter}. When on, a {@link TableRowSorter}
     * hides every row that has no matching cell (an empty query keeps all rows shown); when off, the
     * sorter is detached so the full table is restored. Matching cells stay shaded by the renderer in
     * both modes.
     */
    private void applyFilter() {
        if (!filter) {
            detachRowSorter();
            return;
        }
        DbfTableModel model = (DbfTableModel) table.getModel();
        if (sorter == null || sorter.getModel() != model) {
            // Filtering only: keep the model order, since clicking a header to sort would clash with
            // the editor's record-oriented row operations. See FilterOnlyRowSorter for why sorting is
            // blocked by overriding toggleSortOrder rather than with setSortable(false).
            sorter = new FilterOnlyRowSorter(model);
            table.setRowSorter(sorter);
        }
        sorter.setRowFilter(searchField.getText().isEmpty() ? null : new RowFilter<>() {
            @Override
            public boolean include(@NotNull Entry<? extends DbfTableModel, ? extends Integer> entry) {
                return matchRows.get(entry.getIdentifier());
            }
        });
    }

    /** Detaches the filtering row sorter (if any), restoring the unfiltered view. Keeps {@link #filter}. */
    void detachRowSorter() {
        if (sorter != null) {
            sorter = null;
            table.setRowSorter(null);
        }
    }

    private void setFieldError(boolean error) {
        searchField.getTextEditor().setBackground(
                error ? LightColors.RED : (defaultFieldBackground != null ? defaultFieldBackground : UIUtil.getTextFieldBackground()));
    }

    // ---- CellSearchHighlighter -------------------------------------------------------------

    @Override
    public boolean isMatch(int modelRow, int modelColumn) {
        return matches.length > 0
                && Arrays.binarySearch(matches, DbfTableSearch.encode(modelRow, modelColumn)) >= 0;
    }

    // ---- action helpers --------------------------------------------------------------------

    /** A toggle bound to one of the search options; toggling re-runs the match pass immediately. */
    private abstract class OptionToggle extends DumbAwareToggleAction {
        OptionToggle(@NotNull String text, @NotNull Icon icon) {
            super(text, text, icon);
        }

        abstract boolean get();

        abstract void set(boolean value);

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            return get();
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean state) {
            set(state);
            recompute(true);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    /** A plain (non-toggle) bar button (prev/next/close). */
    private abstract static class BarAction extends DumbAwareAction {
        BarAction(@NotNull String text, @NotNull Icon icon) {
            super(text, text, icon);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }
}
