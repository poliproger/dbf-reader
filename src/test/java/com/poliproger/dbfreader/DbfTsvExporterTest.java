package com.poliproger.dbfreader;

import com.linuxense.javadbf.DBFDataType;
import com.poliproger.dbfreader.model.DbfColumnDef;
import com.poliproger.dbfreader.model.DbfDocument;
import com.poliproger.dbfreader.model.DbfRow;
import com.poliproger.dbfreader.ui.DbfTsvExporter;
import org.junit.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * The pure (Swing-free) TSV export of {@link DbfTsvExporter}: cells are emitted as their
 * <em>displayed</em> text (so dates/numbers/logicals match their formatted form), in the order of the
 * supplied index arrays, with tab/newline/quote-bearing values quoted for the clipboard.
 */
public class DbfTsvExporterTest {

    private static DbfDocument document() throws Exception {
        List<DbfColumnDef> columns = new ArrayList<>(Arrays.asList(
                new DbfColumnDef("NAME", DBFDataType.CHARACTER, 10, 0),   // col 0
                new DbfColumnDef("AGE", DBFDataType.NUMERIC, 5, 0),       // col 1
                new DbfColumnDef("PRICE", DBFDataType.NUMERIC, 8, 2),     // col 2
                new DbfColumnDef("BORN", DBFDataType.DATE, 8, 0),         // col 3
                new DbfColumnDef("ACTIVE", DBFDataType.LOGICAL, 1, 0)));  // col 4
        List<DbfRow> rows = new ArrayList<>(Arrays.asList(
                new DbfRow(Arrays.asList("Alice", bd("30"), bd("19.99"), date("2020-01-15"), Boolean.TRUE)),
                new DbfRow(Arrays.asList("Bob", bd("5"), bd("100.00"), null, Boolean.FALSE))));
        return new DbfDocument(columns, rows, StandardCharsets.UTF_8);
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static Date date(String iso) throws Exception {
        return new SimpleDateFormat("yyyy-MM-dd").parse(iso);
    }

    @Test
    public void wholeSelectionMatchesDisplayedText() throws Exception {
        String tsv = DbfTsvExporter.toTsv(document(), new int[]{0, 1}, new int[]{0, 1, 2, 3, 4});
        assertEquals(
                "Alice\t30\t19.99\t2020-01-15\ttrue\n"
                        + "Bob\t5\t100.00\t\tfalse",
                tsv);
    }

    @Test
    public void columnsEmittedInGivenOrder() throws Exception {
        // The editor passes columns in on-screen order; a reordered/partial selection follows it.
        String tsv = DbfTsvExporter.toTsv(document(), new int[]{0}, new int[]{3, 0});
        assertEquals("2020-01-15\tAlice", tsv);
    }

    @Test
    public void nullCellIsAnEmptyField() throws Exception {
        String tsv = DbfTsvExporter.toTsv(document(), new int[]{1}, new int[]{3});
        assertEquals("", tsv);
    }

    @Test
    public void singleCell() throws Exception {
        String tsv = DbfTsvExporter.toTsv(document(), new int[]{0}, new int[]{2});
        assertEquals("19.99", tsv);
    }

    @Test
    public void valuesAreEmittedVerbatimWithoutEscaping() throws Exception {
        // No CSV/TSV quoting: values are copied exactly as shown, so they paste cleanly into a plain
        // text field (e.g. the search box). Quotes stay literal — a non-leading quote is kept as-is by
        // spreadsheets too.
        DbfDocument doc = new DbfDocument(
                new ArrayList<>(Arrays.asList(new DbfColumnDef("TXT", DBFDataType.CHARACTER, 40, 0))),
                new ArrayList<>(Arrays.asList(
                        new DbfRow(Arrays.<Object>asList("say \"hi\"")),
                        new DbfRow(Arrays.<Object>asList("12\" disk")),
                        new DbfRow(Arrays.<Object>asList("plain")))),
                StandardCharsets.UTF_8);
        assertEquals(
                "say \"hi\"\n"
                        + "12\" disk\n"
                        + "plain",
                DbfTsvExporter.toTsv(doc, new int[]{0, 1, 2}, new int[]{0}));
    }
}