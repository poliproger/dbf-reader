package com.poliproger.dbfreader;

import com.linuxense.javadbf.DBFDataType;
import com.poliproger.dbfreader.model.DbfColumnDef;
import com.poliproger.dbfreader.model.DbfDocument;
import com.poliproger.dbfreader.model.DbfRow;
import com.poliproger.dbfreader.model.DbfTableModel;
import org.junit.Test;

import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * {@link DbfTableModel} edit/structure events. The editor wires "any model event -> mark modified",
 * so the no-op-commit skip here is the foundation of correct modified tracking: clicking through
 * cells (which commits unchanged values) must not fire events, while real edits must.
 */
public class DbfTableModelTest {

    private static DbfTableModel model() {
        List<DbfColumnDef> columns = new ArrayList<>(Arrays.asList(
                new DbfColumnDef("A", DBFDataType.CHARACTER, 5, 0),
                new DbfColumnDef("B", DBFDataType.CHARACTER, 5, 0)));
        List<DbfRow> rows = new ArrayList<>(Arrays.asList(
                new DbfRow(Arrays.asList("a1", "b1")),
                new DbfRow(Arrays.asList(null, "b2"))));
        return new DbfTableModel(new DbfDocument(columns, rows, StandardCharsets.UTF_8));
    }

    /** Counts model events the way the editor does to decide whether the document became modified. */
    private static final class CountingListener implements TableModelListener {
        int events;

        @Override
        public void tableChanged(TableModelEvent e) {
            events++;
        }
    }

    @Test
    public void commitingUnchangedValueFiresNoEvent() {
        DbfTableModel model = model();
        CountingListener listener = new CountingListener();
        model.addTableModelListener(listener);

        model.setValueAt("a1", 0, 0);  // same value already stored
        model.setValueAt(null, 1, 0);  // null over null

        assertEquals("no-op commits must not mark the document modified", 0, listener.events);
        assertEquals("a1", model.getValueAt(0, 0));
    }

    @Test
    public void realEditFiresEventAndUpdatesValue() {
        DbfTableModel model = model();
        CountingListener listener = new CountingListener();
        model.addTableModelListener(listener);

        model.setValueAt("changed", 0, 0);

        assertEquals(1, listener.events);
        assertEquals("changed", model.getValueAt(0, 0));
    }

    @Test
    public void structuralOperationsFireEvents() {
        DbfTableModel model = model();
        CountingListener listener = new CountingListener();
        model.addTableModelListener(listener);

        model.addRow();
        assertEquals(3, model.getRowCount());

        model.addColumn(new DbfColumnDef("C", DBFDataType.CHARACTER, 5, 0), null);
        assertEquals(3, model.getColumnCount());

        model.removeRows(new int[]{0, 2});
        assertEquals(1, model.getRowCount());

        assertEquals("each structural op fires one event", 3, listener.events);
    }

    @Test
    public void logicalColumnReportsBooleanClass() {
        List<DbfColumnDef> columns = new ArrayList<>(Arrays.asList(
                new DbfColumnDef("FLAG", DBFDataType.LOGICAL, 1, 0),
                new DbfColumnDef("TXT", DBFDataType.CHARACTER, 5, 0)));
        List<DbfRow> rows = new ArrayList<>(Arrays.asList(new DbfRow(Arrays.asList(Boolean.TRUE, "x"))));
        DbfTableModel model = new DbfTableModel(new DbfDocument(columns, rows, StandardCharsets.UTF_8));

        assertSame(Boolean.class, model.getColumnClass(0));
        assertSame(Object.class, model.getColumnClass(1));
    }
}