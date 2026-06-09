package com.poliproger.dbfreader.ui;

import com.poliproger.dbfreader.model.DbfColumnDef;
import com.poliproger.dbfreader.model.DbfDocument;
import com.poliproger.dbfreader.model.DbfRow;
import org.jetbrains.annotations.NotNull;

/**
 * Pure (Swing-free) export of selected cells to tab-separated text for the clipboard. Each value is
 * rendered with {@link DbfValueFormatter} so the copied text matches what the table shows (dates as
 * {@code yyyy-MM-dd}, numbers at their decimal count, logicals as {@code true}/{@code false}, null as
 * an empty field) — the same display form {@link DbfTableSearch} matches against. Rows are separated
 * by {@code \n} and columns by a tab.
 *
 * <p>Values are emitted verbatim, with no CSV/TSV quoting: copying a value and pasting it into a plain
 * text field (e.g. the search box) yields it unchanged, and a quote inside a value (e.g. {@code 12"})
 * stays literal. The one thing this does not guard against is a value that itself contains a tab or
 * newline — it would spill into the neighbouring cell/row when pasted into a spreadsheet — which is
 * rare in DBF character data (multi-line text lives in memo fields, which are not loaded here).
 */
public final class DbfTsvExporter {

    private DbfTsvExporter() {
    }

    /**
     * Builds the TSV for the cells at the cross product of {@code modelRows} × {@code modelCols}. Both
     * arrays are emitted in the order given — the editor passes them in the on-screen view order, so a
     * filtered or column-reordered table copies exactly what the user sees.
     */
    public static @NotNull String toTsv(@NotNull DbfDocument doc, int @NotNull [] modelRows, int @NotNull [] modelCols) {
        // Column defs don't change across rows; fetch the selected ones once instead of per cell.
        DbfColumnDef[] defs = new DbfColumnDef[modelCols.length];
        for (int c = 0; c < modelCols.length; c++) {
            defs[c] = doc.getColumn(modelCols[c]);
        }
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < modelRows.length; r++) {
            if (r > 0) {
                sb.append('\n');
            }
            DbfRow row = doc.getRows().get(modelRows[r]);
            for (int c = 0; c < modelCols.length; c++) {
                if (c > 0) {
                    sb.append('\t');
                }
                sb.append(DbfValueFormatter.format(row.get(modelCols[c]), defs[c]));
            }
        }
        return sb.toString();
    }
}