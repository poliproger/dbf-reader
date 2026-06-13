package com.poliproger.dbfreader.editor;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LargeFileWriteRequestor;
import com.intellij.openapi.vfs.VirtualFile;
import com.poliproger.dbfreader.DbfBundle;
import com.poliproger.dbfreader.settings.DbfSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * The write-back side of {@link DbfFileEditor}: the optional one-time {@code .bak} backup, the
 * external-change conflict check (a SHA-256 baseline of the bytes the in-memory model was loaded
 * from) and the final write command.
 *
 * <p>Exposes the save steps individually rather than one blocking {@code save()} so the editor can
 * run the heavy parts — the disk re-read/hash of {@link #isModifiedOnDisk()} and (in the editor)
 * the document serialization to a temp file — off the EDT, and keep only the user dialogs and the
 * {@link #commit(Path) write command} (which must run on the EDT) there. {@code commit} streams the
 * serialized temp file into the VFS rather than taking the whole content as a {@code byte[]}, so a
 * large {@code .dbf} is never materialized in the heap on the way out.
 *
 * <p>Implements {@link LargeFileWriteRequestor} and passes {@code this} as the write requestor so
 * {@code setBinaryContent} skips its "file too large" guard: a {@code .dbf} can legitimately exceed
 * the VFS size limit (it is opened via the background large-file path), and without this the write
 * fails with {@code FileTooBigException}.
 */
final class DbfSaveManager implements LargeFileWriteRequestor {

    private static final Logger LOG = Logger.getInstance(DbfSaveManager.class);

    /** The user's answer to the external-change conflict prompt ({@link #askConflict()}). */
    enum ConflictChoice {
        /** Overwrite the on-disk file with the in-memory edits. */
        OVERWRITE,
        /** Discard the in-memory edits and reload the on-disk version. */
        RELOAD,
        /** Do nothing. */
        CANCEL
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
    /**
     * File size and modification time captured alongside {@link #baselineDigest}, so the (expensive)
     * re-read+hash on save can be skipped when the cheap stamps are unchanged — the common case when
     * nothing external touched the file. {@code -1} until the first baseline.
     */
    private long baselineLength = -1;
    private long baselineTimeStamp = -1;

    DbfSaveManager(@NotNull Project project, @NotNull VirtualFile file) {
        this.project = project;
        this.file = file;
    }

    /** Rebases the external-change check on {@code bytes} — the file content just read on load. */
    void rebaseline(byte @NotNull [] bytes) {
        setBaseline(digest(bytes));
    }

    /**
     * Rebases the external-change check on a digest already computed elsewhere — used by {@link
     * #commit(Path)}, which hashes the bytes while streaming them out so it never holds the whole
     * content to re-hash it here.
     */
    private void setBaseline(byte @NotNull [] computedDigest) {
        baselineDigest = computedDigest;
        // Capture the cheap stamps too: right after a read/our own write the VirtualFile reflects this
        // content, so these match it. isModifiedOnDisk compares them first to avoid re-hashing.
        baselineLength = file.getLength();
        baselineTimeStamp = file.getTimeStamp();
    }

    /**
     * Asks the user how to resolve an external change to the file: another program changed it on disk
     * since we read it, so let them overwrite it with their edits or reload the on-disk version
     * (discarding the in-memory edits). Shows a modal dialog — call on the EDT.
     */
    @NotNull ConflictChoice askConflict() {
        int answer = Messages.showYesNoCancelDialog(project,
                DbfBundle.message("save.conflict.message", file.getName()),
                DbfBundle.message("save.conflict.title"),
                DbfBundle.message("save.conflict.overwrite"),
                DbfBundle.message("save.conflict.reload"),
                Messages.getCancelButton(),
                Messages.getWarningIcon());
        if (answer == Messages.YES) {
            return ConflictChoice.OVERWRITE;
        }
        if (answer == Messages.NO) {
            return ConflictChoice.RELOAD;
        }
        return ConflictChoice.CANCEL;
    }

    /**
     * Confirms removing the records marked as deleted: they are skipped on read and cannot be written
     * back by javadbf, so rewriting the file drops them permanently. Returns {@code true} to proceed.
     * Shows a modal dialog — call on the EDT.
     */
    boolean confirmDeletedRecords(int deleted) {
        int answer = Messages.showYesNoDialog(project,
                DbfBundle.message("save.deleted.message", file.getName(), deleted),
                DbfBundle.message("save.deleted.title"),
                DbfBundle.message("save.deleted.saveAnyway"),
                Messages.getCancelButton(),
                Messages.getWarningIcon());
        return answer == Messages.YES;
    }

    /**
     * Streams the already-serialized temp file {@code source} into the file inside a write command (and
     * makes the one-time backup first, if enabled), then rebases the conflict check on it so our own
     * write is not later mistaken for an external change. Must run on the EDT (it takes a write action);
     * propagates the failure (wrapped in a {@code RuntimeException}) to the caller, which reports it via
     * {@link #showSaveError}.
     *
     * <p>Copies {@code source} through to {@link VirtualFile#getOutputStream}{@code (this, -1, -1)} rather
     * than handing the whole content to {@code setBinaryContent}: {@code setBinaryContent(bytes, .., this)}
     * is itself just {@code getOutputStream(this, ..).write(bytes)}, so the {@link LargeFileWriteRequestor}
     * file-too-large bypass works identically — but the bytes are never materialized as one heap array on
     * our side. A {@link DigestInputStream} hashes them en route, so the conflict baseline is captured
     * without a separate re-read.
     */
    void commit(@NotNull Path source) {
        MessageDigest digest = newDigest();
        WriteCommandAction.writeCommandAction(project)
                .withName(DbfBundle.message("action.save.text"))
                .run(() -> {
                    try {
                        if (DbfSettings.getInstance().createBackupOnSave && !backupCreated) {
                            createBackup();
                            backupCreated = true;
                        }
                        // out is closed first (it is declared last): its close() flushes the content to
                        // disk and fires the VFS change event before the source stream is released.
                        try (DigestInputStream in = new DigestInputStream(Files.newInputStream(source), digest);
                             OutputStream out = file.getOutputStream(this, -1L, -1L)) {
                            in.transferTo(out);
                        }
                    } catch (IOException io) {
                        throw new RuntimeException(io);
                    }
                });
        // The file on disk is now the streamed bytes; rebase the conflict check on the digest computed
        // en route (+ cheap stamps) — only reached when the write command above did not throw.
        setBaseline(digest.digest());
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
        // Bypass the VFS file-too-large guard (see commit); the backup of a large .dbf is just as large.
        backup.setBinaryContent(readAllBytes(), -1L, -1L, this);
    }

    /**
     * Whether the file on disk differs from the bytes our model was loaded from — i.e. another program
     * changed it in the meantime. Refreshes the VirtualFile from disk first so the check sees the actual
     * file system state rather than VFS-cached content. Returns {@code false} when there is no baseline
     * (load failed) or the current bytes cannot be read, so save is never blocked on an inconclusive check.
     *
     * <p>May re-read and hash the whole file, so the editor runs it off the EDT — but only when the
     * cheap size/mtime pre-check below indicates a possible change.
     */
    boolean isModifiedOnDisk() {
        if (baselineDigest == null) {
            return false;
        }
        file.refresh(false, false);
        // Cheap pre-check: if the size and modification time still match the baseline, the bytes are
        // unchanged and the full re-read+hash (costly on a large .dbf, run on every save) is skipped.
        // A mismatch falls through to the hash, which also rules out a spurious change — a stamp that
        // moved while the content stayed identical (e.g. a tool rewrote the same bytes) — so it does not
        // raise a false conflict. The narrow gap this leaves (an external edit that preserves both size
        // and mtime) is negligible: modern filesystems bump mtime on any write.
        if (file.getLength() == baselineLength && file.getTimeStamp() == baselineTimeStamp) {
            return false;
        }
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

    void showSaveError(@NotNull Throwable ex) {
        // Log the full stack so a save failure is diagnosable from idea.log; the dialog shows only the
        // message. warn (not error) keeps it out of the "IDE internal error" UI for routine failures
        // (e.g. a read-only file).
        LOG.warn("Failed to save DBF file " + file.getName(), ex);
        Messages.showErrorDialog(project,
                ex.getMessage() == null ? ex.toString() : ex.getMessage(),
                DbfBundle.message("save.error.title"));
    }

    private static byte @NotNull [] digest(byte @NotNull [] bytes) {
        return newDigest().digest(bytes);
    }

    private static @NotNull MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is a required algorithm on every JVM, so this never happens.
            throw new IllegalStateException(ex);
        }
    }
}
