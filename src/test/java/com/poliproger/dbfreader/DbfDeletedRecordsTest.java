package com.poliproger.dbfreader;

import com.linuxense.javadbf.DBFDataType;
import com.linuxense.javadbf.DBFField;
import com.linuxense.javadbf.DBFWriter;
import com.poliproger.dbfreader.io.DbfFileReaderService;
import com.poliproger.dbfreader.model.DbfDocument;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

/**
 * Reading a file with records marked as deleted (the {@code *} record flag): javadbf skips them, and
 * the reader must report how many were skipped so the UI can warn that saving drops them. javadbf
 * cannot write the flag, so the sample flips a record's flag byte in the produced bytes directly.
 */
public class DbfDeletedRecordsTest {

    private static byte[] sampleDbf() {
        DBFField[] fields = {new DBFField("NAME", DBFDataType.CHARACTER, 10)};
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DBFWriter writer = new DBFWriter(bos, StandardCharsets.UTF_8);
        writer.setFields(fields);
        writer.addRecord(new Object[]{"first"});
        writer.addRecord(new Object[]{"second"});
        writer.addRecord(new Object[]{"third"});
        writer.close();
        return bos.toByteArray();
    }

    /** Sets the deletion flag (first byte of the record, {@code 0x2A '*'}) on the given record. */
    private static byte[] markDeleted(byte[] dbf, int recordIndex) {
        int headerLength = (dbf[8] & 0xFF) | ((dbf[9] & 0xFF) << 8);
        int recordLength = (dbf[10] & 0xFF) | ((dbf[11] & 0xFF) << 8);
        byte[] out = dbf.clone();
        out[headerLength + recordIndex * recordLength] = '*';
        return out;
    }

    @Test
    public void deletedRecordsAreSkippedAndCounted() {
        byte[] bytes = markDeleted(sampleDbf(), 1);
        DbfDocument doc = DbfFileReaderService.read(bytes, StandardCharsets.UTF_8);
        assertEquals(2, doc.getRowCount());
        assertEquals(1, doc.getDeletedRecordCount());
        assertEquals("first", doc.getRows().get(0).get(0));
        assertEquals("third", doc.getRows().get(1).get(0));
    }

    @Test
    public void fileWithoutDeletedRecordsReportsZero() {
        DbfDocument doc = DbfFileReaderService.read(sampleDbf(), StandardCharsets.UTF_8);
        assertEquals(3, doc.getRowCount());
        assertEquals(0, doc.getDeletedRecordCount());
    }
}
