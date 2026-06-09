package com.poliproger.dbfreader.editor;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ShortcutSet;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.fileEditor.FileEditorStateLevel;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.JBUI;
import com.poliproger.dbfreader.DbfBundle;
import com.poliproger.dbfreader.io.DbfFileReaderService;
import com.poliproger.dbfreader.io.DbfFileWriterService;
import com.poliproger.dbfreader.model.DbfColumnDef;
import com.poliproger.dbfreader.model.DbfDocument;
import com.poliproger.dbfreader.model.DbfTableModel;
import com.poliproger.dbfreader.model.DbfTypeConverter;
import com.poliproger.dbfreader.settings.DbfSettings;
import com.poliproger.dbfreader.ui.ColumnEditDialog;
import com.poliproger.dbfreader.ui.DbfHeaderRenderer;
import com.poliproger.dbfreader.ui.DbfTsvExporter;
import com.poliproger.dbfreader.ui.RowNumberTable;
import com.poliproger.dbfreader.ui.cell.DbfBooleanCellEditor;
import com.poliproger.dbfreader.ui.cell.DbfBooleanCellRenderer;
import com.poliproger.dbfreader.ui.cell.DbfCellRenderer;
import com.poliproger.dbfreader.ui.cell.DbfDateCellEditor;
import com.poliproger.dbfreader.ui.cell.DbfTextCellEditor;
import com.linuxense.javadbf.DBFDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.datatransfer.StringSelection;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Table editor for a {@code .dbf} file. Loads the whole file into a {@link DbfDocument}, presents it
 * in a {@link JBTable} with type-aware renderers/editors, and writes the document back on save
 * (javadbf rewrites the file as a whole — there is no in-place editing).
 */
public final class DbfFileEditor extends UserDataHolderBase implements FileEditor {

