package com.poliproger.dbfreader;

import com.poliproger.dbfreader.settings.DbfSettings;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Locks the default values of {@link DbfSettings} that user-facing behavior depends on: backup off by
 * default, and a 20 MB large-file warning threshold.
 */
public class DbfSettingsTest {

    @Test
    public void defaults() {
        DbfSettings settings = new DbfSettings();

        assertFalse(settings.createBackupOnSave);
        assertEquals(20, settings.largeFileWarningThresholdMb);
    }
}
