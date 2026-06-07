package com.poliproger.dbfreader;

import com.linuxense.javadbf.DBFDataType;
import com.linuxense.javadbf.DBFField;
import com.linuxense.javadbf.DBFWriter;
import com.poliproger.dbfreader.io.DbfFileReaderService;
import com.poliproger.dbfreader.io.DbfFileWriterService;
import com.poliproger.dbfreader.model.DbfDocument;
import com.poliproger.dbfreader.model.DbfRow;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Read -> in-memory model -> write -> read round trip, verifying structure and values survive.
 */
public class DbfRoundTripTest {

    private static byte[] sampleDbf() {
        DBFField[] fields = {
                new DBFField("NAME", DBFDataType.CHARACTER, 20),
                new DBFField("AGE", DBFDataType.NUMERIC, 3, 0),
                new DBFField("SALARY", DBFDataType.NUMERIC, 10, 2),
                new DBFField("ACTIVE", DBFDataType.LOGICAL, 1),
                new DBFField("HIRED", DBFDataType.DATE, 8),
        };
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DBFWriter writer = new DBFWriter(bos, StandardCharsets.UTF_8);
        writer.setFields(fields);
        writer.addRecord(new Object[]{"John Doe", 30, new BigDecimal("1234.50"), Boolean.TRUE, date(2020, 1, 15)});
        writer.addRecord(new Object[]{"Jane Roe", 41, new BigDecimal("9876.00"), Boolean.FALSE, date(2018, 6, 1)});
        writer.close();
        return bos.toByteArray();
    }

    private static Date date(int year, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month - 1, day);
        return c.getTime();
    }

    @Test
    public void readsStructureAndValues() {
        DbfDocument doc = DbfFileReaderService.read(sampleDbf(), StandardCharsets.UTF_8);

        assertEquals(5, doc.getColumnCount());
        assertEquals(2, doc.getRowCount());
        assertEquals("NAME", doc.getColumn(0).getName());
        assertEquals(DBFDataType.NUMERIC, doc.getColumn(1).getType());

        DbfRow row0 = doc.getRows().get(0);
        assertEquals("John Doe", row0.get(0).toString().trim());
        assertEquals(0, new BigDecimal("30").compareTo(new BigDecimal(row0.get(1).toString().trim())));
        assertEquals(0, new BigDecimal("1234.50").compareTo(new BigDecimal(row0.get(2).toString().trim())));
        assertEquals(Boolean.TRUE, row0.get(3));
    }

    @Test
    public void rewriteKeepsData() {
        DbfDocument first = DbfFileReaderService.read(sampleDbf(), StandardCharsets.UTF_8);
        byte[] rewritten = DbfFileWriterService.write(first);
        DbfDocument second = DbfFileReaderService.read(rewritten, StandardCharsets.UTF_8);

        assertEquals(first.getColumnCount(), second.getColumnCount());
        assertEquals(first.getRowCount(), second.getRowCount());

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        assertEquals("Jane Roe", second.getRows().get(1).get(0).toString().trim());
        assertEquals("2018-06-01", sdf.format((Date) second.getRows().get(1).get(4)));
        assertTrue(DbfFileWriterService.hasUnwritableColumns(second) == false);
    }
}
