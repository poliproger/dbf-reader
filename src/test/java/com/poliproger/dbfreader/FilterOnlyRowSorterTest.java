package com.poliproger.dbfreader;

import com.linuxense.javadbf.DBFDataType;
import com.poliproger.dbfreader.model.DbfColumnDef;
import com.poliproger.dbfreader.model.DbfDocument;
import com.poliproger.dbfreader.model.DbfRow;
import com.poliproger.dbfreader.model.DbfTableModel;
import com.poliproger.dbfreader.ui.FilterOnlyRowSorter;
import org.junit.Test;

import javax.swing.JTable;
import javax.swing.RowFilter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The Filter Rows sorter must filter and never sort: the file is written in model order, so a
 * sorted view would silently diverge from what gets saved. The regression case is a header click
 * <em>after</em> a structural change (add/edit/delete column): {@code fireTableStructureChanged()}
 * runs {@code DefaultRowSorter.allChanged()}, which resets per-column {@code setSortable} flags —
 * the original B3 bug — so sorting has to stay blocked independently of those flags.
 */
public class FilterOnlyRowSorterTest {

    private static DbfTableModel model() {
        List<DbfColumnDef> columns = new ArrayList<>(Collections.singletonList(
                new DbfColumnDef("NAME", DBFDataType.CHARACTER, 10, 0)));
        List<DbfRow> rows = new ArrayList<>(Arrays.asList(
                new DbfRow(Collections.singletonList("charlie")),
                new DbfRow(Collections.singletonList("alpha")),
                new DbfRow(Collections.singletonList("bravo"))));
        return new DbfTableModel(new DbfDocument(columns, rows, StandardCharsets.UTF_8));
    }

    /** A table wired the way the search controller wires it: model first, then the sorter. */
    private static JTable table(DbfTableModel model, FilterOnlyRowSorter sorter) {
        JTable table = new JTable(model);
        table.setRowSorter(sorter);
        return table;
    }

    private static void assertModelOrder(JTable table) {
        for (int row = 0; row < table.getRowCount(); row++) {
            assertEquals("view order must equal model order", row, table.convertRowIndexToView(row));
        }
    }

    @Test
    public void headerClickDoesNotSort() {
        DbfTableModel model = model();
        FilterOnlyRowSorter sorter = new FilterOnlyRowSorter(model);
        JTable table = table(model, sorter);

        sorter.toggleSortOrder(0);

        assertTrue(sorter.getSortKeys().isEmpty());
        assertModelOrder(table);
    }

    @Test
    public void headerClickDoesNotSortAfterStructuralChange() {
        DbfTableModel model = model();
        FilterOnlyRowSorter sorter = new FilterOnlyRowSorter(model);
        JTable table = table(model, sorter);

        // fireTableStructureChanged -> DefaultRowSorter.allChanged(), which silently resets the
        // per-column sortable flags; the sorter must stay unsortable regardless.
        model.addColumn(new DbfColumnDef("AGE", DBFDataType.NUMERIC, 3, 0), null);
        sorter.toggleSortOrder(0);

        assertTrue(sorter.getSortKeys().isEmpty());
        assertModelOrder(table);
    }

    @Test
    public void rowFilterStillApplies() {
        DbfTableModel model = model();
        FilterOnlyRowSorter sorter = new FilterOnlyRowSorter(model);
        JTable table = table(model, sorter);

        sorter.setRowFilter(new RowFilter<DbfTableModel, Integer>() {
            @Override
            public boolean include(Entry<? extends DbfTableModel, ? extends Integer> entry) {
                return entry.getStringValue(0).startsWith("b");
            }
        });

        assertEquals(1, table.getRowCount());
        assertEquals("bravo", table.getValueAt(0, 0));
    }
}
