package com.poliproger.dbfreader.ui.cell;

import com.intellij.ui.JBColor;
import com.linuxense.javadbf.DBFDataType;
import com.poliproger.dbfreader.model.DbfColumnDef;
import com.poliproger.dbfreader.model.DbfTableModel;
import com.poliproger.dbfreader.ui.DbfValueFormatter;
import org.jetbrains.annotations.Nullable;

import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Color;
import java.awt.Component;

/**
 * Renders cell values via {@link DbfValueFormatter}, right-aligns numbers, dims the text of
 * read-only (memo/extended) columns and shades cells that match the active search. The <em>current</em>
 * match is not shaded here — it is shown by the table's own cell selection (so it is highlighted
 * uniformly for every column type, including the Boolean checkbox cells this renderer never paints).
 */
public final class DbfCellRenderer extends DefaultTableCellRenderer {

    // Theme-aware so the cell text (kept at the default theme foreground) stays readable on the fill:
    // a pale teal tint in light themes, a deep teal in dark themes. Shared with DbfBooleanCellRenderer
    // so logical-column matches are shaded the same way.
    static final Color MATCH_BG = new JBColor(0xCDE7EC, 0x114957);

    private final @Nullable CellSearchHighlighter highlighter;

    public DbfCellRenderer(@Nullable CellSearchHighlighter highlighter) {
        this.highlighter = highlighter;
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                   boolean hasFocus, int row, int column) {
        DbfTableModel model = (DbfTableModel) table.getModel();
        int modelColumn = table.convertColumnIndexToModel(column);
        DbfColumnDef def = model.getColumnDef(modelColumn);

        String text = DbfValueFormatter.format(value, def);
        Component c = super.getTableCellRendererComponent(table, text, isSelected, hasFocus, row, column);

        DBFDataType type = def.getType();
        boolean numeric = type == DBFDataType.NUMERIC || type == DBFDataType.FLOATING_POINT
                || type == DBFDataType.DOUBLE || type == DBFDataType.LONG || type == DBFDataType.CURRENCY;
        setHorizontalAlignment(numeric ? SwingConstants.RIGHT : SwingConstants.LEFT);

        if (!def.isWritable() && !isSelected) {
            c.setForeground(JBColor.GRAY);
        }
        // Reset the background on EVERY unselected cell, not just the matches. DefaultTableCellRenderer
        // is a single reused "rubber stamp" whose setBackground() also records the colour as its
        // `unselectedBackground`; shading only the matches would smear that colour onto every following
        // cell. Selected cells keep the selection background super() already applied.
        if (!isSelected) {
            c.setBackground(searchBackground(table.convertRowIndexToModel(row), modelColumn, table.getBackground()));
        }
        return c;
    }

    private Color searchBackground(int modelRow, int modelColumn, Color normal) {
        if (highlighter != null && highlighter.isMatch(modelRow, modelColumn)) {
            return MATCH_BG;
        }
        return normal;
    }
}
