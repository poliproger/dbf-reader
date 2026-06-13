package com.poliproger.dbfreader;

import com.linuxense.javadbf.DBFDataType;
import com.poliproger.dbfreader.io.DbfFileReaderService;
import com.poliproger.dbfreader.io.DbfFileWriterService;
import com.poliproger.dbfreader.model.DbfColumnDef;
import com.poliproger.dbfreader.model.DbfDocument;
import com.poliproger.dbfreader.model.DbfRow;
import org.junit.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Direct coverage of {@link DbfFileWriterService}: the unwritable-column check that gates Save, and
 * the defensive value coercion applied to every cell before it reaches javadbf (exercised through a
 * write -> read round trip, since {@code coerce} is an implementation detail).
 */
public class DbfFileWriterServiceTest {

    private static DbfDocument document(List<DbfColumnDef> columns, List<DbfRow> rows) {
        return new DbfDocument(new ArrayList<>(columns), new ArrayList<>(rows), StandardCharsets.UTF_8);
    }

    /** Writes a one-column, one-row document and returns the value javadbf reads back. */
    private static Object writeAndReadBack(DbfColumnDef column, Object value) {
        DbfDocument doc = document(
                Collections.singletonList(column),
                Collections.singletonList(new DbfRow(Collections.singletonList(value))));
        byte[] bytes = DbfFileWriterService.write(doc);
        return DbfFileReaderService.read(bytes, StandardCharsets.UTF_8).getRows().get(0).get(0);
    }

