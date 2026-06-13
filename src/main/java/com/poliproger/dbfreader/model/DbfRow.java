package com.poliproger.dbfreader.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A single DBF record. Cell values are stored positionally in an {@code Object[]} aligned with the
 * document's column list. A plain array (rather than a {@code List}) avoids the per-row {@code
 * ArrayList} overhead — material when a file has millions of rows; add/remove-column rebuild the
 * array, which is no more costly than the equivalent {@code ArrayList} shift.
 */
public final class DbfRow {

    private Object[] values;

    /** Copies {@code values} into the row. */
    public DbfRow(@NotNull List<Object> values) {
        this.values = values.toArray();
    }

    private DbfRow(Object @NotNull [] owned) {
        this.values = owned;
    }

    /**
     * Wraps {@code record} directly, without copying — for the reader, which hands over a fresh array
     * per record from javadbf that nothing else retains.
     */
    public static @NotNull DbfRow ofRecord(Object @NotNull [] record) {
        return new DbfRow(record);
    }

    public static @NotNull DbfRow empty(int columnCount) {
        return new DbfRow(new Object[columnCount]);
    }

    public @Nullable Object get(int columnIndex) {
        return values[columnIndex];
    }

    public void set(int columnIndex, @Nullable Object value) {
        values[columnIndex] = value;
    }

    public void insertColumn(int columnIndex, @Nullable Object value) {
        Object[] next = new Object[values.length + 1];
        System.arraycopy(values, 0, next, 0, columnIndex);
        next[columnIndex] = value;
        System.arraycopy(values, columnIndex, next, columnIndex + 1, values.length - columnIndex);
        values = next;
    }

    public void removeColumn(int columnIndex) {
        Object[] next = new Object[values.length - 1];
        System.arraycopy(values, 0, next, 0, columnIndex);
        System.arraycopy(values, columnIndex + 1, next, columnIndex, values.length - columnIndex - 1);
        values = next;
    }

    public int size() {
        return values.length;
    }
}
