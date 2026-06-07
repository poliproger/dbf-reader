package com.poliproger.dbfreader;

import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.util.IconLoader;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

/**
 * Binary file type for {@code .dbf} files. Marked binary so the platform does not try to load it as
 * text; the dedicated {@code DbfFileEditorProvider} opens it in a table editor instead.
 */
public final class DbfFileType implements FileType {

    public static final DbfFileType INSTANCE = new DbfFileType();

    private static final Icon ICON = IconLoader.getIcon("/icons/dbf.svg", DbfFileType.class);

    private DbfFileType() {
    }

    @Override
    public @NonNls @NotNull String getName() {
        return "DBF File";
    }

    @Override
    public @Nls @NotNull String getDescription() {
        return DbfBundle.message("filetype.dbf.description");
    }

    @Override
    public @NonNls @NotNull String getDefaultExtension() {
        return "dbf";
    }

    @Override
    public @Nullable Icon getIcon() {
        return ICON;
    }

    @Override
    public boolean isBinary() {
        return true;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }
}
