package com.poliproger.dbfreader.model;

import com.linuxense.javadbf.DBFDataType;
import com.poliproger.dbfreader.ui.DbfValueFormatter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Best-effort conversion of a column's values when its type or size changes. Values that cannot be
 * represented in the new type/size are cleared (set to {@code null}); the count of cleared values is
 * reported so the UI can warn the user.
 */
public final class DbfTypeConverter {

    public static final class Result {
        public final List<Object> values;
        public final int clearedCount;
        /**
         * Number of CHARACTER values that were shortened to fit a smaller field. javadbf truncates
         * silently on write, so the UI warns about these too (distinct from {@link #clearedCount},
         * which counts values dropped entirely because they could not be represented at all).
         */
        public final int truncatedCount;

        Result(List<Object> values, int clearedCount, int truncatedCount) {
            this.values = values;
            this.clearedCount = clearedCount;
            this.truncatedCount = truncatedCount;
        }
    }

    private DbfTypeConverter() {
    }

    public static @NotNull Result convert(@NotNull List<Object> source,
                                          @NotNull DbfColumnDef oldDef,
                                          @NotNull DbfColumnDef newDef,
                                          @NotNull Charset charset) {
        List<Object> out = new ArrayList<>(source.size());
        int cleared = 0;
        int truncated = 0;
        for (Object original : source) {
            Object converted = convertOne(original, oldDef, newDef, charset);
            if (converted == null) {
                if (original != null && !isBlank(original)) {
                    cleared++;
                }
            } else if (newDef.getType() == DBFDataType.CHARACTER
                    && isTruncated(original, (String) converted, oldDef, charset)) {
                truncated++;
            }
            out.add(converted);
        }
        return new Result(out, cleared, truncated);
    }

    /**
     * Whether converting {@code original} into the new CHARACTER field dropped trailing bytes — its
     * displayed form was longer (in {@code charset} bytes) than the {@code converted} string that fits
     * the smaller field. Lets the caller warn about this otherwise-silent truncation.
     */
    private static boolean isTruncated(@Nullable Object original, @NotNull String converted,
                                       @NotNull DbfColumnDef oldDef, @NotNull Charset charset) {
        if (original == null) {
            return false;
        }
        String before = DbfValueFormatter.format(original, oldDef);
        return before.getBytes(charset).length > converted.getBytes(charset).length;
    }

    private static @Nullable Object convertOne(@Nullable Object value, DbfColumnDef oldDef,
                                               DbfColumnDef newDef, Charset charset) {
        if (value == null) {
            return null;
        }
        switch (newDef.getType()) {
            case CHARACTER:
                // DBF character length is measured in bytes; truncate accordingly so multibyte text
                // is not silently corrupted when javadbf re-encodes it on write.
                return DbfValueFormatter.truncateToBytes(
                        DbfValueFormatter.format(value, oldDef), newDef.getLength(), charset);
            case NUMERIC:
            case FLOATING_POINT: {
                try {
                    BigDecimal bd = value instanceof BigDecimal
                            ? (BigDecimal) value
                            : new BigDecimal(value.toString().trim());
                    if (bd.scale() > newDef.getDecimalCount()) {
                        bd = bd.setScale(newDef.getDecimalCount(), RoundingMode.HALF_UP);
                    }
                    return newDef.fitsNumeric(bd) ? bd : null;
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            case LOGICAL:
                return toBoolean(value);
            case DATE:
                if (value instanceof Date) {
                    return value;
                }
                try {
                    SimpleDateFormat sdf = new SimpleDateFormat(DbfValueFormatter.DATE_PATTERN);
                    sdf.setLenient(false);
                    return sdf.parse(value.toString().trim());
                } catch (ParseException e) {
                    return null;
                }
            default:
                return null;
        }
    }

    private static @Nullable Boolean toBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        String s = value.toString().trim().toLowerCase();
        if (s.equals("t") || s.equals("y") || s.equals("true") || s.equals("1")) {
            return Boolean.TRUE;
        }
        if (s.equals("f") || s.equals("n") || s.equals("false") || s.equals("0")) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static boolean isBlank(Object value) {
        return value.toString().trim().isEmpty();
    }
}
