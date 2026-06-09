package com.poliproger.dbfreader.model;

import com.linuxense.javadbf.DBFDataType;
import org.jetbrains.annotations.NotNull;

import javax.swing.table.AbstractTableModel;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Swing table model backed by a {@link DbfDocument}. Cell edits and structural changes
 * (rows/columns) mutate the underlying document and fire the corresponding table events.
 */
public final class DbfTableModel extends AbstractTableModel {

    private final DbfDocument document;

    public DbfTableModel(@NotNull DbfDocument document) {
        this.document = document;
    }

    public @NotNull DbfDocument getDocument() {
        return document;
    }

    public @NotNull DbfColumnDef getColumnDef(int columnIndex) {
        return document.getColumn(columnIndex);
    }

    @Override
    public int getRowCount() {
        return document.getRowCount();
    }

    @Override
    public int getColumnCount() {
        return document.getColumnCount();
    }

    @Override
    public String getColumnName(int columnIndex) {
        return document.getColumn(columnIndex).getName();
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        if (document.getColumn(columnIndex).getType() == DBFDataType.LOGICAL) {
            return Boolean.class;
        }
        return Object.class;
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return document.getColumn(columnIndex).isWritable();
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        return document.getRows().get(rowIndex).get(columnIndex);
    }

    @Override
    public void setValueAt(Object value, int rowIndex, int columnIndex) {
        // Skip no-op commits: a cell edit always commits on focus loss/navigation, even when the user
        // changed nothing, so firing unconditionally would mark the file modified just from clicking
        // through cells.
        Object current = document.getRows().get(rowIndex).get(columnIndex);
        if (Objects.equals(current, value)) {
            return;
        }
        document.getRows().get(rowIndex).set(columnIndex, value);
        fireTableCellUpdated(rowIndex, columnIndex);
    }

    // ---- structural operations -------------------------------------------------------------

    public void addRow() {
        document.addRow(DbfRow.empty(document.getColumnCount()));
        int newIndex = document.getRowCount() - 1;
        fireTableRowsInserted(newIndex, newIndex);
    }

    /**
     * Deletes the given rows (model indices). Indices may be in any order.
     */
    public void removeRows(int @NotNull [] modelRows) {
        int[] sorted = modelRows.clone();
        Arrays.sort(sorted);
        for (int i = sorted.length - 1; i >= 0; i--) {
            document.removeRow(sorted[i]);
        }
        fireTableDataChanged();
    }

    public void addColumn(@NotNull DbfColumnDef column, Object defaultValue) {
        document.addColumn(column, defaultValue);
        fireTableStructureChanged();
    }

    public void removeColumn(int columnIndex) {
        document.removeColumn(columnIndex);
        fireTableStructureChanged();
    }

    /**
     * Replaces the definition of an existing column and updates the stored values of that column
     * (e.g. after a type/size change the caller passes already-converted values).
     */
    public void updateColumn(int columnIndex, @NotNull DbfColumnDef newDef, @NotNull List<Object> convertedValues) {
        DbfColumnDef target = document.getColumn(columnIndex);
        target.setName(newDef.getName());
        target.setType(newDef.getType());
        target.setLength(newDef.getLength());
        target.setDecimalCount(newDef.getDecimalCount());
        for (int r = 0; r < document.getRowCount(); r++) {
            document.getRows().get(r).set(columnIndex, convertedValues.get(r));
        }
        fireTableStructureChanged();
    }
}
