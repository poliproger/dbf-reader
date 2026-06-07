package com.poliproger.dbfreader.ui.cell;

import com.linuxense.javadbf.DBFDataType;
import com.poliproger.dbfreader.model.DbfColumnDef;
import com.poliproger.dbfreader.model.DbfTableModel;
import com.poliproger.dbfreader.ui.DbfValueFormatter;

import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Component;

/**
 * Renders cell values via {@link DbfValueFormatter}, right-aligns numbers and dims the text of
 * read-only (memo/extended) columns.
 */
public final class DbfCellRenderer extends DefaultTableCellRenderer {

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
            c.setForeground(com.intellij.ui.JBColor.GRAY);
        }
        return c;
    }
}
