package com.poliproger.dbfreader.ui;

import com.intellij.ui.ColorUtil;
import com.intellij.util.ui.UIUtil;
import com.poliproger.dbfreader.model.DbfColumnDef;
import com.poliproger.dbfreader.model.DbfTableModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableModel;
import java.awt.Component;

/**
 * Table-header renderer that shows the field name in the default style and appends its DBF type/size
 * label (e.g. {@code C (254)}) in a muted secondary color, so the type reads as distinct from the
 * column name. Wraps the look-and-feel's own header renderer so native borders, background and
 * sort-arrow handling are preserved; only the label text is restyled (via HTML).
 */
public final class DbfHeaderRenderer implements TableCellRenderer {

    private final TableCellRenderer delegate;

    public DbfHeaderRenderer(@NotNull TableCellRenderer delegate) {
        this.delegate = delegate;
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                   boolean hasFocus, int row, int column) {
        Component component = delegate.getTableCellRendererComponent(
                table, value, isSelected, hasFocus, row, column);
        DbfColumnDef def = columnDef(table, column);
        if (component instanceof JLabel && def != null) {
            String secondary = ColorUtil.toHex(UIUtil.getContextHelpForeground());
            ((JLabel) component).setText("<html>" + escape(def.getName())
                    + "&nbsp;&nbsp;<span style=\"color:#" + secondary + ";\">"
                    + escape(def.typeLabel()) + "</span></html>");
        }
        return component;
    }

    private static @Nullable DbfColumnDef columnDef(@NotNull JTable table, int viewColumn) {
        TableModel m = table.getModel();
        if (!(m instanceof DbfTableModel)) {
            return null;
        }
        int modelColumn = table.convertColumnIndexToModel(viewColumn);
        if (modelColumn < 0 || modelColumn >= m.getColumnCount()) {
            return null;
        }
        return ((DbfTableModel) m).getColumnDef(modelColumn);
    }

    private static @NotNull String escape(@NotNull String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
