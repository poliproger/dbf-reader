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
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.LightColors;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.Alarm;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.poliproger.dbfreader.DbfBundle;
import com.poliproger.dbfreader.model.DbfTableModel;
import com.poliproger.dbfreader.ui.DbfTableSearch;
import com.poliproger.dbfreader.ui.cell.CellSearchHighlighter;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.RowFilter;
import javax.swing.event.DocumentEvent;
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

    private final Color defaultFieldBackground;

    DbfSearchController(@NotNull JBTable table, @NotNull Disposable parent) {
        this.table = table;
        this.alarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, parent);
        this.defaultFieldBackground = searchField.getTextEditor().getBackground();
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
        bar.setVisible(false);
        bar.revalidate();
        bar.repaint();
        // Restore the full, unfiltered view; the toggle state is kept for the next activation.
        detachRowSorter();
        clearMatches();
        table.repaint();
        table.requestFocusInWindow();
    }

    /** The table model changed (edit, row/column op, reload): refresh matches if the bar is open. */
    void onModelChanged() {
        if (bar.isVisible()) {
            // Refresh the highlights/counter without yanking the selection away from what the user edits.
            scheduleRecompute(false);
        }
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
     */
    private void recompute(boolean moveSelection) {
        if (!bar.isVisible()) {
            return;
        }
        DbfTableModel model = (DbfTableModel) table.getModel();
        DbfTableSearch.Query query = new DbfTableSearch.Query(
                searchField.getText(), matchCase, regex, wholeWords);
        DbfTableSearch.Result result = DbfTableSearch.find(model.getDocument(), query);

        matches = result.matches();
        matchRows.clear();
        for (long cell : matches) {
            matchRows.set(DbfTableSearch.rowOf(cell));
        }
        applyFilter();

        if (matches.length == 0) {
            currentIndex = -1;
            boolean blank = query.text().isEmpty();
            setFieldError(!blank);
            countLabel.setText(blank ? "" : DbfBundle.message(
                    result.badPattern() ? "editor.search.badRegex" : "editor.search.noMatches"));
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
            // Filtering only: keep the model order, since clicking a header to sort would clash with the
            // editor's record-oriented row operations. Sorting is blocked by overriding toggleSortOrder
            // (the header-click entry point) rather than with setSortable(false), because every
            // fireTableStructureChanged() (add/edit/delete column) runs DefaultRowSorter.allChanged(),
            // which silently resets the per-column sortable flags back to true.
            sorter = new TableRowSorter<>(model) {
                @Override
                public void toggleSortOrder(int column) {
                    // no-op: this sorter only filters
                }
            };
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
