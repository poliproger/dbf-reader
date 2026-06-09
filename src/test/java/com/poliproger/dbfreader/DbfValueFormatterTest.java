package com.poliproger.dbfreader;

import com.poliproger.dbfreader.ui.DbfValueFormatter;
import org.junit.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link DbfValueFormatter#truncateToBytes} on multibyte boundaries. DBF character fields are sized
 * in bytes, so truncation must measure the encoded form — and must never split a multibyte
 * character or a surrogate pair, which would corrupt the value on write.
 */
public class DbfValueFormatterTest {

    private static final Charset WINDOWS_1251 = Charset.forName("windows-1251");

    private static String truncate(String s, int maxBytes, Charset charset) {
        return DbfValueFormatter.truncateToBytes(s, maxBytes, charset);
    }

    @Test
    public void shortValueIsUnchanged() {
        assertEquals("abc", truncate("abc", 10, StandardCharsets.UTF_8));
    }

    @Test
    public void exactFitIsUnchanged() {
        assertEquals("abcde", truncate("abcde", 5, StandardCharsets.UTF_8));
    }

    @Test
    public void asciiIsTruncatedAtTheByteLimit() {
        assertEquals("abcd", truncate("abcdef", 4, StandardCharsets.UTF_8));
    }

    @Test
    public void multibyteCharacterIsNeverSplit() {
        // Cyrillic letters are 2 bytes each in UTF-8: a 5-byte budget fits two letters (4 bytes),
        // and the third must be dropped whole rather than leaving half of its encoding.
        assertEquals("пр", truncate("привет", 5, StandardCharsets.UTF_8));
        assertEquals("при", truncate("привет", 6, StandardCharsets.UTF_8));
    }

    @Test
    public void surrogatePairIsNeverSplit() {
        // The emoji is one code point of two chars (a surrogate pair), 4 bytes in UTF-8. With only
        // 2-5 bytes of room after "ab" the pair must be dropped whole — keeping just the high
        // surrogate would produce a malformed string.
        String s = "ab😀cd";
        assertEquals("ab", truncate(s, 5, StandardCharsets.UTF_8));
        assertEquals("ab😀", truncate(s, 6, StandardCharsets.UTF_8));
    }

    @Test
    public void truncatedPrefixAlwaysFitsAndStaysWellFormed() {
        String s = "aп😀я!"; // 1 + 2 + 4 + 2 + 1 = 10 bytes in UTF-8
        for (int maxBytes = 0; maxBytes <= 12; maxBytes++) {
            String t = truncate(s, maxBytes, StandardCharsets.UTF_8);
            assertTrue("prefix of the original, maxBytes=" + maxBytes, s.startsWith(t));
            assertTrue("fits the budget, maxBytes=" + maxBytes,
                    t.getBytes(StandardCharsets.UTF_8).length <= Math.max(0, maxBytes));
            assertFalse("no dangling high surrogate, maxBytes=" + maxBytes,
                    !t.isEmpty() && Character.isHighSurrogate(t.charAt(t.length() - 1)));
        }
    }

    @Test
    public void singleByteCharsetTruncatesPerCharacter() {
        // In windows-1251 Cyrillic is single-byte, so the same text fits twice as many letters.
        assertEquals("прив", truncate("привет", 4, WINDOWS_1251));
    }

    @Test
    public void nullAndNonPositiveBudgetYieldEmptyString() {
        assertEquals("", truncate(null, 10, StandardCharsets.UTF_8));
        assertEquals("", truncate("abc", 0, StandardCharsets.UTF_8));
        assertEquals("", truncate("abc", -1, StandardCharsets.UTF_8));
    }
}
