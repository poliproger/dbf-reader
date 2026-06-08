package com.poliproger.dbfreader.ui;

import com.linuxense.javadbf.DBFDataType;
import com.poliproger.dbfreader.model.DbfColumnDef;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Converts stored cell values to and from their textual representation, consistently with the
 * column type. Used by both the renderer (display) and the text cell editor (edit/commit).
 */
public final class DbfValueFormatter {

    public static final String DATE_PATTERN = "yyyy-MM-dd";

    /**
     * {@link SimpleDateFormat} is not thread-safe and constructing one is relatively expensive, yet
     * {@link #format} is called once per visible cell on render and once per cell on every filter
     * pass. A per-thread reused instance avoids both problems (all callers run on the EDT).
     */
    private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat(DATE_PATTERN));

    private DbfValueFormatter() {
    }

    public static @NotNull String format(@Nullable Object value, @NotNull DbfColumnDef def) {
        if (value == null) {
            return "";
        }
        switch (def.getType()) {
            case DATE:
                if (value instanceof Date) {
                    return DATE_FORMAT.get().format((Date) value);
                }
                return value.toString();
            case NUMERIC:
            case FLOATING_POINT:
            case DOUBLE:
            case LONG:
            case CURRENCY:
                return formatNumber(value, def.getDecimalCount());
            default:
                return value.toString();
        }
    }

    /**
     * Parses a {@code yyyy-MM-dd} date string into a {@link Date}, strictly (a malformed or impossible
     * date such as {@code 2024-02-30} is rejected, not rolled over). Blank input yields {@code null}
     * (DBF date fields may be empty). Shared by the text and date cell editors so both validate and
     * store dates identically.
     */
    public static @Nullable Date parseDate(@NotNull String text) throws ParseException {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        SimpleDateFormat sdf = new SimpleDateFormat(DATE_PATTERN);
        sdf.setLenient(false);
        return sdf.parse(trimmed);
    }

    /**
     * Truncates {@code s} so that its {@code charset} encoding occupies at most {@code maxBytes}
     * bytes, never splitting a multibyte character. DBF character fields are sized in bytes, not in
     * characters, so a char-count limit lets multibyte text (e.g. Cyrillic in UTF-8) overflow the
     * field and be corrupted on write.
     */
    public static @NotNull String truncateToBytes(@Nullable String s, int maxBytes, @NotNull Charset charset) {
        if (s == null || maxBytes <= 0) {
            return "";
        }
        if (s.getBytes(charset).length <= maxBytes) {
            return s;
        }
        CharsetEncoder encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        CharBuffer in = CharBuffer.wrap(s);
        encoder.encode(in, ByteBuffer.allocate(maxBytes), true);
        return s.substring(0, in.position());
    }

    private static @NotNull String formatNumber(@NotNull Object value, int decimals) {
        try {
            BigDecimal bd = value instanceof BigDecimal ? (BigDecimal) value : new BigDecimal(value.toString().trim());
            if (decimals > 0) {
                return bd.setScale(decimals, RoundingMode.HALF_UP).toPlainString();
            }
            return bd.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return value.toString();
        }
    }
}
