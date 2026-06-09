package com.poliproger.dbfreader.ui;

import com.linuxense.javadbf.DBFDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure validation rules for a DBF column definition (field name, length, decimal count). Extracted
 * from {@link ColumnEditDialog} so the rules are unit-testable without Swing; methods that can fail
 * return the {@link com.poliproger.dbfreader.DbfBundle} key of the violation, and the dialog maps
 * it to a localized message next to the offending component.
 */
public final class DbfColumnValidator {

    /** xBase field names are limited to 10 characters. */
    public static final int MAX_NAME_LENGTH = 10;

    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Z0-9_]+");

    private DbfColumnValidator() {
    }

    /**
     * Validates a field name that has already been normalized to the DBF convention (trimmed,
     * upper-cased).
     *
     * @return the message key of the violation, or {@code null} if the name is valid
     */
    public static @Nullable String nameError(@NotNull String name, @NotNull Set<String> otherNames) {
        if (name.isEmpty()) {
            return "dialog.column.error.nameEmpty";
        }
        if (name.length() > MAX_NAME_LENGTH) {
            return "dialog.column.error.nameTooLong";
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            return "dialog.column.error.nameChars";
        }
        if (otherNames.contains(name)) {
            return "dialog.column.error.nameDuplicate";
        }
        return null;
    }

    /** Smallest legal field length for {@code type}, e.g. 8 for DATE, 1 for CHARACTER. */
    public static int minLength(@NotNull DBFDataType type) {
        return Math.max(1, type.getMinSize());
    }

    /**
     * Largest legal field length for {@code type}. CHARACTER is capped at the classic dBASE limit
     * of 254 bytes; types whose size javadbf does not report fall back to the same cap.
     */
    public static int maxLength(@NotNull DBFDataType type) {
        int max = type == DBFDataType.CHARACTER ? 254 : type.getMaxSize();
        return max <= 0 ? 254 : max;
    }

    public static boolean lengthValid(@NotNull DBFDataType type, int length) {
        return length >= minLength(type) && length <= maxLength(type);
    }

    /**
     * Whether {@code decimals} is legal for a field of the given type and length. With decimals the
     * stored form needs a decimal point plus at least one integer digit, so length must be
     * {@code >= decimals + 2}. With no decimals there is no point: length >= 1 is enough, so a
     * single-digit NUMERIC(1,0) is valid. Non-numeric types carry no decimals and always pass.
     */
    public static boolean decimalsValid(@NotNull DBFDataType type, int length, int decimals) {
        if (type != DBFDataType.NUMERIC && type != DBFDataType.FLOATING_POINT) {
            return true;
        }
        return decimals >= 0 && (decimals == 0 || decimals <= length - 2);
    }
}
