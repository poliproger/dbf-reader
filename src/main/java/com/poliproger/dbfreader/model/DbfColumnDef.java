package com.poliproger.dbfreader.model;

import com.linuxense.javadbf.DBFDataType;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Mutable definition of a single DBF field (column): name, data type and size.
 */
public final class DbfColumnDef {

    private String name;
    private DBFDataType type;
    private int length;
    private int decimalCount;

    public DbfColumnDef(@NotNull String name, @NotNull DBFDataType type, int length, int decimalCount) {
        this.name = name;
        this.type = type;
        this.length = length;
        this.decimalCount = decimalCount;
    }

    public @NotNull String getName() {
        return name;
    }

    public void setName(@NotNull String name) {
        this.name = name;
    }

    public @NotNull DBFDataType getType() {
        return type;
    }

    public void setType(@NotNull DBFDataType type) {
        this.type = type;
    }

    public int getLength() {
        return length;
    }

    public void setLength(int length) {
        this.length = length;
    }

    public int getDecimalCount() {
        return decimalCount;
    }

    public void setDecimalCount(int decimalCount) {
        this.decimalCount = decimalCount;
    }

    /**
     * @return whether values of this field's type can be written back by javadbf. Non-writable
     * types (memo, extended FoxPro/dBASE 7 types) are displayed read-only.
     */
    public boolean isWritable() {
        return type.isWriteSupported();
    }

    /**
     * Short label of the field's type and size for the column header, e.g. {@code "C (254)"} or,
     * for a numeric field with decimals, {@code "N (10,2)"}. The type is shown as its single-letter
     * DBF code.
     */
    public @NotNull String typeLabel() {
        char code = (char) type.getCode();
        if ((type == DBFDataType.NUMERIC || type == DBFDataType.FLOATING_POINT) && decimalCount > 0) {
            return code + " (" + length + "," + decimalCount + ")";
        }
        return code + " (" + length + ")";
    }

    /**
     * Whether {@code value}, rounded to this field's {@link #decimalCount}, fits the field when
     * written by javadbf. A DBF numeric length counts <em>every</em> character of the stored form:
     * the sign, the integer digits, the decimal point and the decimals (which are always padded out
     * to {@code decimalCount}). Measuring the raw value alone underestimates this, so we expand it to
     * the stored scale first.
     */
    public boolean fitsNumeric(@NotNull BigDecimal value) {
        return value.setScale(decimalCount, RoundingMode.HALF_UP).toPlainString().length() <= length;
    }

    public @NotNull DbfColumnDef copy() {
        return new DbfColumnDef(name, type, length, decimalCount);
    }
}
