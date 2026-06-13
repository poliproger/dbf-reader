package com.poliproger.dbfreader.io;

import com.linuxense.javadbf.DBFDataType;
import com.linuxense.javadbf.DBFField;
import com.linuxense.javadbf.DBFWriter;
import com.poliproger.dbfreader.model.DbfColumnDef;
import com.poliproger.dbfreader.model.DbfDocument;
import com.poliproger.dbfreader.model.DbfRow;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
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
        DBFWriter writer = new DBFWriter(bos, document.getCharset());
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
            // For OutputStream-backed writers, close() flushes the buffered records into bos.
            writer.close();
        }
        return bos.toByteArray();
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
