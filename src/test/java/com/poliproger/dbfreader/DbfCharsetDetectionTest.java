package com.poliproger.dbfreader;

import com.linuxense.javadbf.DBFDataType;
import com.linuxense.javadbf.DBFField;
import com.linuxense.javadbf.DBFWriter;
import com.poliproger.dbfreader.io.DbfFileReaderService;
import com.poliproger.dbfreader.model.DbfDocument;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

/**
 * Charset resolution on read: an explicit override wins, otherwise the file's declared code page
 * (language-driver byte) is honoured, and an undeclared/unknown code falls back to the configured
 * default.
 */
public class DbfCharsetDetectionTest {

    private static final int LANGUAGE_DRIVER_OFFSET = 29;

    /** Builds a one-field, one-record DBF encoded with {@code charsetName}, then forces its LDID byte. */
    private static byte[] dbf(String charsetName, String value, int ldid) {
        DBFField[] fields = {new DBFField("TXT", DBFDataType.CHARACTER, 30)};
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DBFWriter writer = new DBFWriter(bos, Charset.forName(charsetName));
        writer.setFields(fields);
        writer.addRecord(new Object[]{value});
        writer.close();
        byte[] bytes = bos.toByteArray();
        bytes[LANGUAGE_DRIVER_OFFSET] = (byte) ldid;
        return bytes;
    }

    /**
     * Regression: javadbf reads the language-driver as a signed byte, so a code page >= 0x80
     * (0xC9 == windows-1251) was misread and silently decoded as ISO-8859-1. We resolve it ourselves
     * from the unsigned byte, so the declared code page must win over the fallback and decode cleanly.
     */
    @Test
    public void declaredHighByteCodePageIsDetected() {
        String cyrillic = "Привет";
        byte[] bytes = dbf("windows-1251", cyrillic, 0xC9);

        DbfDocument doc = DbfFileReaderService.read(bytes, null, StandardCharsets.ISO_8859_1);

        assertEquals("windows-1251", doc.getCharset().name());
        assertEquals(cyrillic, doc.getRows().get(0).get(0).toString().trim());
    }

    /** A low-byte code page (0x26 == IBM866 Russian OEM) is also honoured. */
    @Test
    public void declaredLowByteCodePageIsDetected() {
        byte[] bytes = dbf("IBM866", "ABC", 0x26);

        DbfDocument doc = DbfFileReaderService.read(bytes, null, StandardCharsets.ISO_8859_1);

        assertEquals("IBM866", doc.getCharset().name());
    }

    /** No declared code page (LDID == 0) -> use the configured default. */
    @Test
    public void undeclaredCodePageUsesFallback() {
        byte[] bytes = dbf("UTF-8", "ABC", 0x00);

        DbfDocument doc = DbfFileReaderService.read(bytes, null, Charset.forName("IBM866"));

        assertEquals("IBM866", doc.getCharset().name());
    }

    /** An unknown LDID code (not in javadbf's mapping table) -> use the configured default. */
    @Test
    public void unknownCodePageUsesFallback() {
        byte[] bytes = dbf("UTF-8", "ABC", 0xAA);

        DbfDocument doc = DbfFileReaderService.read(bytes, null, Charset.forName("IBM866"));

        assertEquals("IBM866", doc.getCharset().name());
    }

    /** An explicit override beats both the declared code page and the fallback. */
    @Test
    public void explicitOverrideWins() {
        byte[] bytes = dbf("windows-1251", "ABC", 0xC9);

        DbfDocument doc = DbfFileReaderService.read(bytes, StandardCharsets.UTF_8, StandardCharsets.ISO_8859_1);

        assertEquals("UTF-8", doc.getCharset().name());
    }
}
