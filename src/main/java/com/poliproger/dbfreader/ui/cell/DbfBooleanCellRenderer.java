package com.poliproger.dbfreader.ui.cell;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JTable;
import javax.swing.table.TableCellRenderer;
import java.awt.Component;

/**
 * Renderer for LOGICAL columns: delegates to the platform's default Boolean checkbox renderer (so the
 * checkbox, selection colours and focus border stay native) and, for an unselected cell that matches
 * the active search, repaints its background with the same shade {@link DbfCellRenderer} uses. Without
 * this, logical-column matches would be counted and navigable but never highlighted. The current match
 * is shown by the table selection, which the delegate already paints.
 */
public final class DbfBooleanCellRenderer implements TableCellRenderer {

    private final @NotNull TableCellRenderer delegate;
    private final @Nullable CellSearchHighlighter highlighter;

    public DbfBooleanCellRenderer(@NotNull TableCellRenderer delegate, @Nullable CellSearchHighlighter highlighter) {
        this.delegate = delegate;
        this.highlighter = highlighter;
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                   boolean hasFocus, int row, int column) {
        Component c = delegate.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
        // The delegate resets the background on every call, so shading a match here never smears onto
        // the next cell. Selected cells (incl. the current match) keep the delegate's selection colour.
        if (!isSelected && highlighter != null
                && highlighter.isMatch(table.convertRowIndexToModel(row), table.convertColumnIndexToModel(column))) {
            c.setBackground(DbfCellRenderer.MATCH_BG);
        }
        return c;
    }
}
