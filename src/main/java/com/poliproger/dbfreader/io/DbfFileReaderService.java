package com.poliproger.dbfreader.io;

import com.linuxense.javadbf.DBFCharsetHelper;
import com.linuxense.javadbf.DBFField;
import com.linuxense.javadbf.DBFReader;
import com.poliproger.dbfreader.model.DbfColumnDef;
import com.poliproger.dbfreader.model.DbfDocument;
import com.poliproger.dbfreader.model.DbfRow;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads the binary contents of a DBF file into an in-memory {@link DbfDocument}.
 */
public final class DbfFileReaderService {

    /** Offset of the language-driver (code-page mark) byte in the DBF header. */
    private static final int LANGUAGE_DRIVER_OFFSET = 29;

    private DbfFileReaderService() {
    }

    /**
     * @param content        raw bytes of the .dbf file
     * @param charsetOverride explicit charset to decode character fields, or {@code null} to let
     *                        javadbf derive it from the file's language-driver byte
     */
    public static @NotNull DbfDocument read(byte @NotNull [] content, @Nullable Charset charsetOverride) {
        return read(content, charsetOverride, Charset.defaultCharset());
    }

    /**
     * @param content        raw bytes of the .dbf file
     * @param charsetOverride explicit charset to decode character fields, or {@code null} to let
     *                        javadbf derive it from the file's language-driver byte
     * @param fallback       charset to use when neither an override is given nor the file declares
     *                        one (language-driver byte is 0)
     */
    public static @NotNull DbfDocument read(byte @NotNull [] content, @Nullable Charset charsetOverride,
                                            @NotNull Charset fallback) {
        // Resolve the charset ourselves and pass it to javadbf explicitly. We cannot rely on
        // DBFReader's auto-detection: javadbf reads the language-driver as a signed byte, so any
        // code page >= 0x80 (e.g. 0xC9 windows-1251) is misread and silently falls back to
        // ISO-8859-1 — which also means getCharset() never returns null to signal "undeclared".
        Charset charset = resolveCharset(content, charsetOverride, fallback);
        DBFReader reader = null;
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(content);
            reader = new DBFReader(in, charset);

            int fieldCount = reader.getFieldCount();
            List<DbfColumnDef> columns = new ArrayList<>(fieldCount);
            for (int i = 0; i < fieldCount; i++) {
                DBFField field = reader.getField(i);
                columns.add(new DbfColumnDef(field.getName(), field.getType(),
                        field.getLength(), field.getDecimalCount()));
            }

            List<DbfRow> rows = new ArrayList<>();
            Object[] record;
            while ((record = reader.nextRecord()) != null) {
                rows.add(new DbfRow(Arrays.asList(record)));
            }

            // The first header byte is the format/version flag; javadbf does not expose it.
            int signature = content.length > 0 ? content[0] & 0xFF : -1;
            return new DbfDocument(columns, rows, charset, signature);
        } finally {
            if (reader != null) {
                reader.close();
            }
        }
    }

    /**
     * Determines which charset to decode character fields with:
     * an explicit {@code override} wins; otherwise the file's declared code page (language-driver
     * byte, read unsigned to dodge javadbf's sign-extension bug) is mapped via javadbf's own table;
     * if the file declares none (byte 0) or an unknown code, the configured {@code fallback} is used.
     */
    private static @NotNull Charset resolveCharset(byte @NotNull [] content, @Nullable Charset override,
                                                   @NotNull Charset fallback) {
        if (override != null) {
            return override;
        }
        if (content.length > LANGUAGE_DRIVER_OFFSET) {
            int ldid = content[LANGUAGE_DRIVER_OFFSET] & 0xFF;
            if (ldid != 0) {
                Charset declared = DBFCharsetHelper.getCharsetByByte(ldid);
                if (declared != null) {
                    return declared;
                }
            }
        }
        return fallback;
    }
}
