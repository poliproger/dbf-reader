package com.poliproger.dbfreader.editor;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.command.WriteCommandAction;
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
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.Alarm;
import com.intellij.util.messages.MessageBusConnection;
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
import com.poliproger.dbfreader.ui.DbfValueFormatter;
import com.poliproger.dbfreader.ui.RowNumberTable;
import com.poliproger.dbfreader.ui.cell.DbfCellRenderer;
import com.poliproger.dbfreader.ui.cell.DbfTextCellEditor;
import com.linuxense.javadbf.DBFDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.event.DocumentEvent;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Table editor for a {@code .dbf} file. Loads the whole file into a {@link DbfDocument}, presents it
 * in a {@link JBTable} with type-aware renderers/editors, and writes the document back on save
 * (javadbf rewrites the file as a whole — there is no in-place editing).
 */
public final class DbfFileEditor extends UserDataHolderBase implements FileEditor {

    /** Debounce so typing in the filter field re-runs the (whole-table) filter pass only once typing pauses. */
    private static final int FILTER_DELAY_MS = 250;

    private final Project project;
    private final VirtualFile file;
    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);

    private final JPanel rootPanel = new JPanel(new BorderLayout());
    private final JBTable table = new JBTable();
    private final JBLabel statusLabel = new JBLabel();
    private final ComboBox<Charset> encodingCombo = new ComboBox<>();
    private final SearchTextField filterField = new SearchTextField();
    private final Alarm filterAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);

    private DbfTableModel model;
    private TableRowSorter<DbfTableModel> sorter;
    private boolean modified;
    private boolean loadError;
    private boolean backupCreated;
    private boolean suppressEncodingEvent;

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
            updateStatus();
        }
        registerSaveShortcut();
        subscribeToClose();
    }

    // ---- loading ---------------------------------------------------------------------------

    private boolean loadDocument(@Nullable Charset charsetOverride) {
        try {
            DbfDocument document = DbfFileReaderService.read(file.contentsToByteArray(), charsetOverride,
                    DbfSettings.getInstance().resolveDefaultCharset());
            model = new DbfTableModel(document);
            table.setModel(model);
            installRowSorter();
            model.addTableModelListener(e -> setModified(true));
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
        JPanel panel = new JPanel(new BorderLayout());

        ActionToolbar toolbar = ActionManager.getInstance()
                .createActionToolbar("DbfEditorToolbar", buildActions(), true);
        toolbar.setTargetComponent(table);
        panel.add(toolbar.getComponent(), BorderLayout.WEST);

        panel.add(buildFilterPanel(), BorderLayout.CENTER);

        JPanel encodingPanel = new JPanel(new BorderLayout(4, 0));
        encodingPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        encodingPanel.add(new JBLabel(DbfBundle.message("editor.encoding.label")), BorderLayout.WEST);
        encodingCombo.addActionListener(e -> onEncodingChanged());
        encodingPanel.add(encodingCombo, BorderLayout.EAST);
        panel.add(encodingPanel, BorderLayout.EAST);

        return panel;
    }

    // ---- filtering -------------------------------------------------------------------------

    private JComponent buildFilterPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 0));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        panel.add(new JBLabel(DbfBundle.message("editor.filter.label")), BorderLayout.WEST);
        filterField.getTextEditor().getEmptyText().setText(DbfBundle.message("editor.filter.placeholder"));
        filterField.addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                scheduleFilter();
            }
        });
        panel.add(filterField, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Binds a fresh {@link TableRowSorter} to the current model. Recreated whenever the model is
     * replaced or its structure changes; sorting is disabled (DBF row order is significant — the
     * sorter is used purely as the filtering host) and the active filter text is re-applied.
     */
    private void installRowSorter() {
        sorter = new TableRowSorter<>(model);
        for (int i = 0; i < model.getColumnCount(); i++) {
            sorter.setSortable(i, false);
        }
        table.setRowSorter(sorter);
        applyFilter();
    }

    private void scheduleFilter() {
        filterAlarm.cancelAllRequests();
        filterAlarm.addRequest(this::applyFilter, FILTER_DELAY_MS);
    }

    /** Applies the current filter-field text across all columns; an empty query clears the filter. */
    private void applyFilter() {
        if (sorter == null) {
            return;
        }
        String query = filterField.getText().trim();
        sorter.setRowFilter(query.isEmpty() ? null : buildFilter(query));
        updateStatus();
    }

    /**
     * Case-insensitive substring match (ilike {@code %query%}) over every column, comparing against
     * the value as the user sees it ({@link DbfValueFormatter}) so dates and numbers match their
     * displayed form rather than their raw {@code toString()}.
     */
    private RowFilter<DbfTableModel, Integer> buildFilter(@NotNull String query) {
        String needle = query.toLowerCase(Locale.ROOT);
        return new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends DbfTableModel, ? extends Integer> entry) {
                DbfTableModel m = entry.getModel();
                for (int c = 0; c < entry.getValueCount(); c++) {
                    Object value = entry.getValue(c);
                    if (value == null) {
                        continue;
                    }
                    String text = DbfValueFormatter.format(value, m.getColumnDef(c));
                    if (text.toLowerCase(Locale.ROOT).contains(needle)) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    /** Clears the filter (text + row filter) so subsequently added rows are visible and addressable. */
    private void clearFilter() {
        filterAlarm.cancelAllRequests();
        filterField.setText("");
        if (sorter != null) {
            sorter.setRowFilter(null);
        }
    }

    private DefaultActionGroup buildActions() {
        DefaultActionGroup group = new DefaultActionGroup();
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
        DbfCellRenderer renderer = new DbfCellRenderer();
        for (int i = 0; i < model.getColumnCount(); i++) {
            DbfColumnDef def = model.getColumnDef(i);
            TableColumn column = table.getColumnModel().getColumn(i);
            // Logical columns rely on the default Boolean checkbox renderer/editor.
            if (def.getType() == DBFDataType.LOGICAL) {
                continue;
            }
            column.setCellRenderer(renderer);
            if (def.isWritable()) {
                column.setCellEditor(new DbfTextCellEditor(def, model.getDocument().getCharset()));
            }
        }
    }

    /**
     * Installs the header renderer that appends each field's type/size label in a muted color. Set on
     * the (persistent) {@code JTableHeader} rather than per-column, so it survives the column-model
     * recreation that {@code fireTableStructureChanged()} triggers and needs installing only once.
     */
    private void installHeaderRenderer() {
        javax.swing.table.JTableHeader header = table.getTableHeader();
        if (header != null && !(header.getDefaultRenderer() instanceof DbfHeaderRenderer)) {
            header.setDefaultRenderer(new DbfHeaderRenderer(header.getDefaultRenderer()));
        }
    }

    // ---- row operations --------------------------------------------------------------------

    private void addRow() {
        stopEditing();
        // Clear any active filter so the new (empty) row is visible and the selection index is valid.
        clearFilter();
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
        if (dialog.showAndGet()) {
            model.addColumn(dialog.getResult(), null);
            installColumnRenderers();
            installRowSorter();
        }
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
        model.updateColumn(modelColumn, newDef, result.values);
        installColumnRenderers();
        installRowSorter();

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
        model.removeColumn(modelColumn);
        installColumnRenderers();
        installRowSorter();
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
        if (loadDocument(selected)) {
            installColumnRenderers();
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
        if (sorter != null && sorter.getRowFilter() != null) {
            sb.append("  |  ").append(DbfBundle.message("editor.status.shown", table.getRowCount(), total));
        }
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
        com.intellij.openapi.actionSystem.AnAction saveAll = ActionManager.getInstance().getAction("SaveAll");
        com.intellij.openapi.actionSystem.ShortcutSet shortcuts = saveAll != null
                ? saveAll.getShortcutSet()
                : com.intellij.openapi.actionSystem.CustomShortcutSet.EMPTY;
        DumbAwareAction.create(e -> save()).registerCustomShortcutSet(shortcuts, rootPanel);
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
                        DbfBundle.message("save.confirm.message"),
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
        TableAction(String text, String description, javax.swing.Icon icon) {
            super(text, description, icon);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }
}
