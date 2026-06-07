package com.poliproger.dbfreader.editor;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.poliproger.dbfreader.DbfFileType;
import org.jetbrains.annotations.NotNull;

/**
 * Opens {@code .dbf} files in the table editor and hides the default binary editor.
 */
public final class DbfFileEditorProvider implements FileEditorProvider, DumbAware {

    private static final String TYPE_ID = "dbf-table-editor";

    @Override
    public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
        if (file.isDirectory()) {
            return false;
        }
        return file.getFileType() instanceof DbfFileType || "dbf".equalsIgnoreCase(file.getExtension());
    }

    @Override
    public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
        return new DbfFileEditor(project, file);
    }

    @Override
    public @NotNull String getEditorTypeId() {
        return TYPE_ID;
    }

    @Override
    public @NotNull FileEditorPolicy getPolicy() {
        return FileEditorPolicy.HIDE_DEFAULT_EDITOR;
    }
}
