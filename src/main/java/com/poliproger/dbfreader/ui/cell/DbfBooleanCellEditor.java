package com.poliproger.dbfreader.ui.cell;

import javax.swing.DefaultCellEditor;
import javax.swing.JCheckBox;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.EventObject;

/**
 * Cell editor for LOGICAL columns. Behaves like the platform's default Boolean checkbox editor
 * ({@code DefaultCellEditor} over a centered {@link JCheckBox}) but only starts editing — and so toggles
 * the value — when a mouse click lands on the checkbox glyph itself, not anywhere in the cell. The
 * default editor uses {@code clickCountToStart = 1}, so a single click anywhere in the cell flips the
 * value; merely selecting a logical cell (to navigate, delete its row or select its column) would change
 * the data. Non-mouse starts (F2, programmatic) stay enabled, so the value is still toggleable without
 * the mouse.
 */
public final class DbfBooleanCellEditor extends DefaultCellEditor {

    public DbfBooleanCellEditor() {
        super(new JCheckBox());
        // Match the default Boolean renderer (JTable.BooleanRenderer), which centres the box; the hit
        // test below assumes a centered glyph.
        ((JCheckBox) getComponent()).setHorizontalAlignment(SwingConstants.CENTER);
    }

    @Override
    public boolean isCellEditable(EventObject anEvent) {
        if (!(anEvent instanceof MouseEvent)) {
            // Keyboard/programmatic starts behave like the default editor (F2 then Space still toggles).
            return super.isCellEditable(anEvent);
        }
        MouseEvent e = (MouseEvent) anEvent;
        if (!(e.getSource() instanceof JTable)) {
            return false;
        }
        JTable table = (JTable) e.getSource();
        int row = table.rowAtPoint(e.getPoint());
        int column = table.columnAtPoint(e.getPoint());
        if (row < 0 || column < 0) {
            return false;
        }
        return checkBoxBounds(table.getCellRect(row, column, false)).contains(e.getPoint());
    }

    /**
     * The clickable box: a square centred in the cell, sized to the smaller of the cell's width/height.
     * Deliberately independent of the look-and-feel's exact glyph metrics — a centred square tracks where
     * the renderer paints the box closely enough, and is forgiving to aim at, while clicks on a wide
     * cell's empty margins fall outside it and only select the cell.
     */
    private static Rectangle checkBoxBounds(Rectangle cell) {
        int side = Math.min(cell.width, cell.height);
        int x = cell.x + (cell.width - side) / 2;
        int y = cell.y + (cell.height - side) / 2;
        return new Rectangle(x, y, side, side);
    }
}
