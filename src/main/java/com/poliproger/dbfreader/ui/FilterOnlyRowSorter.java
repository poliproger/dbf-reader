package com.poliproger.dbfreader.ui;

import com.poliproger.dbfreader.model.DbfTableModel;
import org.jetbrains.annotations.NotNull;

import javax.swing.table.TableRowSorter;

/**
 * A {@link TableRowSorter} that filters but never sorts: the view always keeps the model's row
 * order. The DBF editor's row operations and the writer are record-oriented, so a sorted view
 * would silently diverge from what gets saved.
 *
 * <p>Sorting is blocked by overriding {@link #toggleSortOrder} (the header-click entry point)
 * rather than with {@code setSortable(column, false)}, because every
 * {@code fireTableStructureChanged()} (add/edit/delete column) runs
 * {@code DefaultRowSorter.allChanged()}, which silently resets the per-column sortable flags
 * back to {@code true}.
 */
public final class FilterOnlyRowSorter extends TableRowSorter<DbfTableModel> {

    public FilterOnlyRowSorter(@NotNull DbfTableModel model) {
        super(model);
    }

    @Override
    public void toggleSortOrder(int column) {
        // no-op: this sorter only filters
    }
}
