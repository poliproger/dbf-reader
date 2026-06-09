package com.poliproger.dbfreader.model;

import org.jetbrains.annotations.NotNull;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory representation of a whole DBF file: its columns, records and the charset used to decode
 * character fields. javadbf cannot edit files in place, so the entire document is held in memory,
 * mutated, and written back as a whole on save.
 */
public final class DbfDocument {

    private final List<DbfColumnDef> columns;
    private final List<DbfRow> rows;
    private Charset charset;
    private final int signature;
    private int deletedRecordCount;

    public DbfDocument(@NotNull List<DbfColumnDef> columns, @NotNull List<DbfRow> rows, @NotNull Charset charset) {
        this(columns, rows, charset, -1);
    }

    /**
     * @param signature the raw first byte of the DBF header (the format/version flag), or -1 if
     *                  unknown. See {@link DbfVersion}.
     */
    public DbfDocument(@NotNull List<DbfColumnDef> columns, @NotNull List<DbfRow> rows,
                       @NotNull Charset charset, int signature) {
        this.columns = new ArrayList<>(columns);
        this.rows = new ArrayList<>(rows);
        this.charset = charset;
        this.signature = signature;
    }

    public @NotNull List<DbfColumnDef> getColumns() {
        return columns;
    }

    public @NotNull List<DbfRow> getRows() {
        return rows;
    }

    public int getColumnCount() {
        return columns.size();
    }

    public int getRowCount() {
        return rows.size();
    }

    public @NotNull DbfColumnDef getColumn(int index) {
        return columns.get(index);
    }

    /** The raw header version byte (0..255), or -1 if it was not captured. */
    public int getSignature() {
        return signature;
    }

    /**
     * Number of records in the source file that are marked as deleted (the {@code *} flag) and were
     * skipped on read. They are invisible to this model, and rewriting the file removes them
     * physically, so the UI warns before the first save while this is non-zero.
     */
    public int getDeletedRecordCount() {
        return deletedRecordCount;
    }

    public void setDeletedRecordCount(int deletedRecordCount) {
        this.deletedRecordCount = deletedRecordCount;
    }

    /** The DBF format variant derived from the header version byte. */
    public @NotNull DbfVersion getVersion() {
        return DbfVersion.fromSignature(signature);
    }

    public @NotNull Charset getCharset() {
        return charset;
    }

    public void setCharset(@NotNull Charset charset) {
        this.charset = charset;
    }

    public void addRow(@NotNull DbfRow row) {
        rows.add(row);
    }

    public void removeRow(int index) {
        rows.remove(index);
    }

    public void addColumn(@NotNull DbfColumnDef column, Object defaultValue) {
        columns.add(column);
        for (DbfRow row : rows) {
            row.insertColumn(columns.size() - 1, defaultValue);
        }
    }

    public void removeColumn(int index) {
        columns.remove(index);
        for (DbfRow row : rows) {
            row.removeColumn(index);
        }
    }
}
