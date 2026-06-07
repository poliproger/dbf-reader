package com.poliproger.dbfreader.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.util.ui.FormBuilder;
import com.poliproger.dbfreader.DbfBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.nio.charset.Charset;

/**
 * Settings page (Settings | Tools | DBF Reader) exposing the plugin preferences.
 */
public final class DbfSettingsConfigurable implements Configurable {

    private JBCheckBox backupCheckBox;
    private ComboBox<Charset> defaultCharsetCombo;
    private JPanel panel;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return DbfBundle.message("settings.displayName");
    }

    @Override
    public @Nullable JComponent createComponent() {
        backupCheckBox = new JBCheckBox(DbfBundle.message("settings.backup.checkbox"));
        backupCheckBox.setToolTipText(DbfBundle.message("settings.backup.tooltip"));

        defaultCharsetCombo = new ComboBox<>();
        defaultCharsetCombo.addItem(null); // null == "system default"
        for (Charset cs : DbfSettings.COMMON_CHARSETS) {
            defaultCharsetCombo.addItem(cs);
        }
        defaultCharsetCombo.setRenderer(SimpleListCellRenderer.create(
                DbfBundle.message("settings.defaultCharset.systemDefault"), Charset::displayName));
        defaultCharsetCombo.setToolTipText(DbfBundle.message("settings.defaultCharset.tooltip"));

        panel = FormBuilder.createFormBuilder()
                .addComponent(backupCheckBox)
                .addLabeledComponent(DbfBundle.message("settings.defaultCharset.label"), defaultCharsetCombo)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        return panel;
    }

    @Override
    public boolean isModified() {
        DbfSettings settings = DbfSettings.getInstance();
        return backupCheckBox.isSelected() != settings.createBackupOnSave
                || !selectedCharsetName().equals(storedCharsetName(settings));
    }

    @Override
    public void apply() {
        DbfSettings settings = DbfSettings.getInstance();
        settings.createBackupOnSave = backupCheckBox.isSelected();
        settings.defaultCharset = selectedCharsetName();
    }

    @Override
    public void reset() {
        DbfSettings settings = DbfSettings.getInstance();
        backupCheckBox.setSelected(settings.createBackupOnSave);
        Charset stored = parseStored(settings);
        if (stored != null) {
            ensureItem(stored);
        }
        defaultCharsetCombo.setSelectedItem(stored);
    }

    @Override
    public void disposeUIResources() {
        backupCheckBox = null;
        defaultCharsetCombo = null;
        panel = null;
    }

    /** Charset name for the current combo selection, or {@code ""} for the "system default" item. */
    private String selectedCharsetName() {
        Charset selected = (Charset) defaultCharsetCombo.getSelectedItem();
        return selected == null ? "" : selected.name();
    }

    private static String storedCharsetName(DbfSettings settings) {
        Charset stored = parseStored(settings);
        return stored == null ? "" : stored.name();
    }

    private static @Nullable Charset parseStored(DbfSettings settings) {
        String name = settings.defaultCharset;
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Charset.forName(name);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Adds a charset to the combo if it is not already one of the predefined items. */
    private void ensureItem(Charset charset) {
        for (int i = 0; i < defaultCharsetCombo.getItemCount(); i++) {
            if (charset.equals(defaultCharsetCombo.getItemAt(i))) {
                return;
            }
        }
        defaultCharsetCombo.addItem(charset);
    }
}
