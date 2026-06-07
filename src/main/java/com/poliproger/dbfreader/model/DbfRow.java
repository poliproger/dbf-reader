package com.poliproger.dbfreader.model;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A single DBF record. Cell values are stored positionally, aligned with the document's column list.
 * Using a {@link List} (rather than a fixed array) keeps add/remove-column operations cheap.
 */
public final class DbfRow {

    private final List<Object> values;

    public DbfRow(List<Object> values) {
        this.values = new ArrayList<>(values);
    }

    public static DbfRow empty(int columnCount) {
        List<Object> values = new ArrayList<>(columnCount);
        for (int i = 0; i < columnCount; i++) {
            values.add(null);
        }
        return new DbfRow(values);
    }

    public @Nullable Object get(int columnIndex) {
        return values.get(columnIndex);
    }

    public void set(int columnIndex, @Nullable Object value) {
        values.set(columnIndex, value);
    }

    public void insertColumn(int columnIndex, @Nullable Object value) {
        values.add(columnIndex, value);
    }

    public void removeColumn(int columnIndex) {
        values.remove(columnIndex);
    }

    public int size() {
        return values.size();
    }
}
