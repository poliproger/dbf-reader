package com.poliproger.dbfreader;

import com.linuxense.javadbf.DBFDataType;
import com.poliproger.dbfreader.io.DbfFileReaderService;
import com.poliproger.dbfreader.model.DbfColumnDef;
import com.poliproger.dbfreader.model.DbfDocument;
import com.poliproger.dbfreader.model.DbfRow;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DbfDocumentTest {

    @Test
    public void addAndRemoveColumnKeepsRowsAligned() {
        List<DbfColumnDef> columns = new ArrayList<>(Arrays.asList(
                new DbfColumnDef("A", DBFDataType.CHARACTER, 5, 0),
                new DbfColumnDef("B", DBFDataType.CHARACTER, 5, 0)));
        List<DbfRow> rows = new ArrayList<>(Arrays.asList(
                new DbfRow(Arrays.asList("a1", "b1")),
                new DbfRow(Arrays.asList("a2", "b2"))));
        DbfDocument doc = new DbfDocument(columns, rows, Charset.forName("UTF-8"));

        doc.addColumn(new DbfColumnDef("C", DBFDataType.CHARACTER, 5, 0), "def");
        assertEquals(3, doc.getColumnCount());
        assertEquals("def", doc.getRows().get(0).get(2));
        assertEquals(3, doc.getRows().get(1).size());

        doc.removeColumn(0);
        assertEquals(2, doc.getColumnCount());
        assertEquals("b1", doc.getRows().get(0).get(0));
        assertEquals("def", doc.getRows().get(0).get(1));
    }

    @Test
    public void emptyRowHasNullsForEachColumn() {
        DbfRow row = DbfRow.empty(3);
        assertEquals(3, row.size());
        assertNull(row.get(0));
        assertNull(row.get(2));
    }

    /**
     * Reads the committed synthetic sample file from the test classpath (src/test/resources/dbf/),
     * so the test is self-contained and never skips. See dbf/README.md for how it was generated.
     */
    @Test
    public void readsSyntheticSampleFile() throws Exception {
        DbfDocument doc = DbfFileReaderService.read(resourceBytes("dbf/employee.dbf"), StandardCharsets.UTF_8);

        assertEquals(6, doc.getColumnCount());
        assertEquals(3, doc.getRowCount());
        assertEquals("NAME", doc.getColumn(0).getName());
        assertEquals(DBFDataType.CHARACTER, doc.getColumn(0).getType());
        assertEquals(DBFDataType.NUMERIC, doc.getColumn(2).getType());
        assertEquals(DBFDataType.LOGICAL, doc.getColumn(4).getType());

        DbfRow row0 = doc.getRows().get(0);
        assertEquals("Alice Smith", row0.get(0).toString().trim());
        assertEquals(0, new BigDecimal("34").compareTo(new BigDecimal(row0.get(2).toString().trim())));
        assertEquals(Boolean.TRUE, row0.get(4));
    }

    private static byte[] resourceBytes(String name) throws Exception {
        try (InputStream in = DbfDocumentTest.class.getClassLoader().getResourceAsStream(name)) {
            assertTrue("test resource not found: " + name, in != null);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }
}
