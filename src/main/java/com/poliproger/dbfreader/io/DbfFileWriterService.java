package com.poliproger.dbfreader.io;

import com.linuxense.javadbf.DBFCharsetHelper;
import com.linuxense.javadbf.DBFDataType;
import com.linuxense.javadbf.DBFField;
import com.linuxense.javadbf.DBFWriter;
import com.poliproger.dbfreader.model.DbfColumnDef;
import com.poliproger.dbfreader.model.DbfDocument;
import com.poliproger.dbfreader.model.DbfRow;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Serializes an in-memory {@link DbfDocument} back into DBF bytes via javadbf's {@link DBFWriter}.
 * Because javadbf can only write the C, N, F, L and D types, documents that still contain
 * memo/extended (read-only) columns cannot be saved — see {@link #hasUnwritableColumns}.
 */
public final class DbfFileWriterService {

    private DbfFileWriterService() {
    }

    public static boolean hasUnwritableColumns(@NotNull DbfDocument document) {
        for (DbfColumnDef column : document.getColumns()) {
            if (!column.isWritable()) {
                return true;
            }
        }
        return false;
    }

    public static byte @NotNull [] write(@NotNull DbfDocument document) {
        // javadbf cannot write a document with no fields (DBFWriter.setFields rejects an empty array).
        // Save it as an empty file instead — the same bytes a freshly created file has, which the
        // reader already opens as a blank document, so the round-trip stays consistent.
        if (document.getColumnCount() == 0) {
            return new byte[0];
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        // An OutputStream-backed DBFWriter buffers every record in memory and flushes them on close():
        // fine for the tests, but for the editor's save path see write(document, File), which streams.
        serialize(document, new DBFWriter(bos, document.getCharset()));
        return bos.toByteArray();
    }

    /**
     * Streaming variant of {@link #write(DbfDocument)} that serializes straight into {@code target}
     * instead of returning the bytes. javadbf's {@link File}-backed {@link DBFWriter} appends each
     * record to disk via a {@code RandomAccessFile} as it is added and backfills the header (record
     * count) on close, so the whole serialized file never sits in the heap — unlike the in-memory
     * {@code ByteArrayOutputStream} of the byte-array overload (plus its {@code toByteArray} copy and
     * javadbf's own buffered-record list). The editor uses this for large {@code .dbf} files and then
     * streams {@code target} into the VFS, keeping peak heap close to the in-memory document alone.
     *
     * <p>{@code target} must be an existing, empty file: the {@code File} constructor reads an existing
     * header when the file is non-empty (append mode), and {@code setFields} then rejects the write.
     */
    public static void write(@NotNull DbfDocument document, @NotNull File target) throws IOException {
        if (document.getColumnCount() == 0) {
            // Empty document -> empty file (mirrors the byte[0] case). A fresh temp file is already
            // zero-length; truncate defensively so a reused target cannot leave stale bytes.
            new FileOutputStream(target).close();
            return;
        }
        if (!canStream(document.getCharset())) {
            // The File-backed DBFWriter rejects a charset with no DBF language-driver code (throwing in
            // its constructor before any record is written), while the OutputStream-backed writer accepts
            // it and just records language-driver 0. Fall back to the in-memory path so such a file saves
            // exactly as the byte[] overload does, instead of failing the save — at the cost of buffering
            // it in the heap, acceptable for these uncommon charsets (see canStream).
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(write(document));
            }
            return;
        }
        serialize(document, new DBFWriter(target, document.getCharset()));
    }

    /**
     * Whether the streaming (File-backed) {@link DBFWriter} accepts {@code charset}. Its constructor
     * throws for a charset that has no DBF language-driver code unless it is UTF-8; the
     * OutputStream-backed writer has no such check. Mirrors that constructor condition (via javadbf's own
     * {@link DBFCharsetHelper#getDBFCodeForCharset}) so the editor's save can pick the compatible path
     * rather than fail on, e.g., KOI8-R or a non-UTF-8 platform default charset for a file that declares
     * no code page.
     */
    private static boolean canStream(@NotNull Charset charset) {
        return DBFCharsetHelper.getDBFCodeForCharset(charset) != 0
                || StandardCharsets.UTF_8.equals(charset);
    }

    /** Sets the fields and writes every row through {@code writer}, then closes it. */
    private static void serialize(@NotNull DbfDocument document, @NotNull DBFWriter writer) {
        try {
            int columnCount = document.getColumnCount();
            DBFField[] fields = new DBFField[columnCount];
            for (int i = 0; i < columnCount; i++) {
                DbfColumnDef def = document.getColumn(i);
                DBFField field = new DBFField();
                field.setName(def.getName());
                field.setType(def.getType());
                field.setLength(def.getLength());
                if (def.getType() == DBFDataType.NUMERIC || def.getType() == DBFDataType.FLOATING_POINT) {
                    field.setDecimalCount(def.getDecimalCount());
                }
                fields[i] = field;
            }
            writer.setFields(fields);

            for (DbfRow row : document.getRows()) {
                Object[] values = new Object[columnCount];
                for (int i = 0; i < columnCount; i++) {
                    values[i] = coerce(row.get(i), document.getColumn(i).getType());
                }
                writer.addRecord(values);
            }
        } finally {
            // For a File-backed writer close() seeks back and backfills the header (record count) and
            // the EOF byte; for an OutputStream-backed one it flushes the buffered records into the stream.
            writer.close();
        }
    }

    /**
     * Defensive coercion so a value always matches the type javadbf expects for the field.
     * Cell editors already produce the right types, but this guards against stray Strings.
     */
    private static @Nullable Object coerce(@Nullable Object value, @NotNull DBFDataType type) {
        if (value == null) {
            return null;
        }
        switch (type) {
            case NUMERIC:
            case FLOATING_POINT:
                if (value instanceof Number) {
                    return value;
                }
                String num = value.toString().trim();
                return num.isEmpty() ? null : new BigDecimal(num);
            case LOGICAL:
                if (value instanceof Boolean) {
                    return value;
                }
                return Boolean.parseBoolean(value.toString().trim());
            case DATE:
                return value instanceof Date ? value : null;
            default:
                return value.toString();
        }
    }
}