    private static Date date(int year, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month - 1, day);
        return c.getTime();
    }

    /** Serializes {@code doc} through the streaming {@code write(document, File)} path and returns the bytes. */
    private static byte[] writeToFileBytes(DbfDocument doc) throws Exception {
        Path temp = Files.createTempFile("dbf-test", ".dbf");
        try {
            DbfFileWriterService.write(doc, temp.toFile());
            return Files.readAllBytes(temp);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    // ---- streaming write parity --------------------------------------------------------------

    @Test
    public void streamingWriteMatchesInMemoryWrite() throws Exception {
        DbfDocument doc = document(Arrays.asList(
                new DbfColumnDef("NAME", DBFDataType.CHARACTER, 10, 0),
                new DbfColumnDef("AMOUNT", DBFDataType.NUMERIC, 8, 2),
                new DbfColumnDef("RATE", DBFDataType.FLOATING_POINT, 10, 3),
                new DbfColumnDef("ACTIVE", DBFDataType.LOGICAL, 1, 0),
                new DbfColumnDef("HIRED", DBFDataType.DATE, 8, 0)), Arrays.asList(
                new DbfRow(Arrays.asList("abc", new BigDecimal("12.34"), new BigDecimal("1.500"),
                        Boolean.TRUE, date(2024, 1, 15))),
                new DbfRow(Arrays.asList((Object) null, null, null, Boolean.FALSE, null)),
                new DbfRow(Arrays.asList("xyz", new BigDecimal("0.00"), new BigDecimal("9.999"),
                        Boolean.TRUE, date(1999, 12, 31)))));

        // The File-backed (streaming) writer must produce byte-identical output to the in-memory one,
        // so the editor's low-heap save path round-trips exactly like the proven byte[] path.
        assertArrayEquals(DbfFileWriterService.write(doc), writeToFileBytes(doc));
    }

    @Test
    public void streamingWriteOfEmptyDocumentProducesEmptyFile() throws Exception {
        DbfDocument doc = document(Collections.emptyList(), Collections.emptyList());

        byte[] streamed = writeToFileBytes(doc);

        assertEquals(0, streamed.length); // mirrors write(document) returning byte[0]
        assertArrayEquals(DbfFileWriterService.write(doc), streamed);
    }

    @Test
    public void streamingWriteTruncatesAReusedTarget() throws Exception {
        DbfDocument doc = document(Collections.emptyList(), Collections.emptyList());
        Path temp = Files.createTempFile("dbf-test", ".dbf");
        try {
            Files.write(temp, new byte[]{1, 2, 3, 4, 5}); // stale content from a hypothetical reuse
            DbfFileWriterService.write(doc, temp.toFile());
            assertEquals(0, Files.size(temp)); // an empty document leaves no stale bytes behind
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    // ---- hasUnwritableColumns ----------------------------------------------------------------

    @Test
    public void memoColumnMakesDocumentUnwritable() {
        DbfDocument doc = document(Arrays.asList(
                new DbfColumnDef("NAME", DBFDataType.CHARACTER, 10, 0),
                new DbfColumnDef("NOTES", DBFDataType.MEMO, 10, 0)), Collections.emptyList());

        assertTrue(DbfFileWriterService.hasUnwritableColumns(doc));
    }

    @Test
    public void allBaseTypesAreWritable() {
        DbfDocument doc = document(Arrays.asList(
                new DbfColumnDef("NAME", DBFDataType.CHARACTER, 10, 0),
                new DbfColumnDef("AGE", DBFDataType.NUMERIC, 3, 0),
                new DbfColumnDef("RATE", DBFDataType.FLOATING_POINT, 10, 2),
                new DbfColumnDef("ACTIVE", DBFDataType.LOGICAL, 1, 0),
                new DbfColumnDef("HIRED", DBFDataType.DATE, 8, 0)), Collections.emptyList());

        assertFalse(DbfFileWriterService.hasUnwritableColumns(doc));
    }

    // ---- coerce: NUMERIC ---------------------------------------------------------------------

    @Test
    public void strayNumericStringIsWrittenAsNumber() {
        Object value = writeAndReadBack(new DbfColumnDef("AMOUNT", DBFDataType.NUMERIC, 10, 2), "123.45");

        assertEquals(0, new BigDecimal("123.45").compareTo(new BigDecimal(value.toString().trim())));
    }

    @Test
    public void blankNumericStringBecomesNull() {
        assertNull(writeAndReadBack(new DbfColumnDef("AMOUNT", DBFDataType.NUMERIC, 10, 2), "   "));
    }

    @Test
    public void numberValuePassesThrough() {
        Object value = writeAndReadBack(new DbfColumnDef("AGE", DBFDataType.NUMERIC, 3, 0), 42);

        assertEquals(0, new BigDecimal("42").compareTo(new BigDecimal(value.toString().trim())));
    }

    // ---- coerce: LOGICAL ---------------------------------------------------------------------

    @Test
    public void booleanValuePassesThrough() {
        assertEquals(Boolean.TRUE,
                writeAndReadBack(new DbfColumnDef("ACTIVE", DBFDataType.LOGICAL, 1, 0), Boolean.TRUE));
    }

    @Test
    public void strayLogicalStringIsParsed() {
        assertEquals(Boolean.TRUE,
                writeAndReadBack(new DbfColumnDef("ACTIVE", DBFDataType.LOGICAL, 1, 0), "true"));
    }

    // ---- coerce: DATE ------------------------------------------------------------------------

    @Test
    public void dateValuePassesThrough() {
        Object value = writeAndReadBack(new DbfColumnDef("HIRED", DBFDataType.DATE, 8, 0), date(2024, 1, 15));

        assertEquals("2024-01-15", new SimpleDateFormat("yyyy-MM-dd").format((Date) value));
    }

    @Test
    public void strayNonDateValueBecomesNull() {
        // The guard drops anything that is not already a Date rather than risk writing garbage.
        assertNull(writeAndReadBack(new DbfColumnDef("HIRED", DBFDataType.DATE, 8, 0), "2024-01-15"));
    }

    // ---- coerce: CHARACTER ---------------------------------------------------------------------

    @Test
    public void strayNonStringValueIsWrittenAsItsText() {
        Object value = writeAndReadBack(new DbfColumnDef("CODE", DBFDataType.CHARACTER, 10, 0), 42);

        assertEquals("42", value.toString().trim());
    }

    @Test
    public void nullSurvivesWhereTheFormatAllowsIt() {
        assertNull(writeAndReadBack(new DbfColumnDef("AMOUNT", DBFDataType.NUMERIC, 10, 2), null));
        assertNull(writeAndReadBack(new DbfColumnDef("ACTIVE", DBFDataType.LOGICAL, 1, 0), null));
        assertNull(writeAndReadBack(new DbfColumnDef("HIRED", DBFDataType.DATE, 8, 0), null));
        // DBF character fields have no null: javadbf pads with spaces and reads back a blank string.
        assertEquals("", writeAndReadBack(new DbfColumnDef("NAME", DBFDataType.CHARACTER, 10, 0), null));
    }
}
