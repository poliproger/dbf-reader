package com.poliproger.dbfreader.ui;

import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.JTable;
import javax.swing.JViewport;
import javax.swing.RowSorter;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.RowSorterEvent;
import javax.swing.event.RowSorterListener;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import javax.swing.table.TableModel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

/**
 * A single-column table that shows 1-based row numbers, meant to be installed as the
 * {@code rowHeaderView} of the scroll pane that holds the main table. As a row header it scrolls
 * vertically with the content but stays fixed during horizontal scrolling, like the column header.
 *
 * <p>It mirrors the main table's row count, per-row height and (shared) selection model, so the
 * numbers stay aligned and the current row is highlighted. Styled like the column header.
 *
 * <p>Adapted from Rob Camick's well-known {@code RowNumberTable} pattern.
 */
public final class RowNumberTable extends JBTable
        implements ChangeListener, PropertyChangeListener, TableModelListener, RowSorterListener {

    private final JTable main;
    private TableModel observedModel;
    private RowSorter<?> observedSorter;

    public RowNumberTable(JTable main) {
        this.main = main;
        main.addPropertyChangeListener(this);
        observedModel = main.getModel();
        observedModel.addTableModelListener(this);
        observedSorter = main.getRowSorter();
        if (observedSorter != null) {
            observedSorter.addRowSorterListener(this);
        }

        setFocusable(false);
        // The main table already shows "Nothing to show"; without this the gutter paints its own
        // copy, clipped to the narrow column width.
        getEmptyText().setText("");
        setAutoCreateColumnsFromModel(false);
        setSelectionModel(main.getSelectionModel());
        setRowSelectionAllowed(true);
        getTableHeader().setReorderingAllowed(false);
        setStriped(false);

        TableColumn column = new TableColumn();
        column.setHeaderValue("");
        column.setCellRenderer(new RowNumberRenderer());
        addColumn(column);

        adjustWidth();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        Component parent = getParent();
        if (parent instanceof JViewport viewport) {
            viewport.addChangeListener(this);
        }
    }

    // ---- track the main table -------------------------------------------------------------

    @Override
    public int getRowCount() {
        // The superclass constructor calls setModel() -> tableChanged() before `main` is assigned.
        return main == null ? 0 : main.getRowCount();
    }

    @Override
    public int getRowHeight() {
        // Mirror the main table's height directly: each JBTable computes its own row height from its
        // renderers, and even a 1px difference accumulates into a visible drift on long scrolls.
        // rowAtPoint() and the total-height math use this flat value, so it must match exactly.
        return main == null ? super.getRowHeight() : main.getRowHeight();
    }

    @Override
    public int getRowHeight(int row) {
        return main == null ? super.getRowHeight(row) : main.getRowHeight(row);
    }

    @Override
    public Object getValueAt(int row, int column) {
        // Show the original (model) record number, so a filtered view keeps each row's real index
        // instead of renumbering the visible rows 1..N.
        int modelRow = main == null ? row : main.convertRowIndexToModel(row);
        return Integer.toString(modelRow + 1);
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        return false;
    }

    // ---- listeners ------------------------------------------------------------------------

    /** Keep the row header's vertical position in sync with the main table's viewport. */
    @Override
    public void stateChanged(ChangeEvent e) {
        repaint();
    }

    @Override
    public void propertyChange(PropertyChangeEvent e) {
        switch (e.getPropertyName()) {
            case "selectionModel" -> setSelectionModel(main.getSelectionModel());
            case "rowHeight" -> {
                revalidate();
                repaint();
            }
            case "model" -> {
                observedModel.removeTableModelListener(this);
                observedModel = main.getModel();
                observedModel.addTableModelListener(this);
                // The new model usually has a different row count (e.g. the real document swapped in
                // after the background load replaces the empty initial table), so resize the gutter to
                // fit the widest number — tableChanged() won't fire for this swap, it listened to the
                // old model object.
                adjustWidth();
                revalidate();
                repaint();
            }
            case "rowSorter" -> {
                if (observedSorter != null) {
                    observedSorter.removeRowSorterListener(this);
                }
                observedSorter = main.getRowSorter();
                if (observedSorter != null) {
                    observedSorter.addRowSorterListener(this);
                }
                adjustWidth();
                revalidate();
                repaint();
            }
            default -> { /* ignore */ }
        }
    }

    /** The filter (or any sort) changed the set/order of visible rows: renumber and resize the gutter. */
    @Override
    public void sorterChanged(RowSorterEvent e) {
        if (main == null || getColumnCount() == 0) {
            return;
        }
        adjustWidth();
        revalidate();
        repaint();
    }

    @Override
    public void tableChanged(TableModelEvent e) {
        // May fire from the superclass constructor (before `main`/the gutter column exist).
        if (main == null || getColumnCount() == 0) {
            return;
        }
        adjustWidth();
        revalidate();
        repaint();
    }

    /**
     * Sizes the column to fit the widest row number. Based on the total (model) record count rather
     * than the visible count, so the original numbers always fit and the width stays stable while
     * filtering.
     */
    private void adjustWidth() {
        int total = main == null ? getRowCount() : main.getModel().getRowCount();
        int digits = Integer.toString(Math.max(total, 1)).length();
        FontMetrics fm = getFontMetrics(getFont());
        int width = fm.charWidth('0') * digits + JBUI.scale(12);
        TableColumn column = getColumnModel().getColumn(0);
        column.setPreferredWidth(width);
        column.setMinWidth(width);
        column.setMaxWidth(width);
        setPreferredScrollableViewportSize(new Dimension(width, 0));
    }

    /** Renders each number using the column-header look, bolding the selected row. */
    private static final class RowNumberRenderer extends DefaultTableCellRenderer {

        RowNumberRenderer() {
            setHorizontalAlignment(SwingConstants.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, false, false, row, column);
            JTableHeader header = table == null ? null : table.getTableHeader();
            if (header != null) {
                setForeground(header.getForeground());
                setFont(header.getFont());
            }
            setBackground(UIUtil.getPanelBackground());
            if (isSelected) {
                setFont(getFont().deriveFont(Font.BOLD));
            }
            setBorder(UIManager.getBorder("TableHeader.cellBorder"));
            return this;
        }
    }
}
