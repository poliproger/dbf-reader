package com.poliproger.dbfreader.ui.cell;

import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.table.TableCellRenderer;
import java.awt.Component;

/**
 * Renderer for LOGICAL columns: delegates to the platform's default Boolean checkbox renderer (so the
 * checkbox glyph and selection colours stay native) and, for an unselected cell that matches the active
 * search, repaints its background with the same shade {@link DbfCellRenderer} uses. Without this,
 * logical-column matches would be counted and navigable but never highlighted. The current match is
 * shown by the table selection, which the delegate already paints.
 *
 * <p>The delegate's own focus indication is overridden with the standard {@code
 * Table.focusCellHighlightBorder} (the same border {@link javax.swing.table.DefaultTableCellRenderer}
 * draws) so a focused logical cell gets the identical thin rectangular frame as every other column,
 * instead of the checkbox renderer's softer/rounded default.
 */
public final class DbfBooleanCellRenderer implements TableCellRenderer {

    // 1px empty border for the unfocused state, matching DefaultTableCellRenderer's no-focus inset, so
    // the checkbox does not shift when the focus border appears or disappears.
    private static final Border NO_FOCUS_BORDER = JBUI.Borders.empty(1);

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
        // Draw the same focus frame as DefaultTableCellRenderer (which the other column types use) so
        // selection looks uniform across the whole table, instead of the checkbox renderer's default.
        if (c instanceof JComponent jc) {
            jc.setBorder(hasFocus ? focusBorder(isSelected) : NO_FOCUS_BORDER);
        }
        return c;
    }

    private static Border focusBorder(boolean isSelected) {
        Border border = isSelected ? UIManager.getBorder("Table.focusSelectedCellHighlightBorder") : null;
        if (border == null) {
            border = UIManager.getBorder("Table.focusCellHighlightBorder");
        }
        return border != null ? border : NO_FOCUS_BORDER;
    }
}
