package com.poliproger.dbfreader;

import com.linuxense.javadbf.DBFDataType;
import com.poliproger.dbfreader.model.DbfColumnDef;
import com.poliproger.dbfreader.model.DbfTypeConverter;
import org.junit.Test;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DbfTypeConverterTest {

    private static final Charset CS = StandardCharsets.UTF_8;

    @Test
    public void characterToNumericParsesAndClearsInvalid() {
        DbfColumnDef from = new DbfColumnDef("F", DBFDataType.CHARACTER, 10, 0);
        DbfColumnDef to = new DbfColumnDef("F", DBFDataType.NUMERIC, 10, 2);
        List<Object> source = Arrays.asList("12.34", "not-a-number", null);

        DbfTypeConverter.Result result = DbfTypeConverter.convert(source, from, to, CS);

        assertEquals(0, new BigDecimal("12.34").compareTo((BigDecimal) result.values.get(0)));
        assertNull(result.values.get(1));
        assertNull(result.values.get(2));
        assertEquals(1, result.clearedCount); // only the non-blank unparseable value counts
    }

    @Test
    public void numericToCharacterFormatsAndTruncates() {
        DbfColumnDef from = new DbfColumnDef("F", DBFDataType.NUMERIC, 10, 2);
        DbfColumnDef to = new DbfColumnDef("F", DBFDataType.CHARACTER, 4, 0);
        List<Object> source = Arrays.asList(new BigDecimal("1234.56"));

        DbfTypeConverter.Result result = DbfTypeConverter.convert(source, from, to, CS);

        assertEquals("1234", result.values.get(0)); // truncated to length 4
        assertEquals(0, result.clearedCount);
    }

    @Test
    public void characterToLogical() {
        DbfColumnDef from = new DbfColumnDef("F", DBFDataType.CHARACTER, 5, 0);
        DbfColumnDef to = new DbfColumnDef("F", DBFDataType.LOGICAL, 1, 0);
        List<Object> source = Arrays.asList("T", "F", "xyz");

        DbfTypeConverter.Result result = DbfTypeConverter.convert(source, from, to, CS);

        assertEquals(Boolean.TRUE, result.values.get(0));
        assertEquals(Boolean.FALSE, result.values.get(1));
        assertNull(result.values.get(2)); // unrecognised -> cleared
        assertEquals(1, result.clearedCount);
    }

    @Test
    public void numericClearedWhenItOverflowsTargetFieldAfterDecimalExpansion() {
        DbfColumnDef from = new DbfColumnDef("F", DBFDataType.CHARACTER, 10, 0);
        // length 5, 2 decimals -> only 2 integer digits fit ("99.99"); 123 would need "123.00".
        DbfColumnDef to = new DbfColumnDef("F", DBFDataType.NUMERIC, 5, 2);
        List<Object> source = Arrays.asList("12", "123");

        DbfTypeConverter.Result result = DbfTypeConverter.convert(source, from, to, CS);

        assertEquals(0, new BigDecimal("12").compareTo((BigDecimal) result.values.get(0)));
        assertNull(result.values.get(1)); // "123.00" (6 chars) does not fit length 5
        assertEquals(1, result.clearedCount);
    }

    @Test
    public void characterTruncationCountsBytesNotChars() {
        DbfColumnDef from = new DbfColumnDef("F", DBFDataType.CHARACTER, 20, 0);
        DbfColumnDef to = new DbfColumnDef("F", DBFDataType.CHARACTER, 5, 0);
        // Each Cyrillic char is 2 bytes in UTF-8, so a 5-byte field holds at most 2 of them.
        List<Object> source = Arrays.asList("абвг");

        DbfTypeConverter.Result result = DbfTypeConverter.convert(source, from, to, CS);

        String converted = (String) result.values.get(0);
        assertEquals("аб", converted); // truncated on a byte boundary, not split mid-character
        assertTrue("must fit the 5-byte field", converted.getBytes(CS).length <= 5);
    }
}
