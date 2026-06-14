package com.poliproger.dbfreader.ui;

import com.poliproger.dbfreader.model.DbfColumnDef;
import com.poliproger.dbfreader.model.DbfDocument;
import com.poliproger.dbfreader.model.DbfRow;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.BooleanSupplier;
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

    /**
     * Aggregated result of {@link #scan} — enough to drive the highlight, navigation and the exact "N of
     * M" counter <em>without</em> storing a coordinate per matching cell (which on a huge file with a
     * broad query is the editor's memory cliff). See {@code notes/search-wide-query-memory.md}.
     *
     * @param rowMatchCount number of matching cells in each model row (index = model row); all-zero when
     *                      nothing matched, length 0 only for {@link #BAD}
     * @param total         total number of matching cells (= sum of {@code rowMatchCount}); the M
     * @param currentCell   the match to select — the first one at or after {@code anchor} in row-major
     *                      order, wrapping to the very first match; -1 when there are no matches
     * @param currentIndex  0-based rank of {@code currentCell} among all matches (the N-1); -1 when none
     * @param badPattern    {@code true} only when a regex query failed to compile (as in {@link Result})
     */
    public record ScanResult(int @NotNull [] rowMatchCount, int total, long currentCell, int currentIndex,
                             boolean badPattern) {

        private static final ScanResult BAD = new ScanResult(new int[0], 0, -1, -1, true);
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

    /**
     * Convenience overload that snapshots {@code doc} and scans it to completion (no cancellation).
     * Used by the tests and any synchronous caller; the editor uses {@link #find(DbfRow[],
     * DbfColumnDef[], Query, BooleanSupplier)} to scan off the EDT.
     */
    public static @NotNull Result find(@NotNull DbfDocument doc, @NotNull Query query) {
        int columnCount = doc.getColumnCount();
        DbfColumnDef[] defs = new DbfColumnDef[columnCount];
        for (int c = 0; c < columnCount; c++) {
            defs[c] = doc.getColumn(c);
        }
        Result result = find(doc.getRows().toArray(new DbfRow[0]), defs, query, () -> false);
        // The no-cancel scan above never returns null; fall back to EMPTY to keep the contract non-null.
        return result != null ? result : Result.EMPTY;
    }

    /**
     * Scans a pre-captured snapshot of the table — the {@code rows} references and {@code defs} the
     * caller took on the EDT — so a whole-table pass (format + regex over rows×columns) can run off
     * the EDT without freezing the UI on a large file. Polls {@code cancelled} once per row and
     * returns {@code null} as soon as it fires, so a pass superseded by a newer query (or a model
     * change) stops early instead of finishing a full scan over a huge table.
     *
     * <p>The snapshot fixes the row set and their indices, so rows added/removed on the EDT during the
     * scan cannot derail it. A column added/removed on the EDT mid-scan can still change a row's value
     * count under us; that surfaces as an {@link IndexOutOfBoundsException}, caught per row to abandon
     * the now-stale pass (the model change has already scheduled a fresh one).
     *
     * @return the matches, or {@code null} if {@code cancelled} fired (or a structural change aborted
     * the scan) — a stale result the caller must not apply
     */
    public static @Nullable Result find(@NotNull DbfRow @NotNull [] rows, @NotNull DbfColumnDef @NotNull [] defs,
                                         @NotNull Query query, @NotNull BooleanSupplier cancelled) {
        if (query.text().isEmpty()) {
            return Result.EMPTY;
        }
        Pattern pattern;
        try {
            pattern = compile(query);
        } catch (PatternSyntaxException e) {
            return Result.BAD;
        }

        int columnCount = defs.length;
        int rowCount = rows.length;
        // Start at a small fixed capacity (grown on demand). Don't size from rowCount*columnCount:
        // that product can overflow int for a very large table and yield a negative array size.
        long[] buffer = new long[(rowCount == 0 || columnCount == 0) ? 0 : 16];
        int size = 0;
        // Reuse a single Matcher across all cells (reset per cell) instead of allocating one per cell.
        Matcher matcher = null;
        for (int r = 0; r < rowCount; r++) {
            if (cancelled.getAsBoolean()) {
                return null;
            }
            DbfRow row = rows[r];
            try {
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
            } catch (IndexOutOfBoundsException structuralChange) {
                // A column was added/removed on the EDT mid-scan, shrinking this row's value list:
                // the snapshot is now stale, so abandon the pass — a fresh one is already scheduled.
                return null;
            }
        }
        if (size == 0) {
            return Result.EMPTY;
        }
        long[] matches = new long[size];
        System.arraycopy(buffer, 0, matches, 0, size);
        return new Result(matches, false);
    }

    /**
     * Like {@link #find(DbfRow[], DbfColumnDef[], Query, BooleanSupplier)}, but aggregates into a
     * {@link ScanResult} instead of materialising one {@code long} per matching cell. A broad query
     * (e.g. {@code "a"}) over a large file matches a huge number of cells, so storing each coordinate is
     * the editor's memory cliff; here the only per-row allocation is an {@code int} count, and the cell
     * highlight is recomputed live by the caller via {@link #matches(Pattern, Object, DbfColumnDef)}.
     *
     * <p>In one row-major pass it also picks the match to select — the first cell at or after
     * {@code anchor} (the caller's last selected cell, encoded; pass -1 for "from the top"), wrapping to
     * the first match — and that cell's rank, so the caller needn't keep the coordinates to compute the
     * "N of M" counter. Cancellation and the structural-change guard behave exactly as in {@code find}.
     *
     * @return the aggregate, or {@code null} if {@code cancelled} fired or a structural change aborted
     * the scan — a stale result the caller must not apply
     */
    public static @Nullable ScanResult scan(@NotNull DbfRow @NotNull [] rows, @NotNull DbfColumnDef @NotNull [] defs,
                                             @NotNull Query query, long anchor, @NotNull BooleanSupplier cancelled) {
        if (query.text().isEmpty()) {
            return new ScanResult(new int[rows.length], 0, -1, -1, false);
        }
        Pattern pattern;
        try {
            pattern = compile(query);
        } catch (PatternSyntaxException e) {
            return ScanResult.BAD;
        }

        int columnCount = defs.length;
        int rowCount = rows.length;
        int[] rowMatchCount = new int[rowCount];
        int total = 0;
        long firstAtOrAfter = -1;  // first match with coordinate >= anchor
        int firstRank = -1;
        long firstOverall = -1;    // very first match, for wrap-around when anchor is past the last match
        Matcher matcher = null;
        for (int r = 0; r < rowCount; r++) {
            if (cancelled.getAsBoolean()) {
                return null;
            }
            DbfRow row = rows[r];
            int rowMatches = 0;
            try {
                for (int c = 0; c < columnCount; c++) {
                    String text = DbfValueFormatter.format(row.get(c), defs[c]);
                    if (text.isEmpty()) {
                        continue;
                    }
                    matcher = matcher == null ? pattern.matcher(text) : matcher.reset(text);
                    if (!matcher.find()) {
                        continue;
                    }
                    long cell = encode(r, c);
                    if (firstOverall < 0) {
                        firstOverall = cell;
                    }
                    if (firstAtOrAfter < 0 && cell >= anchor) {
                        firstAtOrAfter = cell;
                        firstRank = total;  // total so far == this match's 0-based rank
                    }
                    total++;
                    rowMatches++;
                }
            } catch (IndexOutOfBoundsException structuralChange) {
                // A column was added/removed on the EDT mid-scan: the snapshot is stale, abandon the pass.
                return null;
            }
            rowMatchCount[r] = rowMatches;
        }
        if (total == 0) {
            return new ScanResult(rowMatchCount, 0, -1, -1, false);
        }
        if (firstAtOrAfter < 0) {  // anchor is past the last match — wrap to the first
            firstAtOrAfter = firstOverall;
            firstRank = 0;
        }
        return new ScanResult(rowMatchCount, total, firstAtOrAfter, firstRank, false);
    }

    /**
     * Whether a single cell's displayed text matches {@code query} — one iteration of {@link #find},
     * letting the editor re-evaluate just an edited cell instead of rescanning the whole table.
     * Returns {@code false} for a blank query or an invalid regex (consistent with {@code find}
     * producing no matches in those cases).
     */
    public static boolean matchesCell(@Nullable Object value, @NotNull DbfColumnDef def, @NotNull Query query) {
        if (query.text().isEmpty()) {
            return false;
        }
        Pattern pattern;
        try {
            pattern = compile(query);
        } catch (PatternSyntaxException e) {
            return false;
        }
        return matches(pattern, value, def);
    }

    /**
     * Whether a single cell's displayed text matches an already-compiled {@code pattern}. Lets a caller
     * compile once (per query) and reuse it across many cells — the editor caches the pattern and calls
     * this for every visible cell on repaint (its live highlight) and during prev/next navigation,
     * instead of recompiling the regex per cell.
     */
    public static boolean matches(@NotNull Pattern pattern, @Nullable Object value, @NotNull DbfColumnDef def) {
        String text = DbfValueFormatter.format(value, def);
        return !text.isEmpty() && pattern.matcher(text).find();
    }

    /** Compiles {@code query} into a {@link Pattern}; throws {@link PatternSyntaxException} for a bad regex. */
    public static @NotNull Pattern compile(@NotNull Query query) {
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
