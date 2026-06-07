package com.poliproger.dbfreader;

import com.poliproger.dbfreader.model.DbfVersion;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class DbfVersionTest {

    @Test
    public void mapsKnownSignatures() {
        assertSame(DbfVersion.DBASE_III, DbfVersion.fromSignature(0x03));
        assertSame(DbfVersion.VISUAL_FOXPRO, DbfVersion.fromSignature(0x30));
        assertSame(DbfVersion.DBASE_III_MEMO, DbfVersion.fromSignature(0x83));
        assertSame(DbfVersion.FOXPRO_2_MEMO, DbfVersion.fromSignature(0xF5));
    }

    /** Signatures with the high bit set arrive as negative bytes; only the low 8 bits matter. */
    @Test
    public void masksToLowByte() {
        assertSame(DbfVersion.DBASE_III_MEMO, DbfVersion.fromSignature((byte) 0x83));
        assertSame(DbfVersion.FOXPRO_2_MEMO, DbfVersion.fromSignature((byte) 0xF5));
    }

    @Test
    public void unknownSignatureFallsBack() {
        assertSame(DbfVersion.UNKNOWN, DbfVersion.fromSignature(0x00));
        assertSame(DbfVersion.UNKNOWN, DbfVersion.fromSignature(-1));
        assertEquals("Unknown", DbfVersion.UNKNOWN.getDisplayName());
    }
}