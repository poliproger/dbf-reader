package com.poliproger.dbfreader.ui.cell;

/**
 * Lets {@link DbfCellRenderer} ask whether a cell (in model coordinates) is a search hit, so matching
 * cells can be shaded. Implemented by the editor's search controller; kept as a tiny interface here so
 * the renderer (package {@code ui.cell}) needn't depend on the {@code editor} package. The current
 * match is not asked about here — it is shown by the table's own selection.
 */
public interface CellSearchHighlighter {

    /** @return whether the cell at the given model row/column matches the active search. */
    boolean isMatch(int modelRow, int modelColumn);
}