    private final Project project;
    private final VirtualFile file;
    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);

    private final JPanel rootPanel = new JPanel(new BorderLayout());
    private final JBTable table = new JBTable();
    private final JBLabel statusLabel = new JBLabel();
    private final ComboBox<Charset> encodingCombo = new ComboBox<>();
    private final DbfSearchController search = new DbfSearchController(table, this);
    private final DbfColumnNavigator columnNavigator = new DbfColumnNavigator(table);

    private DbfTableModel model;
    private boolean modified;
    private boolean loadError;
    private boolean backupCreated;
    private boolean suppressEncodingEvent;
    /**
     * SHA-256 of the on-disk file bytes our in-memory model is based on, captured when the document was
     * loaded and refreshed after each of our own saves. On save we re-hash the current file to detect a
     * change made by another program since we read it. {@code null} until the first successful load.
     */
    private byte @Nullable [] baselineDigest;

    public DbfFileEditor(@NotNull Project project, @NotNull VirtualFile file) {
        this.project = project;
        this.file = file;

        table.setAutoResizeMode(JBTable.AUTO_RESIZE_OFF);
        table.setCellSelectionEnabled(true);
        table.getSelectionModel().setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        boolean ok = loadDocument(null);
        buildUi();
        installHeaderRenderer();
        if (ok) {
            installColumnRenderers();
            fitColumnWidthsToHeader();
            updateStatus();
        }
        registerSaveShortcut();
        registerSearchShortcuts();
        registerColumnNavShortcut();
        registerCopyShortcut();
        subscribeToClose();
    }

    // ---- loading ---------------------------------------------------------------------------

    private boolean loadDocument(@Nullable Charset charsetOverride) {
        try {
            byte[] bytes = file.contentsToByteArray();
            DbfDocument document = DbfFileReaderService.read(bytes, charsetOverride,
                    DbfSettings.getInstance().resolveDefaultCharset());
            baselineDigest = digest(bytes);
            model = new DbfTableModel(document);
            // Drop any active row filter before swapping the model: the sorter is bound to the old model
            // and JTable would not rebind it. onModelChanged() below re-applies the filter to the new one.
            search.detachRowSorter();
            table.setModel(model);
            model.addTableModelListener(e -> setModified(true));
            model.addTableModelListener(e -> search.onModelChanged());
            search.onModelChanged();
            populateEncodingCombo(document.getCharset());
            loadError = false;
            return true;
        } catch (Exception ex) {
            loadError = true;
            Messages.showErrorDialog(project,
                    ex.getMessage() == null ? ex.toString() : ex.getMessage(),
                    DbfBundle.message("read.error.title"));
            return false;
        }
    }

    private void populateEncodingCombo(@NotNull Charset current) {
        suppressEncodingEvent = true;
        encodingCombo.removeAllItems();
        Set<Charset> charsets = new LinkedHashSet<>();
        charsets.add(current);
        for (Charset cs : DbfSettings.COMMON_CHARSETS) {
            charsets.add(cs);
        }
        for (Charset cs : charsets) {
            encodingCombo.addItem(cs);
        }
        encodingCombo.setSelectedItem(current);
        suppressEncodingEvent = false;
    }

    // ---- UI --------------------------------------------------------------------------------

    private void buildUi() {
        rootPanel.add(buildTopPanel(), BorderLayout.NORTH);

        JBScrollPane scrollPane = new JBScrollPane(table);
        // Pinned row-number gutter: scrolls vertically with the table, stays fixed on horizontal scroll.
        scrollPane.setRowHeaderView(new RowNumberTable(table));
        rootPanel.add(scrollPane, BorderLayout.CENTER);

        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        rootPanel.add(statusLabel, BorderLayout.SOUTH);
    }

    private JComponent buildTopPanel() {
        JPanel toolbarRow = new JPanel(new BorderLayout());

        ActionToolbar toolbar = ActionManager.getInstance()
                .createActionToolbar("DbfEditorToolbar", buildActions(), true);
        toolbar.setTargetComponent(table);
        toolbarRow.add(toolbar.getComponent(), BorderLayout.WEST);

        JPanel encodingPanel = new JPanel(new BorderLayout(4, 0));
        encodingPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        encodingPanel.add(new JBLabel(DbfBundle.message("editor.encoding.label")), BorderLayout.WEST);
        encodingCombo.addActionListener(e -> onEncodingChanged());
        encodingPanel.add(encodingCombo, BorderLayout.EAST);
        toolbarRow.add(encodingPanel, BorderLayout.EAST);

        // Toolbar row on top; the Cmd-F search bar (hidden until activated) sits directly below it.
        JPanel north = new JPanel(new BorderLayout());
        north.add(toolbarRow, BorderLayout.NORTH);
        north.add(search.getComponent(), BorderLayout.SOUTH);
        return north;
    }

    private DefaultActionGroup buildActions() {
        DefaultActionGroup group = new DefaultActionGroup();
        TableAction findButton = new TableAction(DbfBundle.message("action.find.text"),
                DbfBundle.message("action.find.description"), AllIcons.Actions.Find) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                search.activate();
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(!loadError);
            }
        };
        // Surface the IDE Find shortcut (e.g. Cmd-F) in the button's tooltip. The toolbar's ActionButton
        // reads the keystroke from the action's own shortcut set, so copy the IDE Find action's shortcuts
        // onto this button. This is display-only — it binds no handler; the active binding stays on the
        // panel (see registerSearchShortcuts), so the shortcut is not triggered twice.
        AnAction ideFind = ActionManager.getInstance().getAction("Find");
        if (ideFind != null) {
            findButton.setShortcutSet(ideFind.getShortcutSet());
        }
        group.add(findButton);
        group.addSeparator();
        group.add(new TableAction(DbfBundle.message("action.save.text"), DbfBundle.message("action.save.description"),
                AllIcons.Actions.MenuSaveall) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                save();
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(modified && !loadError
                        && !DbfFileWriterService.hasUnwritableColumns(model.getDocument()));
            }
        });
        group.addSeparator();
        group.add(new TableAction(DbfBundle.message("action.addRow.text"), DbfBundle.message("action.addRow.description"),
                AllIcons.General.Add) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                addRow();
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(!loadError);
            }
        });
        group.add(new TableAction(DbfBundle.message("action.deleteRow.text"), DbfBundle.message("action.deleteRow.description"),
                AllIcons.General.Remove) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                deleteSelectedRows();
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(!loadError && table.getSelectedRowCount() > 0);
            }
        });
        group.addSeparator();
        group.add(new TableAction(DbfBundle.message("action.addColumn.text"), DbfBundle.message("action.addColumn.description"),
                AllIcons.General.Add) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                addColumn();
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(!loadError);
            }
        });
        group.add(new TableAction(DbfBundle.message("action.editColumn.text"), DbfBundle.message("action.editColumn.description"),
                AllIcons.Actions.Edit) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                editSelectedColumn();
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(!loadError && table.getSelectedColumn() >= 0);
            }
        });
        group.add(new TableAction(DbfBundle.message("action.deleteColumn.text"), DbfBundle.message("action.deleteColumn.description"),
                AllIcons.General.Remove) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                deleteSelectedColumn();
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(!loadError && table.getSelectedColumn() >= 0
                        && model.getColumnCount() > 1);
            }
        });
        return group;
    }

    /**
     * Installs the type-aware renderer/editor on each column. Must be re-run after every structural
     * change because {@code fireTableStructureChanged()} recreates the table's column model.
     */
    private void installColumnRenderers() {
        DbfCellRenderer renderer = new DbfCellRenderer(search);
        for (int i = 0; i < model.getColumnCount(); i++) {
            DbfColumnDef def = model.getColumnDef(i);
            TableColumn column = table.getColumnModel().getColumn(i);
            // Logical columns use a renderer wrapping the default checkbox renderer (so search matches are
            // shaded there too) and a checkbox editor that toggles only on a click on the box itself, not
            // anywhere in the cell.
            if (def.getType() == DBFDataType.LOGICAL) {
                column.setCellRenderer(new DbfBooleanCellRenderer(table.getDefaultRenderer(Boolean.class), search));
                column.setCellEditor(new DbfBooleanCellEditor());
                continue;
            }
            column.setCellRenderer(renderer);
            if (def.isWritable()) {
                if (def.getType() == DBFDataType.DATE) {
                    column.setCellEditor(new DbfDateCellEditor(def));
                } else {
                    column.setCellEditor(new DbfTextCellEditor(def, model.getDocument().getCharset()));
                }
            }
        }
    }

    /**
     * Sizes every column to fit its header so the whole header is visible, instead of all columns
     * starting at the same default width. Runs once when the file is opened.
     */
    private void fitColumnWidthsToHeader() {
        for (int i = 0; i < table.getColumnModel().getColumnCount(); i++) {
            fitColumnWidthToHeader(i);
        }
    }

    /**
     * Sizes a single column to fit its header — the field name plus the type/size label appended by
     * {@link DbfHeaderRenderer} (e.g. {@code C (254)}). Measures the header only, never the data cells.
     */
    private void fitColumnWidthToHeader(int viewColumn) {
        JTableHeader header = table.getTableHeader();
        if (header == null) {
            return;
        }
        TableCellRenderer headerRenderer = header.getDefaultRenderer();
        TableColumn column = table.getColumnModel().getColumn(viewColumn);
        Component comp = headerRenderer.getTableCellRendererComponent(
                table, column.getHeaderValue(), false, false, -1, viewColumn);
        column.setPreferredWidth(comp.getPreferredSize().width + JBUI.scale(8));
    }

    /** Snapshots the current preferred width of each column, keyed by field name. */
    private Map<String, Integer> currentColumnWidths() {
        Map<String, Integer> widths = new HashMap<>();
        for (int i = 0; i < table.getColumnModel().getColumnCount(); i++) {
            TableColumn column = table.getColumnModel().getColumn(i);
            widths.put(model.getColumnDef(column.getModelIndex()).getName(), column.getPreferredWidth());
        }
        return widths;
    }

    /**
     * Restores the widths captured by {@link #currentColumnWidths()}, matching columns by field name.
     * A column with no captured width (e.g. a freshly added one) is left untouched.
     */
    private void restoreColumnWidths(Map<String, Integer> widths) {
        for (int i = 0; i < table.getColumnModel().getColumnCount(); i++) {
            TableColumn column = table.getColumnModel().getColumn(i);
            Integer width = widths.get(model.getColumnDef(column.getModelIndex()).getName());
            if (width != null) {
                column.setPreferredWidth(width);
            }
        }
    }

    /**
     * Installs the header renderer that appends each field's type/size label in a muted color. Set on
     * the (persistent) {@code JTableHeader} rather than per-column, so it survives the column-model
     * recreation that {@code fireTableStructureChanged()} triggers and needs installing only once.
     */
    private void installHeaderRenderer() {
        JTableHeader header = table.getTableHeader();
        if (header != null && !(header.getDefaultRenderer() instanceof DbfHeaderRenderer)) {
            header.setDefaultRenderer(new DbfHeaderRenderer(header.getDefaultRenderer()));
        }
    }

    // ---- row operations --------------------------------------------------------------------

    private void addRow() {
        stopEditing();
        model.addRow();
        int last = table.convertRowIndexToView(model.getRowCount() - 1);
        if (last >= 0) {
            table.getSelectionModel().setSelectionInterval(last, last);
            table.scrollRectToVisible(table.getCellRect(last, 0, true));
        }
    }

    private void deleteSelectedRows() {
        stopEditing();
        int[] viewRows = table.getSelectedRows();
        if (viewRows.length == 0) {
            return;
        }
        int[] modelRows = new int[viewRows.length];
        for (int i = 0; i < viewRows.length; i++) {
            modelRows[i] = table.convertRowIndexToModel(viewRows[i]);
        }
        model.removeRows(modelRows);
    }

    // ---- column operations -----------------------------------------------------------------

    private void addColumn() {
        stopEditing();
        ColumnEditDialog dialog = new ColumnEditDialog(project, null, columnNames(-1));
        if (!dialog.showAndGet()) {
            return;
        }
        // fireTableStructureChanged() rebuilds the column model and resets every column to the default
        // width, so capture the current widths, restore them for the existing columns afterwards, and
        // size only the new (last) column to its header.
        Map<String, Integer> widths = currentColumnWidths();
        model.addColumn(dialog.getResult(), null);
        installColumnRenderers();
        search.onModelChanged();
        restoreColumnWidths(widths);
        fitColumnWidthToHeader(table.getColumnModel().getColumnCount() - 1);
    }

    private void editSelectedColumn() {
        stopEditing();
        int viewColumn = table.getSelectedColumn();
        if (viewColumn < 0) {
            return;
        }
        int modelColumn = table.convertColumnIndexToModel(viewColumn);
        DbfColumnDef oldDef = model.getColumnDef(modelColumn).copy();

        ColumnEditDialog dialog = new ColumnEditDialog(project, oldDef, columnNames(modelColumn));
        if (!dialog.showAndGet()) {
            return;
        }
        DbfColumnDef newDef = dialog.getResult();

        List<Object> source = model.getDocument().getRows().stream()
                .map(r -> r.get(modelColumn))
                .collect(Collectors.toList());
        DbfTypeConverter.Result result =
                DbfTypeConverter.convert(source, oldDef, newDef, model.getDocument().getCharset());

        Map<String, Integer> widths = currentColumnWidths();
        // The edited column may have been renamed; carry its width over to the new name so the
        // structural change keeps every column (including this one) at its current width.
        Integer editedWidth = widths.get(oldDef.getName());
        if (editedWidth != null) {
            widths.put(newDef.getName(), editedWidth);
        }
        model.updateColumn(modelColumn, newDef, result.values);
        installColumnRenderers();
        search.onModelChanged();
        restoreColumnWidths(widths);

        if (result.clearedCount > 0) {
            Messages.showWarningDialog(project,
                    DbfBundle.message("convert.warning.message"),
                    DbfBundle.message("convert.warning.title"));
        }
    }

    private void deleteSelectedColumn() {
        stopEditing();
        int viewColumn = table.getSelectedColumn();
        if (viewColumn < 0 || model.getColumnCount() <= 1) {
            return;
        }
        int modelColumn = table.convertColumnIndexToModel(viewColumn);
        Map<String, Integer> widths = currentColumnWidths();
        model.removeColumn(modelColumn);
        installColumnRenderers();
        search.onModelChanged();
        restoreColumnWidths(widths);
    }

    /** Names of all columns except the one at {@code excludeIndex} (use -1 to exclude none). */
    private Set<String> columnNames(int excludeIndex) {
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < model.getColumnCount(); i++) {
            if (i != excludeIndex) {
                names.add(model.getColumnDef(i).getName().toUpperCase(Locale.ROOT));
            }
        }
        return names;
    }

    // ---- copy ------------------------------------------------------------------------------

    /** Puts the current cell selection on the clipboard as TSV; a no-op when nothing is selected. */
    private void copySelectionToClipboard() {
        String tsv = selectionAsTsv();
        if (tsv != null) {
            CopyPasteManager.getInstance().setContents(new StringSelection(tsv));
        }
    }

    /**
     * The current cell selection as tab-separated text, in the on-screen order (honouring an active
     * row filter and any column reordering), or {@code null} when nothing is selected. Values are
     * rendered exactly as the table shows them — see {@link DbfTsvExporter}.
     */
    private @Nullable String selectionAsTsv() {
        int[] viewRows = table.getSelectedRows();
        int[] viewColumns = table.getSelectedColumns();
        if (viewRows.length == 0 || viewColumns.length == 0) {
            return null;
        }
        int[] modelRows = new int[viewRows.length];
        for (int i = 0; i < viewRows.length; i++) {
            modelRows[i] = table.convertRowIndexToModel(viewRows[i]);
        }
        int[] modelColumns = new int[viewColumns.length];
        for (int i = 0; i < viewColumns.length; i++) {
            modelColumns[i] = table.convertColumnIndexToModel(viewColumns[i]);
        }
        return DbfTsvExporter.toTsv(model.getDocument(), modelRows, modelColumns);
    }

    // ---- encoding --------------------------------------------------------------------------

    private void onEncodingChanged() {
        if (suppressEncodingEvent) {
            return;
        }
        Charset selected = (Charset) encodingCombo.getSelectedItem();
        if (selected == null || model == null || selected.equals(model.getDocument().getCharset())) {
            return;
        }
        if (modified) {
            int answer = Messages.showYesNoDialog(project,
                    DbfBundle.message("encoding.confirm.message"),
                    DbfBundle.message("encoding.confirm.title"), null);
            if (answer != Messages.YES) {
                // revert selection without re-triggering
                suppressEncodingEvent = true;
                encodingCombo.setSelectedItem(model.getDocument().getCharset());
                suppressEncodingEvent = false;
                return;
            }
        }
        Charset previous = model.getDocument().getCharset();
        // Re-reading swaps in a fresh model, and table.setModel() rebuilds the column model with default
        // widths. The structure is unchanged (only character fields decode differently), so capture the
        // current widths and restore them by field name afterwards.
        Map<String, Integer> widths = currentColumnWidths();
        if (loadDocument(selected)) {
            installColumnRenderers();
            restoreColumnWidths(widths);
            setModified(false);
            updateStatus();
        } else {
            // Re-read with the new charset failed; the previous document is still loaded and valid, so
            // clear the error state and put the combo back instead of leaving the editor disabled.
            loadError = false;
            suppressEncodingEvent = true;
            encodingCombo.setSelectedItem(previous);
            suppressEncodingEvent = false;
        }
    }

    // ---- saving ----------------------------------------------------------------------------

    private void save() {
        if (loadError) {
            return;
        }
        // Commit any in-progress cell edit before checking `modified`: the SaveAll shortcut can reach
        // save() while a cell editor is still open (toolbar buttons aren't focusable, so the edit is
        // not auto-committed on focus loss), and testing `modified` first would make such a save a
        // silent no-op that drops the typed value.
        stopEditing();
        if (!modified) {
            return;
        }
        if (DbfFileWriterService.hasUnwritableColumns(model.getDocument())) {
            return;
        }
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
                reloadFromDisk();
                return;
            }
            if (answer != Messages.YES) {
                return;
            }
            // YES: fall through and overwrite the file with our version.
        }
        final byte[] bytes;
        try {
            bytes = DbfFileWriterService.write(model.getDocument());
        } catch (Exception ex) {
            Messages.showErrorDialog(project,
                    ex.getMessage() == null ? ex.toString() : ex.getMessage(),
                    DbfBundle.message("save.error.title"));
            return;
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
            // The file on disk is now exactly these bytes; rebase the conflict check so our own save is
            // not later mistaken for an external change.
            baselineDigest = digest(bytes);
            setModified(false);
        } catch (RuntimeException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            Messages.showErrorDialog(project,
                    cause.getMessage() == null ? cause.toString() : cause.getMessage(),
                    DbfBundle.message("save.error.title"));
        }
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
        backup.setBinaryContent(file.contentsToByteArray());
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
            return !Arrays.equals(baselineDigest, digest(file.contentsToByteArray()));
        } catch (IOException ex) {
            return false;
        }
    }

    /**
     * Discards the in-memory edits and reloads the document from the current on-disk bytes, preserving the
     * active encoding and column widths (the structure is normally unchanged). Used when the user opts to
     * keep the externally changed file instead of overwriting it.
     */
    private void reloadFromDisk() {
        Charset current = model != null ? model.getDocument().getCharset() : null;
        Map<String, Integer> widths = currentColumnWidths();
        if (loadDocument(current)) {
            installColumnRenderers();
            restoreColumnWidths(widths);
            setModified(false);
            updateStatus();
        }
    }

    private static byte @NotNull [] digest(byte @NotNull [] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is a required algorithm on every JVM, so this never happens.
            throw new IllegalStateException(ex);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private void stopEditing() {
        if (table.isEditing() && table.getCellEditor() != null) {
            table.getCellEditor().stopCellEditing();
        }
    }

    private void setModified(boolean value) {
        if (this.modified == value) {
            return;
        }
        this.modified = value;
        updateStatus();
        pcs.firePropertyChange("modified", !value, value);
    }

    private void updateStatus() {
        if (loadError || model == null) {
            statusLabel.setText("");
            return;
        }
        StringBuilder sb = new StringBuilder();
        int total = model.getRowCount();
        sb.append(DbfBundle.message("editor.status.rows", total));
        int signature = model.getDocument().getSignature();
        sb.append("  |  ").append(DbfBundle.message("editor.status.version",
                model.getDocument().getVersion().getDisplayName(), String.format("0x%02X", signature & 0xFF)));
        sb.append("  |  ").append(model.getDocument().getCharset().displayName());
        if (modified) {
            sb.append("  |  ").append(DbfBundle.message("editor.status.modified"));
        }
        statusLabel.setText(sb.toString());
    }

    private void registerSaveShortcut() {
        bindAction("SaveAll", this::save);
    }

    /**
     * Binds the Cmd-F search actions (open / next / previous) to the editor panel, reusing the IDE's
     * own Find shortcuts so they follow the user's keymap.
     */
    private void registerSearchShortcuts() {
        bindAction("Find", search::activate);
        bindAction("FindNext", search::findNext);
        bindAction("FindPrevious", search::findPrev);
    }

    /**
     * Binds column navigation to the IDE File Structure shortcut (e.g. Cmd-F12), reusing whatever the
     * user's keymap assigns to it. Registered on the panel; the popup needs the action's
     * {@link DataContext} for best positioning, so it cannot go through {@link #bindAction}.
     */
    private void registerColumnNavShortcut() {
        AnAction action = ActionManager.getInstance().getAction("FileStructurePopup");
        ShortcutSet shortcuts = action != null ? action.getShortcutSet() : CustomShortcutSet.EMPTY;
        DumbAwareAction.create(e -> columnNavigator.show(e.getDataContext()))
                .registerCustomShortcutSet(shortcuts, rootPanel);
    }

    /**
     * Binds the IDE Copy shortcut (e.g. Cmd-C) to copy the cell selection as TSV. Registered on the
     * table (not the panel) and disabled while a cell is being edited, so Cmd-C inside the cell editor
     * still copies the editor's text rather than the whole selection — a disabled action does not
     * consume the keystroke, letting it fall through to the focused text field.
     */
    private void registerCopyShortcut() {
        AnAction ideCopy = ActionManager.getInstance().getAction("$Copy");
        ShortcutSet shortcuts = ideCopy != null ? ideCopy.getShortcutSet() : CustomShortcutSet.EMPTY;
        new DumbAwareAction() {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                copySelectionToClipboard();
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(!loadError && !table.isEditing()
                        && table.getSelectedRowCount() > 0 && table.getSelectedColumnCount() > 0);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        }.registerCustomShortcutSet(shortcuts, table);
    }

    /** Registers {@code runnable} under the shortcut of the IDE action {@code actionId}, on the panel. */
    private void bindAction(@NotNull String actionId, @NotNull Runnable runnable) {
        AnAction action = ActionManager.getInstance().getAction(actionId);
        ShortcutSet shortcuts = action != null ? action.getShortcutSet() : CustomShortcutSet.EMPTY;
        DumbAwareAction.create(e -> runnable.run()).registerCustomShortcutSet(shortcuts, rootPanel);
    }

    private void subscribeToClose() {
        MessageBusConnection connection = project.getMessageBus().connect(this);
        connection.subscribe(FileEditorManagerListener.Before.FILE_EDITOR_MANAGER, new FileEditorManagerListener.Before() {
            @Override
            public void beforeFileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile closing) {
                if (!closing.equals(file) || !modified || loadError) {
                    return;
                }
                if (DbfFileWriterService.hasUnwritableColumns(model.getDocument())) {
                    return;
                }
                int answer = Messages.showYesNoDialog(project,
                        DbfBundle.message("save.confirm.message", closing.getName(), closing.getPresentableUrl()),
                        DbfBundle.message("save.confirm.title"), null);
                if (answer == Messages.YES) {
                    save();
                }
            }
        });
    }

    // ---- FileEditor ------------------------------------------------------------------------

    @Override
    public @NotNull JComponent getComponent() {
        return rootPanel;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return table;
    }

    @Override
    public @NotNull String getName() {
        return "DBF Table";
    }

    @Override
    public @NotNull VirtualFile getFile() {
        return file;
    }

    @Override
    public void setState(@NotNull FileEditorState state) {
    }

    @Override
    public @NotNull FileEditorState getState(@NotNull FileEditorStateLevel level) {
        return FileEditorState.INSTANCE;
    }

    @Override
    public boolean isModified() {
        return modified;
    }

    @Override
    public boolean isValid() {
        return file.isValid();
    }

    @Override
    public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {
        pcs.addPropertyChangeListener(listener);
    }

    @Override
    public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {
        pcs.removePropertyChangeListener(listener);
    }

    @Override
    public void dispose() {
    }

    /** Common base for toolbar actions; all read Swing state and so update on the EDT. */
    private abstract static class TableAction extends DumbAwareAction {
        TableAction(String text, String description, Icon icon) {
            super(text, description, icon);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }
}
