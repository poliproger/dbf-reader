package com.poliproger.dbfreader.editor;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.speedSearch.SpeedSearchUtil;
import com.intellij.ui.table.JBTable;
import com.poliproger.dbfreader.DbfBundle;
import com.poliproger.dbfreader.model.DbfColumnDef;
import com.poliproger.dbfreader.model.DbfTableModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JList;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * "Go to Column" navigation, bound to the IDE File Structure shortcut (e.g. Cmd-F12) — the analogue of
 * the Database plugin's column structure popup. Opens a speed-search list of every field; choosing one
 * selects that column in the table and scrolls it into view.
 */
final class DbfColumnNavigator {

    private final JBTable table;

    DbfColumnNavigator(@NotNull JBTable table) {
        this.table = table;
    }

    /** One row in the popup: a field paired with its model-column index (used for navigation). */
    private record ColumnEntry(int modelIndex, @NotNull DbfColumnDef def) {
    }

    void show(@NotNull DataContext context) {
        if (!(table.getModel() instanceof DbfTableModel model) || model.getColumnCount() == 0) {
            return;
        }
        // Build entries in on-screen (view) order so the list matches the table's left-to-right layout,
        // honouring any column reordering; each entry keeps its model index for navigation.
        List<ColumnEntry> entries = new ArrayList<>();
        ColumnEntry preselect = null;
        int selectedModelColumn = table.getSelectedColumn() >= 0
                ? table.convertColumnIndexToModel(table.getSelectedColumn()) : -1;
        for (int viewCol = 0; viewCol < table.getColumnModel().getColumnCount(); viewCol++) {
            int modelCol = table.convertColumnIndexToModel(viewCol);
            ColumnEntry entry = new ColumnEntry(modelCol, model.getColumnDef(modelCol));
            entries.add(entry);
            if (modelCol == selectedModelColumn) {
                preselect = entry;
            }
        }

        JBPopup popup = JBPopupFactory.getInstance()
                .createPopupChooserBuilder(entries)
                .setTitle(DbfBundle.message("editor.columns.popup.title"))
                .setRenderer(new ColumnEntryRenderer())
                .setNamerForFiltering(entry -> entry.def().getName())
                .setItemChosenCallback(this::navigateTo)
                .setSelectedValue(preselect, true)
                .createPopup();
        popup.showInBestPositionFor(context);
    }

    /** Selects the chosen field's column and scrolls it into view, keeping the current row. */
    private void navigateTo(@NotNull ColumnEntry entry) {
        int viewColumn = table.convertColumnIndexToView(entry.modelIndex());
        if (viewColumn < 0) {
            return;
        }
        int viewRow = table.getSelectedRow();
        if (viewRow < 0 && table.getRowCount() > 0) {
            viewRow = 0;
        }
        if (viewRow >= 0) {
            table.changeSelection(viewRow, viewColumn, false, false);
        } else {
            // No rows to select a cell in: select just the column so the column-scoped toolbar actions
            // (Edit/Delete Column) enable, then scroll the header into view.
            table.getColumnModel().getSelectionModel().setSelectionInterval(viewColumn, viewColumn);
        }
        table.scrollRectToVisible(table.getCellRect(Math.max(viewRow, 0), viewColumn, true));
        table.requestFocusInWindow();
    }

    /** Renders a field as its name plus the muted DBF type/size label, with speed-search highlighting. */
    private static final class ColumnEntryRenderer extends ColoredListCellRenderer<ColumnEntry> {
        @Override
        protected void customizeCellRenderer(@NotNull JList<? extends ColumnEntry> list, @Nullable ColumnEntry entry,
                                             int index, boolean selected, boolean hasFocus) {
            if (entry == null) {
                return;
            }
            setIcon(AllIcons.Nodes.DataColumn);
            append(entry.def().getName());
            SpeedSearchUtil.applySpeedSearchHighlighting(list, this, true, selected);
            append("  " + entry.def().typeLabel(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
        }
    }
}
