package com.poliproger.dbfreader.model;

import org.jetbrains.annotations.NotNull;

/**
 * dBASE/xBase format variant identified by the first byte of the DBF header (the "version" /
 * file-type flag). That byte is a bit mask: bits 0-2 hold the dBASE level (3 = dBASE III), bit 3 a
 * dBASE IV memo, bits 4-6 an SQL-table flag and bit 7 the presence of any memo (.DBT/.FPT) file.
 * The known whole-byte values are enumerated here; anything else maps to {@link #UNKNOWN}.
 */
public enum DbfVersion {
    FOXBASE(0x02, "FoxBASE"),
    DBASE_III(0x03, "dBASE III (no memo)"),
    DBASE_7(0x04, "dBASE 7 (no memo)"),
    DBASE_V(0x05, "dBASE V (no memo)"),
    VISUAL_FOXPRO(0x30, "Visual FoxPro"),
    VISUAL_FOXPRO_AUTOINC(0x31, "Visual FoxPro (autoincrement)"),
    VISUAL_FOXPRO_VAR(0x32, "Visual FoxPro (varchar/varbinary)"),
    DBASE_IV_SQL_TABLE(0x43, "dBASE IV SQL table (no memo)"),
    DBASE_IV_SQL_SYSTEM(0x63, "dBASE IV SQL system (no memo)"),
    DBASE_IV_MEMO_7B(0x7B, "dBASE IV (with memo)"),
    DBASE_III_MEMO(0x83, "dBASE III+ (with memo)"),
    DBASE_IV_MEMO(0x8B, "dBASE IV (with memo)"),
    DBASE_IV_SQL_TABLE_MEMO(0xCB, "dBASE IV SQL table (with memo)"),
    HIPERSIX_MEMO(0xE5, "HiPer-Six (with SMT memo)"),
    FOXPRO_2_MEMO(0xF5, "FoxPro 2.x (with memo)"),
    FOXBASE_FB(0xFB, "FoxBASE"),
    UNKNOWN(-1, "Unknown");

    private final int signature;
    private final String displayName;

    DbfVersion(int signature, String displayName) {
        this.signature = signature;
        this.displayName = displayName;
    }

    /** The raw header version byte (0..255) this variant corresponds to, or -1 for {@link #UNKNOWN}. */
    public int getSignature() {
        return signature;
    }

    public @NotNull String getDisplayName() {
        return displayName;
    }

    /**
     * Maps the raw header version byte to a known variant, or {@link #UNKNOWN} if it is unrecognized.
     *
     * @param signature the first byte of the DBF header (only the low 8 bits are considered)
     */
    public static @NotNull DbfVersion fromSignature(int signature) {
        int b = signature & 0xFF;
        for (DbfVersion v : values()) {
            if (v.signature == b) {
                return v;
            }
        }
        return UNKNOWN;
    }
}