package com.poliproger.dbfreader;

import com.linuxense.javadbf.DBFDataType;
import com.poliproger.dbfreader.ui.DbfColumnValidator;
import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The column-definition validation rules behind {@link com.poliproger.dbfreader.ui.ColumnEditDialog}.
 * Names arrive already normalized (trimmed, upper-cased) — the dialog does that before validating.
 */
public class DbfColumnValidatorTest {

    private static final Set<String> NO_OTHERS = Collections.emptySet();

    // ---- field name --------------------------------------------------------------------------

    @Test
    public void validNamePasses() {
        assertNull(DbfColumnValidator.nameError("NAME_2", NO_OTHERS));
        assertNull(DbfColumnValidator.nameError("ABCDEFGHIJ", NO_OTHERS)); // exactly 10 chars
    }

    @Test
    public void emptyNameIsRejected() {
        assertEquals("dialog.column.error.nameEmpty", DbfColumnValidator.nameError("", NO_OTHERS));
    }

    @Test
    public void nameLongerThanTenCharsIsRejected() {
        assertEquals("dialog.column.error.nameTooLong",
                DbfColumnValidator.nameError("ABCDEFGHIJK", NO_OTHERS));
    }

    @Test
    public void illegalCharactersAreRejected() {
        assertEquals("dialog.column.error.nameChars", DbfColumnValidator.nameError("BAD NAME", NO_OTHERS));
        assertEquals("dialog.column.error.nameChars", DbfColumnValidator.nameError("NAME-1", NO_OTHERS));
        assertEquals("dialog.column.error.nameChars", DbfColumnValidator.nameError("ИМЯ", NO_OTHERS));
    }

    @Test
    public void duplicateNameIsRejected() {
        Set<String> others = new HashSet<>(Set.of("NAME", "OTHER"));

        assertEquals("dialog.column.error.nameDuplicate", DbfColumnValidator.nameError("NAME", others));
        assertNull(DbfColumnValidator.nameError("NAME2", others));
    }

    // ---- length ------------------------------------------------------------------------------

    @Test
    public void characterLengthIsLimitedToClassicDbaseRange() {
        assertTrue(DbfColumnValidator.lengthValid(DBFDataType.CHARACTER, 1));
        assertTrue(DbfColumnValidator.lengthValid(DBFDataType.CHARACTER, 254));
        assertFalse(DbfColumnValidator.lengthValid(DBFDataType.CHARACTER, 0));
        assertFalse(DbfColumnValidator.lengthValid(DBFDataType.CHARACTER, 255));
    }

    @Test
    public void numericLengthIsLimitedByJavadbf() {
        assertTrue(DbfColumnValidator.lengthValid(DBFDataType.NUMERIC, 1));
        assertTrue(DbfColumnValidator.lengthValid(DBFDataType.NUMERIC, 32));
        assertFalse(DbfColumnValidator.lengthValid(DBFDataType.NUMERIC, 33));

        assertTrue(DbfColumnValidator.lengthValid(DBFDataType.FLOATING_POINT, 20));
        assertFalse(DbfColumnValidator.lengthValid(DBFDataType.FLOATING_POINT, 21));
    }

    @Test
    public void fixedSizeTypesAcceptOnlyTheirSize() {
        assertTrue(DbfColumnValidator.lengthValid(DBFDataType.DATE, 8));
        assertFalse(DbfColumnValidator.lengthValid(DBFDataType.DATE, 7));
        assertFalse(DbfColumnValidator.lengthValid(DBFDataType.DATE, 9));

        assertTrue(DbfColumnValidator.lengthValid(DBFDataType.LOGICAL, 1));
        assertFalse(DbfColumnValidator.lengthValid(DBFDataType.LOGICAL, 2));
    }

    // ---- decimals ----------------------------------------------------------------------------

    @Test
    public void decimalsNeedRoomForThePointAndAnIntegerDigit() {
        // The stored form is "<digits>.<decimals>": length must be >= decimals + 2.
        assertTrue(DbfColumnValidator.decimalsValid(DBFDataType.NUMERIC, 10, 2));
        assertTrue(DbfColumnValidator.decimalsValid(DBFDataType.NUMERIC, 4, 2));
        assertFalse(DbfColumnValidator.decimalsValid(DBFDataType.NUMERIC, 3, 2));
        assertFalse(DbfColumnValidator.decimalsValid(DBFDataType.FLOATING_POINT, 10, 9));
    }

    @Test
    public void zeroDecimalsFitAnyLength() {
        assertTrue(DbfColumnValidator.decimalsValid(DBFDataType.NUMERIC, 1, 0));
    }

    @Test
    public void negativeDecimalsAreRejected() {
        assertFalse(DbfColumnValidator.decimalsValid(DBFDataType.NUMERIC, 10, -1));
    }

    @Test
    public void decimalsAreIgnoredForNonNumericTypes() {
        assertTrue(DbfColumnValidator.decimalsValid(DBFDataType.CHARACTER, 10, 5));
        assertTrue(DbfColumnValidator.decimalsValid(DBFDataType.DATE, 8, 3));
    }
}
