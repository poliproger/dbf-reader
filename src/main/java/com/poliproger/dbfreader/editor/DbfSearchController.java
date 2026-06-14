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
import java.util.BitSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
     * Number of matching cells in each model row (index = model row), or {@code null} for a blank/invalid
     * query. Drives the exact "N of M" counter and the incremental single-cell update without storing a
     * coordinate per matching cell — the per-cell highlight is recomputed live in {@link #isMatch}. This
     * is the {@code O(rows)} state that replaces the old {@code O(matched cells)} array; see
     * {@code notes/search-wide-query-memory.md} (variant B + per-row counts).
     */
    private int @Nullable [] rowMatchCount;
    /** Total number of matching cells (= sum of {@link #rowMatchCount}); the M in the "N of M" counter. */
    private int total;
    /**
     * Model rows that have at least one matching cell; drives the row filter and the prev/next navigation
     * (fast {@code nextSetBit}/{@code previousSetBit} over matching rows). Kept in sync with
     * {@link #rowMatchCount} ({@code matchRows.get(r) == rowMatchCount[r] > 0}). A {@link BitSet} (rows are
     * dense, {@code 0..rowCount}) avoids boxing one {@code Integer} per matching row.
     */
    private final BitSet matchRows = new BitSet();
    /** Non-null only while {@link #filter} is on and the bar is open; hides non-matching rows. */
    private TableRowSorter<DbfTableModel> sorter;
    /** Encoded coordinate of the current match (shown by the selection), or -1 when there is none. */
    private long currentCell = -1;
    /** 0-based rank of {@link #currentCell} among all matches; the N-1 in the "N of M" counter. */
    private int currentIndex = -1;
    /** Anchor for the next scan: the last cell the selection was moved to (row-major). */
    private long lastCurrentCell = -1;
    /**
     * Compiled pattern for the active query, cached so the renderer's {@link #isMatch} (called per visible
     * cell on every repaint) and navigation needn't recompile the regex per cell. {@code null} for a
     * blank/invalid query. Set on the EDT in {@link #applyScan}/{@link #applyEmpty} with the match data.
     */
    private @Nullable Pattern matchPattern;

    /**
     * Monotonic id of the most recently requested match pass, bumped on the EDT for every (re)compute,
     * model change and close. The background scan captures its id and is discarded — both early (the
     * scan polls this) and on completion (the apply step checks it) — once a newer request supersedes
     * it. {@code volatile}: written on the EDT, read on the scan thread.
     */
    private volatile long searchSeq;
    /**
     * The {@link #searchSeq} of the last scan applied to {@link #rowMatchCount} (EDT-only). When it equals
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
            applyEmpty(seq);
            return;
        }

        DbfRow[] rows = doc.getRows().toArray(new DbfRow[0]);
        DbfColumnDef[] defs = new DbfColumnDef[doc.getColumnCount()];
        for (int c = 0; c < defs.length; c++) {
            defs[c] = doc.getColumn(c);
        }
        long anchor = lastCurrentCell;  // snapshot the selection anchor on the EDT for the rank computation
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            DbfTableSearch.ScanResult result = DbfTableSearch.scan(rows, defs, query, anchor, () -> searchSeq != seq);
            if (result == null) {
                return;  // superseded or aborted mid-scan; a newer pass will (or already did) apply
            }
            ApplicationManager.getApplication().invokeLater(
                    () -> {
                        if (!disposed && bar.isVisible() && searchSeq == seq) {
                            applyScan(result, query, moveSelection, seq);
                        }
                    },
                    ModalityState.any(), o -> disposed);
        });
    }

    private DbfTableSearch.Query currentQuery() {
        return new DbfTableSearch.Query(searchField.getText(), matchCase, regex, wholeWords);
    }

    /**
     * Applies a completed scan on the EDT: caches the pattern, swaps in the per-row counts, rebuilds the
     * matching-row set and the filter, and updates the counter/selection. {@code moveSelection} jumps the
     * selection to the current match (typing/activation/toggle); otherwise the selection is left in place
     * (a refresh after an edit elsewhere).
     */
    private void applyScan(@NotNull DbfTableSearch.ScanResult result, @NotNull DbfTableSearch.Query query,
                           boolean moveSelection, long seq) {
        appliedSeq = seq;
        if (result.badPattern()) {
            clearMatches();
            showNoMatches(true);
            applyFilter();
            table.repaint();
            return;
        }
        matchPattern = compileQuietly(query);
        rowMatchCount = result.rowMatchCount();
        total = result.total();
        rebuildMatchRows();
        currentCell = result.currentCell();
        currentIndex = result.currentIndex();
        applyFilter();
        if (total == 0) {
            showNoMatches(false);
        } else {
            setFieldError(false);
            if (moveSelection) {
                selectCurrent();
            } else {
                updateCounter();
            }
        }
        table.repaint();
    }

    /** Applies the blank-query state on the EDT: nothing highlighted, full view restored, counter cleared. */
    private void applyEmpty(long seq) {
        appliedSeq = seq;
        clearMatches();
        applyFilter();  // empty query → the filter shows all rows
        table.repaint();
    }

    /** Rebuilds {@link #matchRows} from {@link #rowMatchCount} ({@code count > 0} ⇒ the row has a match). */
    private void rebuildMatchRows() {
        matchRows.clear();
        if (rowMatchCount == null) {
            return;
        }
        for (int r = 0; r < rowMatchCount.length; r++) {
            if (rowMatchCount[r] > 0) {
                matchRows.set(r);
            }
        }
    }

    /** Shows the "N of M" counter for the current match. */
    private void updateCounter() {
        countLabel.setText(DbfBundle.message("editor.search.count", currentIndex + 1, total));
    }

    /** Shows the no-match (or bad-regex) state: red field, message, no current match. */
    private void showNoMatches(boolean badPattern) {
        currentCell = -1;
        currentIndex = -1;
        setFieldError(true);
        countLabel.setText(DbfBundle.message(badPattern ? "editor.search.badRegex" : "editor.search.noMatches"));
    }

    /** Compiles the query for the live highlight; {@code null} for a blank or invalid query. */
    private static @Nullable Pattern compileQuietly(@NotNull DbfTableSearch.Query query) {
        if (query.text().isEmpty()) {
            return null;
        }
        try {
            return DbfTableSearch.compile(query);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    /**
     * Updates the match set for a single edited cell without rescanning the table. Falls back to a full
     * rescan if a scan is still in flight (the per-row counts aren't final, so patching them would corrupt
     * them). The edited cell's match state changed iff its row's match count changed; that delta (±1, since
     * only one cell changed) is applied to {@link #total} and {@link #rowMatchCount}, so the "N of M"
     * counter stays exact without rescanning a large file.
     */
    private void updateCellMatch(int modelRow, int modelColumn) {
        if (searchSeq != appliedSeq) {
            searchSeq++;
            scheduleRecompute(false);
            return;
        }
        if (matchPattern == null || rowMatchCount == null) {
            return;  // nothing is highlighted; the edited cell repaints itself from the table event
        }
        DbfDocument doc = document();
        if (modelRow < 0 || modelRow >= doc.getRowCount() || modelRow >= rowMatchCount.length
                || modelColumn < 0 || modelColumn >= doc.getColumnCount()) {
            return;
        }
        int oldRowCount = rowMatchCount[modelRow];
        int newRowCount = countMatchesInRow(modelRow);
        if (newRowCount == oldRowCount) {
            return;  // the edited cell's match state is unchanged; it already repaints itself
        }
        int delta = newRowCount - oldRowCount;  // +1 or -1: only the edited cell changed in this row
        rowMatchCount[modelRow] = newRowCount;
        total += delta;
        boolean rowVisibilityChanged = (oldRowCount == 0) != (newRowCount == 0);
        if (newRowCount > 0) {
            matchRows.set(modelRow);
        } else {
            matchRows.clear(modelRow);
        }
        updateCurrentAfterEdit(DbfTableSearch.encode(modelRow, modelColumn), delta);
        // Re-apply the filter only when this row just gained/lost its sole match (so it must appear or
        // hide); otherwise its visibility is unchanged and the O(rows) filter pass is unnecessary.
        if (filter && rowVisibilityChanged) {
            applyFilter();
        }
        if (total == 0) {
            showNoMatches(false);
        } else {
            setFieldError(false);
            updateCounter();
        }
        table.repaint();
    }

    /**
     * Keeps {@link #currentCell}/{@link #currentIndex} consistent after a single-cell edit changed the
     * match set by {@code delta} (±1) at {@code editedCell}. A match added/removed <em>before</em> the
     * current one shifts its rank; if the current cell itself stopped matching, the selection moves to the
     * next match (wrapping), inheriting its rank.
     */
    private void updateCurrentAfterEdit(long editedCell, int delta) {
        if (total == 0) {
            currentCell = -1;
            currentIndex = -1;
        } else if (currentCell < 0) {
            currentCell = firstMatch();  // there were no matches before; select the first one now
            currentIndex = 0;
        } else if (delta > 0) {
            if (editedCell < currentCell) {
                currentIndex++;  // a match appeared before the current one
            }
        } else if (editedCell == currentCell) {
            // The selected cell stopped matching: move to the next match. If it wrapped (the removed cell
            // was the last), the rank resets to 0; otherwise the next cell inherits the removed cell's rank.
            long next = nextMatch(editedCell);
            currentCell = next;
            if (next < editedCell) {
                currentIndex = 0;
            }
        } else if (editedCell < currentCell) {
            currentIndex--;  // a match disappeared before the current one
        }
    }

    private @NotNull DbfDocument document() {
        return ((DbfTableModel) table.getModel()).getDocument();
    }

    /** Number of cells matching {@link #matchPattern} in model row {@code r}. */
    private int countMatchesInRow(int r) {
        if (matchPattern == null) {
            return 0;
        }
        DbfDocument doc = document();
        DbfRow row = doc.getRows().get(r);
        int cols = doc.getColumnCount();
        int count = 0;
        for (int c = 0; c < cols; c++) {
            if (DbfTableSearch.matches(matchPattern, row.get(c), doc.getColumn(c))) {
                count++;
            }
        }
        return count;
    }

    /** First matching cell in model row {@code r} at a column {@code >= from}, or -1. */
    private long matchInRowFrom(int r, int from) {
        if (matchPattern == null) {
            return -1;
        }
        DbfDocument doc = document();
        if (r < 0 || r >= doc.getRowCount()) {
            return -1;
        }
        DbfRow row = doc.getRows().get(r);
        int cols = doc.getColumnCount();
        for (int c = Math.max(from, 0); c < cols; c++) {
            if (DbfTableSearch.matches(matchPattern, row.get(c), doc.getColumn(c))) {
                return DbfTableSearch.encode(r, c);
            }
        }
        return -1;
    }

    /** Last matching cell in model row {@code r} at a column {@code < before}, or -1. */
    private long matchInRowBefore(int r, int before) {
        if (matchPattern == null) {
            return -1;
        }
        DbfDocument doc = document();
        if (r < 0 || r >= doc.getRowCount()) {
            return -1;
        }
        DbfRow row = doc.getRows().get(r);
        for (int c = Math.min(before, doc.getColumnCount()) - 1; c >= 0; c--) {
            if (DbfTableSearch.matches(matchPattern, row.get(c), doc.getColumn(c))) {
                return DbfTableSearch.encode(r, c);
            }
        }
        return -1;
    }

    /**
     * First match strictly after {@code cell} in row-major order, wrapping to the first match. Uses
     * {@link #matchRows} to skip directly to the next row that has a match, then scans that row's columns
     * live — so a broad query (dense {@code matchRows}) finds the neighbour at once and a narrow one
     * (sparse {@code matchRows}) skips the empty rows cheaply via {@code nextSetBit}.
     */
    private long nextMatch(long cell) {
        int row = DbfTableSearch.rowOf(cell);
        long inRow = matchInRowFrom(row, DbfTableSearch.columnOf(cell) + 1);
        if (inRow >= 0) {
            return inRow;
        }
        for (int r = matchRows.nextSetBit(row + 1); r >= 0; r = matchRows.nextSetBit(r + 1)) {
            long m = matchInRowFrom(r, 0);
            if (m >= 0) {
                return m;
            }
        }
        return firstMatch();  // wrap
    }

    /** Last match strictly before {@code cell} in row-major order, wrapping to the last match. */
    private long prevMatch(long cell) {
        int row = DbfTableSearch.rowOf(cell);
        long inRow = matchInRowBefore(row, DbfTableSearch.columnOf(cell));
        if (inRow >= 0) {
            return inRow;
        }
        for (int r = matchRows.previousSetBit(row - 1); r >= 0; r = matchRows.previousSetBit(r - 1)) {
            long m = matchInRowBefore(r, document().getColumnCount());
            if (m >= 0) {
                return m;
            }
        }
        return lastMatch();  // wrap
    }

    /** The first match in row-major order, or -1 when there are none. */
    private long firstMatch() {
        for (int r = matchRows.nextSetBit(0); r >= 0; r = matchRows.nextSetBit(r + 1)) {
            long m = matchInRowFrom(r, 0);
            if (m >= 0) {
                return m;
            }
        }
        return -1;
    }

    /** The last match in row-major order, or -1 when there are none. */
    private long lastMatch() {
        for (int r = matchRows.previousSetBit(matchRows.length()); r >= 0; r = matchRows.previousSetBit(r - 1)) {
            long m = matchInRowBefore(r, document().getColumnCount());
            if (m >= 0) {
                return m;
            }
        }
        return -1;
    }

    void findNext() {
        if (total == 0) {
            return;
        }
        currentCell = nextMatch(currentCell);
        currentIndex = (currentIndex + 1) % total;
        selectCurrent();
    }

    void findPrev() {
        if (total == 0) {
            return;
        }
        currentCell = prevMatch(currentCell);
        currentIndex = (currentIndex - 1 + total) % total;
        selectCurrent();
    }

    /**
     * Moves the table's cell selection to {@link #currentCell} and scrolls it into view. The current match
     * is shown by the selection itself (so it is highlighted uniformly for every column type, including
     * the Boolean checkbox cells the renderer does not paint); the other matches stay shaded by the
     * renderer.
     */
    private void selectCurrent() {
        if (currentCell < 0) {
            return;
        }
        lastCurrentCell = currentCell;
        int viewRow = table.convertRowIndexToView(DbfTableSearch.rowOf(currentCell));
        int viewColumn = table.convertColumnIndexToView(DbfTableSearch.columnOf(currentCell));
        if (viewRow >= 0 && viewColumn >= 0) {
            table.changeSelection(viewRow, viewColumn, false, false);
            table.scrollRectToVisible(table.getCellRect(viewRow, viewColumn, true));
        }
        updateCounter();
        table.repaint();
    }

    /** Resets all match state and the bar's counter/field shade (blank query, close, or bad pattern). */
    private void clearMatches() {
        total = 0;
        rowMatchCount = null;
        matchRows.clear();
        matchPattern = null;
        currentCell = -1;
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

    /**
     * Turns the row filter off and restores the full view. Called by the editor when it adds a row: the
     * new row is empty and so matches nothing, and a live filter would hide it — leaving the Add Row
     * click with no visible effect and the row uneditable. Gated on an installed sorter (the bar is open
     * and filtering), so adding a row while the bar is closed leaves the remembered toggle untouched; the
     * filter toggle button reflects the change on its next toolbar update.
     */
    void disableFilter() {
        if (sorter != null) {
            filter = false;
            detachRowSorter();
        }
    }

    private void setFieldError(boolean error) {
        searchField.getTextEditor().setBackground(
                error ? LightColors.RED : (defaultFieldBackground != null ? defaultFieldBackground : UIUtil.getTextFieldBackground()));
    }

    // ---- CellSearchHighlighter -------------------------------------------------------------

    @Override
    public boolean isMatch(int modelRow, int modelColumn) {
        if (matchPattern == null) {
            return false;
        }
        DbfDocument doc = document();
        if (modelRow < 0 || modelRow >= doc.getRowCount() || modelColumn < 0 || modelColumn >= doc.getColumnCount()) {
            return false;
        }
        return DbfTableSearch.matches(matchPattern,
                doc.getRows().get(modelRow).get(modelColumn), doc.getColumn(modelColumn));
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
