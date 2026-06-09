package com.poliproger.dbfreader.ui;

import com.poliproger.dbfreader.model.DbfColumnDef;
import com.poliproger.dbfreader.model.DbfDocument;
import com.poliproger.dbfreader.model.DbfRow;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Pure (Swing-free) search over a {@link DbfDocument}: finds every cell whose <em>displayed</em>
 * text ({@link DbfValueFormatter#format}) matches the query, in row-major order. Lives in {@code ui}
 * rather than {@code model} because it intentionally matches the user-visible form (so dates,
 * numbers and logical values match what is shown), reusing {@link DbfValueFormatter}.
 */
public final class DbfTableSearch {

    private DbfTableSearch() {
    }

    /** A single word character per the engine's Unicode-aware {@code \w} (the definition {@code \b} uses). */
    private static final Pattern WORD_CHAR = Pattern.compile("\\w", Pattern.UNICODE_CHARACTER_CLASS);

    /** Search options, mirroring the toolbar toggles. */
    public record Query(@NotNull String text, boolean matchCase, boolean regex, boolean wholeWords) {
    }

    /**
     * @param matches    matching cells in row-major order, each encoded by {@link #encode}; empty when
     *                   the query is blank, the pattern is invalid, or nothing matches
     * @param badPattern {@code true} only when {@link Query#regex} was on and the pattern failed to
     *                   compile (distinct from a valid pattern that simply matches nothing)
     */
    public record Result(long @NotNull [] matches, boolean badPattern) {

        public static final Result EMPTY = new Result(new long[0], false);
        private static final Result BAD = new Result(new long[0], true);
    }

    /** Packs a cell coordinate into a single {@code long} (row in the high word, column in the low word). */
    public static long encode(int row, int column) {
        return ((long) row << 32) | (column & 0xffffffffL);
    }

    public static int rowOf(long cell) {
        return (int) (cell >> 32);
    }

    public static int columnOf(long cell) {
        return (int) cell;
    }

    public static @NotNull Result find(@NotNull DbfDocument doc, @NotNull Query query) {
        if (query.text().isEmpty()) {
            return Result.EMPTY;
        }
        Pattern pattern;
        try {
            pattern = compile(query);
        } catch (PatternSyntaxException e) {
            return Result.BAD;
        }

        int columnCount = doc.getColumnCount();
        int rowCount = doc.getRowCount();
        // Column defs don't change across rows; fetch them once instead of per cell.
        DbfColumnDef[] defs = new DbfColumnDef[columnCount];
        for (int c = 0; c < columnCount; c++) {
            defs[c] = doc.getColumn(c);
        }
        List<DbfRow> rows = doc.getRows();

        // Start at a small fixed capacity (grown on demand). Don't size from rowCount*columnCount:
        // that product can overflow int for a very large table and yield a negative array size.
        long[] buffer = new long[(rowCount == 0 || columnCount == 0) ? 0 : 16];
        int size = 0;
        // Reuse a single Matcher across all cells (reset per cell) instead of allocating one per cell.
        Matcher matcher = null;
        for (int r = 0; r < rowCount; r++) {
            DbfRow row = rows.get(r);
            for (int c = 0; c < columnCount; c++) {
                String text = DbfValueFormatter.format(row.get(c), defs[c]);
                if (text.isEmpty()) {
                    continue;
                }
                matcher = matcher == null ? pattern.matcher(text) : matcher.reset(text);
                if (!matcher.find()) {
                    continue;
                }
                if (size == buffer.length) {
                    long[] grown = new long[Math.max(16, buffer.length * 2)];
                    System.arraycopy(buffer, 0, grown, 0, size);
                    buffer = grown;
                }
                buffer[size++] = encode(r, c);
            }
        }
        if (size == 0) {
            return Result.EMPTY;
        }
        long[] matches = new long[size];
        System.arraycopy(buffer, 0, matches, 0, size);
        return new Result(matches, false);
    }

    private static @NotNull Pattern compile(@NotNull Query query) {
        String base = query.regex() ? query.text() : Pattern.quote(query.text());
        if (query.wholeWords()) {
            base = wrapWholeWords(query, base);
        }
        // UNICODE_CHARACTER_CLASS makes \b (and \w, \d, \s) Unicode-aware. Without it, \b uses the
        // ASCII definition of a word character, so "Whole Words" never matches a Cyrillic word — the
        // plugin's primary case (windows-1251 data).
        int flags = Pattern.UNICODE_CHARACTER_CLASS;
        if (!query.matchCase()) {
            flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        }
        return Pattern.compile(base, flags);
    }

    /**
     * Wraps {@code base} in word boundaries for Whole Words, but adds a {@code \b} at an edge only when
     * the query's character at that edge is itself a word character. A query that begins or ends with a
     * non-word character (a quote or bracket, e.g. {@code "widget"} or {@code Тип "Образец"})
     * has no word boundary there, so an unconditional {@code \b} could never match and the cell would
     * wrongly read as "not found". A regex query's edges can't be inspected, so both boundaries are kept.
     */
    private static @NotNull String wrapWholeWords(@NotNull Query query, @NotNull String base) {
        String text = query.text();
        boolean lead = query.regex() || startsWithWordChar(text);
        boolean trail = query.regex() || endsWithWordChar(text);
        return (lead ? "\\b" : "") + "(?:" + base + ")" + (trail ? "\\b" : "");
    }

    private static boolean startsWithWordChar(@NotNull String s) {
        return !s.isEmpty() && isWordChar(s.codePointAt(0));
    }

    private static boolean endsWithWordChar(@NotNull String s) {
        return !s.isEmpty() && isWordChar(s.codePointBefore(s.length()));
    }

    private static boolean isWordChar(int codePoint) {
        return WORD_CHAR.matcher(new String(Character.toChars(codePoint))).matches();
    }
}
