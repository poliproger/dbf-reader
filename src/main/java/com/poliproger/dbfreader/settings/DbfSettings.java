package com.poliproger.dbfreader.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.Charset;

/**
 * Persistent, application-level settings for the DBF Reader plugin.
 * Backup-on-save is opt-in (disabled by default).
 */
@Service
@State(name = "DbfReaderSettings", storages = @Storage("dbf-reader.xml"))
public final class DbfSettings implements PersistentStateComponent<DbfSettings> {

    /** Charsets offered in the editor combo box and the settings page. */
    public static final Charset[] COMMON_CHARSETS = {
            Charset.forName("UTF-8"), Charset.forName("windows-1251"),
            Charset.forName("IBM866"), Charset.forName("windows-1252"), Charset.forName("ISO-8859-1")
    };

    /** When {@code true}, the original file is copied to {@code <name>.dbf.bak} before the first save. */
    public boolean createBackupOnSave = false;

    /**
     * Files larger than this many megabytes prompt a confirmation before opening, because the whole
     * file is read into memory. A value of {@code 0} disables the warning.
     */
    public int largeFileWarningThresholdMb = 20;

    /**
     * Fallback charset name used for character fields when a DBF file does not declare one
     * (language-driver byte is 0). A blank value means "use the JVM default charset".
     */
    public String defaultCharset = "";

    public static DbfSettings getInstance() {
        return ApplicationManager.getApplication().getService(DbfSettings.class);
    }

    /**
     * Resolves {@link #defaultCharset} to a usable {@link Charset}, falling back to the JVM default
     * when it is unset or names a charset not available on this JVM.
     */
    public @NotNull Charset resolveDefaultCharset() {
        if (defaultCharset != null && !defaultCharset.isBlank()) {
            try {
                return Charset.forName(defaultCharset);
            } catch (Exception ignored) {
                // Stored name is invalid/unsupported here; fall through to the system default.
            }
        }
        return Charset.defaultCharset();
    }

    @Override
    public DbfSettings getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull DbfSettings state) {
        XmlSerializerUtil.copyBean(state, this);
    }
}
