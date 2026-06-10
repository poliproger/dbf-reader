package com.poliproger.dbfreader;

import com.linuxense.javadbf.DBFDataType;
import com.poliproger.dbfreader.io.DbfFileReaderService;
import com.poliproger.dbfreader.io.DbfFileWriterService;
import com.poliproger.dbfreader.model.DbfColumnDef;
import com.poliproger.dbfreader.model.DbfDocument;
import org.junit.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * A freshly created (empty) {@code .dbf} file — e.g. opened straight after New File — must load as a
 * blank document with no columns, instead of failing to parse. The user then builds it up by adding
 * columns and saving.
 */
public class DbfEmptyFileTest {

    /** Empty content loads as a blank document (no columns, no rows) rather than throwing. */
    @Test
    public void emptyContentLoadsAsBlankDocument() {
        DbfDocument doc = DbfFileReaderService.read(new byte[0], null, StandardCharsets.UTF_8);

        assertEquals(0, doc.getColumnCount());
        assertEquals(0, doc.getRowCount());
        assertEquals("UTF-8", doc.getCharset().name());
    }

    /** An explicit charset override is honoured even for an empty file. */
    @Test
    public void emptyContentHonoursCharsetOverride() {
        DbfDocument doc = DbfFileReaderService.read(new byte[0], Charset.forName("windows-1251"),
                StandardCharsets.UTF_8);

        assertEquals("windows-1251", doc.getCharset().name());
    }

    /** After adding a writable column, the blank document serializes to a valid, re-readable DBF. */
    @Test
    public void blankDocumentBecomesSaveableAfterAddingColumn() {
        DbfDocument doc = DbfFileReaderService.read(new byte[0], null, StandardCharsets.UTF_8);

        doc.addColumn(new DbfColumnDef("NAME", DBFDataType.CHARACTER, 20, 0), null);
        assertFalse(DbfFileWriterService.hasUnwritableColumns(doc));

        byte[] written = DbfFileWriterService.write(doc);
        DbfDocument reloaded = DbfFileReaderService.read(written, null, StandardCharsets.UTF_8);

        assertEquals(1, reloaded.getColumnCount());
        assertEquals("NAME", reloaded.getColumn(0).getName());
        assertEquals(0, reloaded.getRowCount());
    }
}