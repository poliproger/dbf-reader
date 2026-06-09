package com.poliproger.dbfreader;

import com.linuxense.javadbf.DBFDataType;
import com.poliproger.dbfreader.model.DbfColumnDef;
import com.poliproger.dbfreader.model.DbfDocument;
import com.poliproger.dbfreader.model.DbfRow;
import com.poliproger.dbfreader.ui.DbfTableSearch;
import org.junit.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The pure (Swing-free) search logic of {@link DbfTableSearch}: matching is done against the
 * <em>displayed</em> text (so dates/numbers/logicals match their formatted form), in row-major order,
 * honouring the Match Case / Regex / Whole Words options.
 */
public class DbfTableSearchTest {

    private static DbfDocument document() throws Exception {
        List<DbfColumnDef> columns = new ArrayList<>(Arrays.asList(
                new DbfColumnDef("NAME", DBFDataType.CHARACTER, 10, 0),  // col 0
                new DbfColumnDef("AGE", DBFDataType.NUMERIC, 5, 0),      // col 1
                new DbfColumnDef("PRICE", DBFDataType.NUMERIC, 8, 2),    // col 2
                new DbfColumnDef("BORN", DBFDataType.DATE, 8, 0),        // col 3
                new DbfColumnDef("ACTIVE", DBFDataType.LOGICAL, 1, 0))); // col 4
        List<DbfRow> rows = new ArrayList<>(Arrays.asList(
                new DbfRow(Arrays.asList("Alice", bd("30"), bd("19.99"), date("2020-01-15"), Boolean.TRUE)),
                new DbfRow(Arrays.asList("alice BOB", bd("5"), bd("100.00"), null, Boolean.FALSE)),
                new DbfRow(Arrays.asList("Carol", null, bd("19.99"), date("1999-12-31"), Boolean.TRUE)),
                new DbfRow(Arrays.asList("Иванов кот", null, bd("0"), null, Boolean.FALSE)),
                new DbfRow(Arrays.asList("котёнок", null, bd("0"), null, Boolean.FALSE))));
        return new DbfDocument(columns, rows, StandardCharsets.UTF_8);
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static Date date(String iso) throws Exception {
        return new SimpleDateFormat("yyyy-MM-dd").parse(iso);
    }

    private static DbfTableSearch.Result find(String text, boolean matchCase, boolean regex, boolean wholeWords)
            throws Exception {
        return DbfTableSearch.find(document(), new DbfTableSearch.Query(text, matchCase, regex, wholeWords));
    }

    @Test
    public void caseInsensitiveSubstringInRowMajorOrder() throws Exception {
        DbfTableSearch.Result r = find("alice", false, false, false);
        assertFalse(r.badPattern());
        assertArrayEquals(new long[]{DbfTableSearch.encode(0, 0), DbfTableSearch.encode(1, 0)}, r.matches());
    }

    @Test
    public void matchCaseRespectsCase() throws Exception {
        DbfTableSearch.Result r = find("alice", true, false, false);
        assertArrayEquals(new long[]{DbfTableSearch.encode(1, 0)}, r.matches());
    }

    @Test
    public void regexMatchesFormattedNumbers() throws Exception {
        // Matches the displayed two-decimal form: 19.99, 100.00, 19.99.
        DbfTableSearch.Result r = find("\\d{2}\\.\\d{2}", false, true, false);
        assertArrayEquals(new long[]{
                DbfTableSearch.encode(0, 2), DbfTableSearch.encode(1, 2), DbfTableSearch.encode(2, 2)}, r.matches());
    }

    @Test
    public void wholeWordsMatchesOnlyFullWords() throws Exception {
        assertEquals("substring inside a word must not match", 0, find("lic", false, false, true).matches().length);
        assertArrayEquals("standalone word matches",
                new long[]{DbfTableSearch.encode(1, 0)}, find("bob", false, false, true).matches());
    }

    @Test
    public void wholeWordsMatchesCyrillicWords() throws Exception {
        // \b must be Unicode-aware: a standalone Cyrillic word matches, a substring inside one does not.
        assertArrayEquals("standalone Cyrillic word matches",
                new long[]{DbfTableSearch.encode(3, 0)}, find("Иванов", false, false, true).matches());
        assertArrayEquals("Cyrillic word among others matches",
                new long[]{DbfTableSearch.encode(3, 0)}, find("кот", false, false, true).matches());
        assertEquals("Cyrillic substring inside a word must not match",
                0, find("котён", false, false, true).matches().length);
    }

    @Test
    public void matchesFormattedDate() throws Exception {
        assertArrayEquals(new long[]{DbfTableSearch.encode(0, 3)}, find("2020-01-15", false, false, false).matches());
    }

    @Test
    public void matchesLogicalByDisplayedText() throws Exception {
        DbfTableSearch.Result r = find("true", false, false, false);
        assertArrayEquals(new long[]{DbfTableSearch.encode(0, 4), DbfTableSearch.encode(2, 4)}, r.matches());
    }

    @Test
    public void noMatchesIsEmptyNotBad() throws Exception {
        DbfTableSearch.Result r = find("zzz", false, false, false);
        assertEquals(0, r.matches().length);
        assertFalse(r.badPattern());
    }

    @Test
    public void invalidRegexIsFlaggedBad() throws Exception {
        DbfTableSearch.Result r = find("[", false, true, false);
        assertTrue(r.badPattern());
        assertEquals(0, r.matches().length);
    }

    @Test
    public void blankQueryMatchesNothing() throws Exception {
        DbfTableSearch.Result r = find("", false, false, false);
        assertEquals(0, r.matches().length);
        assertFalse(r.badPattern());
    }

    @Test
    public void wholeWordsMatchesQueryWithLeadingTrailingNonWordChars() throws Exception {
        // A query that begins/ends with a non-word char (here a quote) has no word boundary there, so
        // wrapping it in \b...\b unconditionally made Whole Words read the cell as "not found".
        DbfDocument doc = quotedDoc();
        assertArrayEquals("leading+trailing quotes",
                new long[]{DbfTableSearch.encode(0, 0)},
                DbfTableSearch.find(doc, new DbfTableSearch.Query("\"widget\"", false, false, true)).matches());
        assertArrayEquals("trailing quote, Cyrillic",
                new long[]{DbfTableSearch.encode(1, 0)},
                DbfTableSearch.find(doc, new DbfTableSearch.Query("Тип \"Образец\"", false, false, true)).matches());
    }

    @Test
    public void wholeWordsStillRejectsSubstringOnTheWordCharEdge() throws Exception {
        // The boundary on the word-character edge stays enforced: a partial word still does not match.
        DbfDocument doc = quotedDoc();
        assertEquals(0, DbfTableSearch.find(doc, new DbfTableSearch.Query("widge", false, false, true)).matches().length);
    }

    private static DbfDocument quotedDoc() {
        List<DbfColumnDef> columns = new ArrayList<>(Arrays.asList(
                new DbfColumnDef("TXT", DBFDataType.CHARACTER, 40, 0)));
        List<DbfRow> rows = new ArrayList<>(Arrays.asList(
                new DbfRow(Arrays.<Object>asList("\"widget\"")),
                new DbfRow(Arrays.<Object>asList("Тип \"Образец\""))));
        return new DbfDocument(columns, rows, StandardCharsets.UTF_8);
    }
}
