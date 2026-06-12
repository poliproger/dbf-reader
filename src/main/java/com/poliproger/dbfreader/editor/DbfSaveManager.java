package com.poliproger.dbfreader.editor;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.poliproger.dbfreader.DbfBundle;
import com.poliproger.dbfreader.io.DbfFileWriterService;
import com.poliproger.dbfreader.model.DbfDocument;
import com.poliproger.dbfreader.settings.DbfSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * The write-back side of {@link DbfFileEditor}: serializes the document, writes it via a
 * write command, makes the optional one-time {@code .bak} backup and runs the external-change
 * conflict check (a SHA-256 baseline of the bytes the in-memory model was loaded from).
 */
final class DbfSaveManager {

    /** Outcome of {@link #save}; tells the editor how to update its own state. */
    enum SaveResult {
        /** The file was written; the editor should clear its modified flag. */
        SAVED,
        /** The user chose to keep the externally changed file; the editor should reload from disk. */
        RELOAD_REQUESTED,
        /** Nothing was written — the user cancelled or the write failed (an error dialog was shown). */
        CANCELLED
    }

    private final Project project;
    private final VirtualFile file;

    /** Whether the one-time backup was already made in this session. */
    private boolean backupCreated;
    /**
     * SHA-256 of the on-disk file bytes the in-memory model is based on, captured when the document
     * was loaded and refreshed after each of our own saves. On save we re-hash the current file to
     * detect a change made by another program since we read it. {@code null} until the first
     * successful load.
     */
    private byte @Nullable [] baselineDigest;

    DbfSaveManager(@NotNull Project project, @NotNull VirtualFile file) {
        this.project = project;
        this.file = file;
    }

    /** Rebases the external-change check on {@code bytes} — the file content just read or written. */
    void rebaseline(byte @NotNull [] bytes) {
        baselineDigest = digest(bytes);
    }

    /**
     * Writes {@code document} back to the file, after the external-change conflict check and the
     * deleted-records confirmation. Shows its own error dialogs; the result tells the caller whether
     * the write happened or a reload was requested instead.
     */
    @NotNull SaveResult save(@NotNull DbfDocument document) {
        // Someone may have changed the file on disk since we read it. If so, let the user decide whether
        // to overwrite it with their edits or reload the on-disk version (discarding the in-memory edits).
        if (isModifiedOnDisk()) {
            int answer = Messages.showYesNoCancelDialog(project,
                    DbfBundle.message("save.conflict.message", file.getName()),
                    DbfBundle.message("save.conflict.title"),
                    DbfBundle.message("save.conflict.overwrite"),
                    DbfBundle.message("save.conflict.reload"),
                    Messages.getCancelButton(),
                    Messages.getWarningIcon());
            if (answer == Messages.NO) {
                return SaveResult.RELOAD_REQUESTED;
            }
            if (answer != Messages.YES) {
                return SaveResult.CANCELLED;
            }
            // YES: fall through and overwrite the file with our version.
        }
        // Records marked as deleted are skipped on read and cannot be written back by javadbf, so
        // rewriting the file removes them permanently. Confirm before that happens; once the file
        // has been rewritten the count is reset and the question is not asked again.
        int deleted = document.getDeletedRecordCount();
        if (deleted > 0) {
            int answer = Messages.showYesNoDialog(project,
                    DbfBundle.message("save.deleted.message", file.getName(), deleted),
                    DbfBundle.message("save.deleted.title"),
                    DbfBundle.message("save.deleted.saveAnyway"),
                    Messages.getCancelButton(),
                    Messages.getWarningIcon());
            if (answer != Messages.YES) {
                return SaveResult.CANCELLED;
            }
        }
        final byte[] bytes;
        try {
            bytes = DbfFileWriterService.write(document);
        } catch (Exception ex) {
            showSaveError(ex);
            return SaveResult.CANCELLED;
        }
        try {
            WriteCommandAction.writeCommandAction(project)
                    .withName(DbfBundle.message("action.save.text"))
                    .run(() -> {
                        try {
                            if (DbfSettings.getInstance().createBackupOnSave && !backupCreated) {
                                createBackup();
                                backupCreated = true;
                            }
                            file.setBinaryContent(bytes);
                        } catch (IOException io) {
                            throw new RuntimeException(io);
                        }
                    });
        } catch (RuntimeException ex) {
            showSaveError(ex.getCause() != null ? ex.getCause() : ex);
            return SaveResult.CANCELLED;
        }
        // The file on disk is now exactly these bytes; rebase the conflict check so our own save is
        // not later mistaken for an external change.
        baselineDigest = digest(bytes);
        // The rewritten file no longer contains the deleted-marked records, so stop warning about
        // them (cleared before the editor flips its modified flag, so the status bar refreshes
        // without them).
        document.setDeletedRecordCount(0);
        return SaveResult.SAVED;
    }

    private void createBackup() throws IOException {
        VirtualFile parent = file.getParent();
        if (parent == null) {
            return;
        }
        String backupName = file.getName() + ".bak";
        VirtualFile backup = parent.findChild(backupName);
        if (backup == null) {
            backup = parent.createChildData(this, backupName);
        }
        backup.setBinaryContent(readAllBytes());
    }

    /**
     * Whether the file on disk differs from the bytes our model was loaded from — i.e. another program
     * changed it in the meantime. Refreshes the VirtualFile from disk first so the check sees the actual
     * file system state rather than VFS-cached content. Returns {@code false} when there is no baseline
     * (load failed) or the current bytes cannot be read, so save is never blocked on an inconclusive check.
     */
    private boolean isModifiedOnDisk() {
        if (baselineDigest == null) {
            return false;
        }
        file.refresh(false, false);
        try {
            return !Arrays.equals(baselineDigest, digest(readAllBytes()));
        } catch (IOException ex) {
            return false;
        }
    }

    /**
     * Reads the whole file via its input stream rather than {@link VirtualFile#contentsToByteArray()},
     * which throws {@code FileTooBigException} above the IDE content-load limit — exactly the large-file
     * case this plugin must handle. The stream bypasses that limit.
     */
    private byte @NotNull [] readAllBytes() throws IOException {
        try (InputStream in = file.getInputStream()) {
            return in.readAllBytes();
        }
    }

    private void showSaveError(@NotNull Throwable ex) {
        Messages.showErrorDialog(project,
                ex.getMessage() == null ? ex.toString() : ex.getMessage(),
                DbfBundle.message("save.error.title"));
    }

    private static byte @NotNull [] digest(byte @NotNull [] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is a required algorithm on every JVM, so this never happens.
            throw new IllegalStateException(ex);
        }
    }
}
