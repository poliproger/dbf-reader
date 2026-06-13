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
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
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
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBLoadingPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.Alarm;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
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
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.GridBagLayout;
import java.awt.datatransfer.StringSelection;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Table editor for a {@code .dbf} file. Loads the whole file into a {@link DbfDocument}, presents it
 * in a {@link JBTable} with type-aware renderers/editors, and writes the document back on save
 * (javadbf rewrites the file as a whole — there is no in-place editing).
 *
 * <p>The file is read and parsed off the EDT ({@link #beginLoad}) so a large {@code .dbf} does not
 * freeze the UI; a {@link JBLoadingPanel} shows a spinner meanwhile. Opening a file above the
 * configured size threshold first asks for confirmation (see {@link #openInitial}).
 */
public final class DbfFileEditor extends UserDataHolderBase implements FileEditor {

    /**
     * Center cards: a plain dark panel shown until loading actually begins (so opening a file — and
     * waiting on the large-file confirm dialog — shows only a uniform background, never a half-built
     * empty table), the table itself (with its loading spinner), and the "not loaded" placeholder.
     */
    private static final String CARD_BLANK = "blank";
    private static final String CARD_TABLE = "table";
    private static final String CARD_PLACEHOLDER = "placeholder";

    /** Delay before the loading spinner appears, so a fast (small-file) load shows no spinner flicker. */
    private static final int LOADING_START_DELAY_MS = 250;

    private final Project project;
    private final VirtualFile file;
    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);

    private final JPanel rootPanel = new JPanel(new BorderLayout());
    private final JPanel centerCards = new JPanel(new CardLayout());
    private final JBTable table = new JBTable();
    private final JBLabel statusLabel = new JBLabel();
    private final ComboBox<Charset> encodingCombo = new ComboBox<>();
    private final DbfSearchController search = new DbfSearchController(table, this);
    private final DbfColumnNavigator columnNavigator = new DbfColumnNavigator(table);
    private final DbfSaveManager saveManager;
    /** Defers disabling the table during a save so a fast (small-file) save doesn't flash it disabled. */
    private final Alarm savingUiAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);

    private JBLoadingPanel loadingPanel;
    private JBLabel notLoadedLabel;

    private DbfTableModel model;
    private boolean modified;
    private boolean loadError;
    private boolean loading;
    private boolean saving;
    private volatile boolean disposed;
    private boolean suppressEncodingEvent;

    public DbfFileEditor(@NotNull Project project, @NotNull VirtualFile file) {
        this.project = project;
        this.file = file;
        this.saveManager = new DbfSaveManager(project, file);

        table.setAutoResizeMode(JBTable.AUTO_RESIZE_OFF);
        table.setCellSelectionEnabled(true);
        table.getSelectionModel().setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        buildUi();
        installHeaderRenderer();
        registerSaveShortcut();
        registerSearchShortcuts();
        registerColumnNavShortcut();
        registerCopyShortcut();
        subscribeToClose();

        // Load off the EDT so a large file does not freeze the UI. createEditor returns immediately
        // with the loading spinner showing; the size guard and background read run right after.
        // nonModal() (not any()): openInitial shows a modal dialog and mutates the model, which must
        // run in a write-safe context — ModalityState.any() is write-unsafe.
        ApplicationManager.getApplication().invokeLater(this::openInitial, ModalityState.nonModal(), o -> disposed);
    }

    // ---- loading ---------------------------------------------------------------------------

    /**
     * Decides whether to open the file straight away or warn first, then kicks off the background
     * load. Runs on the EDT just after construction.
     */
    private void openInitial() {
        if (disposed) {
            return;
        }
        long length = file.getLength();
        int thresholdMb = DbfSettings.getInstance().largeFileWarningThresholdMb;
        if (thresholdMb > 0 && length > (long) thresholdMb * 1024L * 1024L) {
            int answer = Messages.showYesNoDialog(project,
                    DbfBundle.message("largeFile.confirm.message", file.getName(), StringUtil.formatFileSize(length)),
                    DbfBundle.message("largeFile.confirm.title"), null);
            if (answer != Messages.YES) {
                showNotLoadedPlaceholder(length);
                return;
            }
        }
        beginLoad(null, this::fitColumnWidthsToHeader, this::defaultLoadError);
    }

    /**
     * Reads and parses the file on a background thread, then installs the resulting model on the EDT.
     * Shared by the initial open, an encoding change and a reload-from-disk; the caller supplies what
     * to run after the model is installed (e.g. fit-to-header vs. restore captured widths) and how to
     * handle a read failure.
     */
    private void beginLoad(@Nullable Charset charsetOverride, @NotNull Runnable afterInstall,
                           @NotNull Consumer<Throwable> onFailure) {
        loading = true;
        // Reveal the table card now that loading is actually starting (it stays hidden behind CARD_BLANK
        // until here, so the empty table is never shown — e.g. while the large-file dialog is up).
        showCard(CARD_TABLE);
        loadingPanel.startLoading();
        // Disabled rather than gated: onEncodingChanged would silently swallow a selection made while
        // loading, leaving the combo showing a charset that was never applied. Re-enabled on install.
        encodingCombo.setEnabled(false);
        Charset fallback = DbfSettings.getInstance().resolveDefaultCharset();
        // A plain pooled thread, deliberately NOT ReadAction.nonBlocking: the read needs no IDE model
        // access (the VFS stream is thread-safe, javadbf parsing is pure), and a multi-second read
        // action that never calls checkCanceled() blocks any pending write action — freezing the EDT,
        // and with it the loading spinner, for the whole parse (and NBRA would then restart the
        // cancelled computation from scratch). The `disposed` expiry condition below replaces
        // expireWith(); no coalescing is needed — the `loading` flag already prevents a second load
        // from starting while one is in flight.
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            LoadResult result = readDocument(charsetOverride, fallback);
            // nonModal(), not any(): installLoaded mutates the editor's model in a write-safe context.
            ApplicationManager.getApplication().invokeLater(
                    () -> installLoaded(result, afterInstall, onFailure),
                    ModalityState.nonModal(), o -> disposed);
        });
    }

    /**
     * Reads and parses the file off the EDT. A read/parse failure is captured into the
     * {@link LoadResult} so it can be reported via a dialog on the UI thread without the platform
     * logging it as a plugin error.
     */
    private @NotNull LoadResult readDocument(@Nullable Charset charsetOverride, @NotNull Charset fallback) {
        try {
            // Read via the stream, not contentsToByteArray(): the latter throws FileTooBigException above
            // the IDE content-load limit — exactly the large-file case this background path exists for.
            byte[] bytes;
            try (InputStream in = file.getInputStream()) {
                bytes = in.readAllBytes();
            }
            DbfDocument document = DbfFileReaderService.read(bytes, charsetOverride, fallback);
            return LoadResult.success(bytes, document);
        } catch (Throwable t) {
            // Throwable, not Exception: reading a huge file into memory can throw OutOfMemoryError,
            // and it must still reach installLoaded — otherwise `loading` stays true forever and the
            // editor is stuck on the spinner with every action disabled.
            return LoadResult.failure(t);
        }
    }

    /** Installs the freshly loaded model on the EDT, or reports a read failure via {@code onFailure}. */
    private void installLoaded(@NotNull LoadResult result, @NotNull Runnable afterInstall,
                               @NotNull Consumer<Throwable> onFailure) {
        loading = false;
        if (disposed) {
            return;
        }
        loadingPanel.stopLoading();
        encodingCombo.setEnabled(true);
        if (result.error != null) {
            onFailure.accept(result.error);
            return;
        }
        // A cell edit begun while the load was in flight belongs to the document being discarded:
        // cancel it (not commit) so it neither flips the modified flag nor lands in the dropped model.
        cancelEditing();
        DbfDocument document = result.document;
        saveManager.rebaseline(result.bytes);
        model = new DbfTableModel(document);
        // Drop any active row filter before swapping the model: the sorter is bound to the old model
        // and JTable would not rebind it. search.onModelChanged() below re-applies it to the new one.
        search.detachRowSorter();
        table.setModel(model);
        model.addTableModelListener(e -> {
            setModified(true);
            // setModified only refreshes the status bar when the flag flips, so refresh here too:
            // the record count must track every row add/delete, not just the first edit.
            updateStatus();
        });
        model.addTableModelListener(e -> search.onModelChanged());
        search.onModelChanged();
        populateEncodingCombo(document.getCharset());
        loadError = false;
        installColumnRenderers();
        afterInstall.run();
        updateStatus();
    }

    /** Default read-failure handling: mark the editor errored and show the message. */
    private void defaultLoadError(@NotNull Throwable error) {
        loadError = true;
        Messages.showErrorDialog(project,
                error.getMessage() == null ? error.toString() : error.getMessage(),
                DbfBundle.message("read.error.title"));
        updateStatus();
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

    /** Whether the document is loaded and editable — gates the toolbar actions and shortcuts. */
    private boolean editingReady() {
        return model != null && !loadError && !loading && !saving;
    }

    // ---- UI --------------------------------------------------------------------------------

    private void buildUi() {
        rootPanel.add(buildTopPanel(), BorderLayout.NORTH);

        // Keep the whole editor in the table's (dark) background from the very first paint, so opening a
        // file never flashes a light area before the rows appear.
        Color background = UIUtil.getTableBackground();
        rootPanel.setBackground(background);
        centerCards.setBackground(background);

        JBScrollPane scrollPane = new JBScrollPane(table);
        // Pinned row-number gutter: scrolls vertically with the table, stays fixed on horizontal scroll.
        scrollPane.setRowHeaderView(new RowNumberTable(table));
        table.setBackground(background);
        scrollPane.setBackground(background);
        scrollPane.getViewport().setBackground(background);
        // startDelay: a load that finishes within it shows no spinner at all (no flicker on small files);
        // a slow (large-file) load still gets the spinner.
        loadingPanel = new JBLoadingPanel(new BorderLayout(), this, LOADING_START_DELAY_MS);
        loadingPanel.setLoadingText(DbfBundle.message("editor.loading.text"));
        loadingPanel.add(scrollPane, BorderLayout.CENTER);

        // CARD_BLANK is the default (added first): a plain dark panel shown until loading begins, so the
        // empty/half-built table is never the first thing painted. Switched to CARD_TABLE in beginLoad,
        // or CARD_PLACEHOLDER when a large file is declined.
        JPanel blankCard = new JPanel();
        blankCard.setBackground(background);
        centerCards.add(blankCard, CARD_BLANK);
        centerCards.add(loadingPanel, CARD_TABLE);
        centerCards.add(buildNotLoadedPlaceholder(), CARD_PLACEHOLDER);
        rootPanel.add(centerCards, BorderLayout.CENTER);

        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        rootPanel.add(statusLabel, BorderLayout.SOUTH);
    }

    /** The card shown when the user declines to open a large file: a note plus a "Load Anyway" button. */
    private JComponent buildNotLoadedPlaceholder() {
        notLoadedLabel = new JBLabel();
        notLoadedLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        JButton loadAnyway = new JButton(DbfBundle.message("largeFile.placeholder.load"));
        loadAnyway.setAlignmentX(Component.CENTER_ALIGNMENT);
        // beginLoad switches to CARD_TABLE itself.
        loadAnyway.addActionListener(e -> beginLoad(null, this::fitColumnWidthsToHeader, this::defaultLoadError));
        JPanel inner = new JPanel();
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.add(notLoadedLabel);
        inner.add(Box.createVerticalStrut(JBUI.scale(8)));
        inner.add(loadAnyway);
        // GridBagLayout with a single child centers it.
        JPanel wrapper = new JPanel(new GridBagLayout());
        wrapper.add(inner);
        // Same table background as the blank and table cards, so declining a large file does not
        // flash a differently colored (panel-background) area.
        Color background = UIUtil.getTableBackground();
        inner.setBackground(background);
        wrapper.setBackground(background);
        return wrapper;
    }

    private void showNotLoadedPlaceholder(long length) {
        notLoadedLabel.setText(DbfBundle.message("largeFile.placeholder.text",
                file.getName(), StringUtil.formatFileSize(length)));
        showCard(CARD_PLACEHOLDER);
    }

    private void showCard(@NotNull String card) {
        ((CardLayout) centerCards.getLayout()).show(centerCards, card);
    }

    private JComponent buildTopPanel() {
        JPanel toolbarRow = new JPanel(new BorderLayout());

        ActionToolbar toolbar = ActionManager.getInstance()
                .createActionToolbar("DbfEditorToolbar", buildActions(), true);
        toolbar.setTargetComponent(table);
        // Off by default; without it the toolbar drops the "Row:"/"Col:" separator labels.
        toolbar.setShowSeparatorTitles(true);
        toolbarRow.add(toolbar.getComponent(), BorderLayout.WEST);

        JPanel encodingPanel = new JPanel(new BorderLayout(4, 0));
        encodingPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        encodingPanel.add(new JBLabel(DbfBundle.message("editor.encoding.label")), BorderLayout.WEST);
        encodingCombo.addActionListener(e -> onEncodingChanged());
        // Empty and useless until the first load installs the document's charset; enabled then.
        encodingCombo.setEnabled(false);
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
                e.getPresentation().setEnabled(editingReady());
            }
        };
        // Surface the IDE Find shortcut (e.g. Cmd-F) in the button's tooltip. The toolbar's ActionButton
        // reads the keystroke from the action's own shortcut set, so copy the IDE Find action's shortcuts
        // onto this button. This is display-only — it binds no handler; the active binding stays on the
        // panel (see registerSearchShortcuts), so the shortcut is not triggered twice.
        AnAction ideFind = ActionManager.getInstance().getAction("Find");
        if (ideFind != null) {
            findButton.copyShortcutFrom(ideFind);
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
                e.getPresentation().setEnabled(editingReady() && modified
                        && !DbfFileWriterService.hasUnwritableColumns(model.getDocument()));
            }
        });
        // The row and column groups use the same Add/Remove icons, so label each group's separator
        // to keep them visually distinguishable.
        group.addSeparator(DbfBundle.message("toolbar.group.rows"));
        group.add(new TableAction(DbfBundle.message("action.addRow.text"), DbfBundle.message("action.addRow.description"),
                AllIcons.General.Add) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                addRow();
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                // A row needs at least one column to hold a value; a freshly created (empty) file has none.
                e.getPresentation().setEnabled(editingReady() && model.getColumnCount() > 0);
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
                e.getPresentation().setEnabled(editingReady() && table.getSelectedRowCount() > 0);
            }
        });
        group.addSeparator(DbfBundle.message("toolbar.group.columns"));
        group.add(new TableAction(DbfBundle.message("action.addColumn.text"), DbfBundle.message("action.addColumn.description"),
                AllIcons.General.Add) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                addColumn();
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(editingReady());
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
                e.getPresentation().setEnabled(editingReady() && table.getSelectedColumn() >= 0);
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
                e.getPresentation().setEnabled(editingReady() && table.getSelectedColumn() >= 0);
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
        // The first column on a freshly created (empty) file makes the table usable; give it a row to
        // edit right away, so the user does not have to add one manually before entering data.
        boolean wasEmpty = model.getColumnCount() == 0;
        model.addColumn(dialog.getResult(), null);
        if (wasEmpty) {
            model.addRow();
        }
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
        if (viewColumn < 0) {
            return;
        }
        int modelColumn = table.convertColumnIndexToModel(viewColumn);
        Map<String, Integer> widths = currentColumnWidths();
        model.removeColumn(modelColumn);
        // Deleting the last column leaves the rows field-less; drop them so the file returns to the
        // clean blank state of a freshly created one (Add Row disabled, ready for new columns again).
        if (model.getColumnCount() == 0) {
            model.clearRows();
        }
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
        if (suppressEncodingEvent || loading || saving) {
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
        beginLoad(selected,
                () -> {
                    restoreColumnWidths(widths);
                    setModified(false);
                },
                error -> revertEncoding(previous));
    }

    /**
     * Restores the encoding combo to {@code previous} after a failed re-read. The previously loaded
     * document is still intact (the failed read never replaced the model), so the editor stays usable.
     */
    private void revertEncoding(@NotNull Charset previous) {
        loadError = false;
        suppressEncodingEvent = true;
        encodingCombo.setSelectedItem(previous);
        suppressEncodingEvent = false;
    }

    // ---- saving ----------------------------------------------------------------------------

    private void save() {
        if (!editingReady()) {
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
        beginSave(model.getDocument());
    }

    /**
     * Saves off the EDT, mirroring {@link #beginLoad}: the disk re-read/hash ({@link
     * DbfSaveManager#isModifiedOnDisk}) and the document serialization run on a pooled thread, leaving
     * only the user dialogs and the write command on the EDT. While the save is in flight the table is
     * disabled and every action is gated (see {@link #editingReady}), so the document cannot be mutated
     * under the background serialization — no snapshot is needed.
     */
    private void beginSave(@NotNull DbfDocument document) {
        saving = true;
        // Gating (editingReady) already blocks the toolbar/shortcuts — and so every structural change —
        // the instant `saving` flips. Disabling the table (which also blocks a cell edit, the one
        // mutation not gated that way) is deferred: a fast save finishes before the delay and never
        // flashes the table disabled, while a slow one takes the lock before the serialization runs.
        savingUiAlarm.cancelAllRequests();
        savingUiAlarm.addRequest(() -> {
            if (saving) {
                setSavingUi(true);
            }
        }, LOADING_START_DELAY_MS);
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            boolean changedOnDisk = saveManager.isModifiedOnDisk();
            ApplicationManager.getApplication().invokeLater(
                    () -> afterDiskCheck(document, changedOnDisk), ModalityState.nonModal(), o -> disposed);
        });
    }

    /**
     * Resolves an external change (if any) and the deleted-records prompt on the EDT, then serializes
     * the document off the EDT before committing it.
     */
    private void afterDiskCheck(@NotNull DbfDocument document, boolean changedOnDisk) {
        if (disposed) {
            return;
        }
        if (changedOnDisk) {
            DbfSaveManager.ConflictChoice choice = saveManager.askConflict();
            if (choice == DbfSaveManager.ConflictChoice.RELOAD) {
                endSave();
                reloadFromDisk();
                return;
            }
            if (choice == DbfSaveManager.ConflictChoice.CANCEL) {
                endSave();
                return;
            }
            // OVERWRITE: fall through and write our version.
        }
        int deleted = document.getDeletedRecordCount();
        if (deleted > 0 && !saveManager.confirmDeletedRecords(deleted)) {
            endSave();
            return;
        }
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            byte[] bytes;
            try {
                bytes = DbfFileWriterService.write(document);
            } catch (Throwable t) {
                ApplicationManager.getApplication().invokeLater(
                        () -> failSave(t), ModalityState.nonModal(), o -> disposed);
                return;
            }
            ApplicationManager.getApplication().invokeLater(
                    () -> finishSave(document, bytes), ModalityState.nonModal(), o -> disposed);
        });
    }

    /** Runs the write command on the EDT, then clears the modified flag and unblocks the UI. */
    private void finishSave(@NotNull DbfDocument document, byte @NotNull [] bytes) {
        if (disposed) {
            return;
        }
        try {
            saveManager.commit(bytes);
        } catch (RuntimeException ex) {
            failSave(ex.getCause() != null ? ex.getCause() : ex);
            return;
        }
        // The rewritten file no longer contains the deleted-marked records, so stop warning about them
        // (cleared before the modified flag flips, so the status bar refreshes without them).
        document.setDeletedRecordCount(0);
        endSave();
        setModified(false);
    }

    private void failSave(@NotNull Throwable error) {
        if (disposed) {
            return;
        }
        saveManager.showSaveError(error);
        endSave();
    }

    private void endSave() {
        saving = false;
        savingUiAlarm.cancelAllRequests();
        setSavingUi(false);
    }

    /**
     * Blocks or unblocks editing while a save is in flight: disabling the table stops a cell edit from
     * mutating the document under the background serialization (the toolbar actions are already gated by
     * {@link #editingReady}), and the status bar shows the in-progress state.
     */
    private void setSavingUi(boolean active) {
        table.setEnabled(!active);
        encodingCombo.setEnabled(!active);
        updateStatus();
    }

    /**
     * Synchronous save for the "save before closing?" path: the editor is about to be disposed, so the
     * async {@link #beginSave} pipeline (whose continuations expire on dispose) would never finish the
     * write. Runs every step on the EDT instead, so the file is written before the editor closes. A
     * conflicting on-disk change is resolved as "skip the write" (reloading a closing file is
     * pointless), letting the close proceed with the on-disk version kept.
     */
    private void saveBlocking() {
        DbfDocument document = model.getDocument();
        if (saveManager.isModifiedOnDisk()
                && saveManager.askConflict() != DbfSaveManager.ConflictChoice.OVERWRITE) {
            return;
        }
        int deleted = document.getDeletedRecordCount();
        if (deleted > 0 && !saveManager.confirmDeletedRecords(deleted)) {
            return;
        }
        byte[] bytes;
        try {
            bytes = DbfFileWriterService.write(document);
        } catch (Throwable t) {
            saveManager.showSaveError(t);
            return;
        }
        try {
            saveManager.commit(bytes);
        } catch (RuntimeException ex) {
            saveManager.showSaveError(ex.getCause() != null ? ex.getCause() : ex);
            return;
        }
        document.setDeletedRecordCount(0);
        setModified(false);
    }

    /**
     * Discards the in-memory edits and reloads the document from the current on-disk bytes, preserving the
     * active encoding and column widths (the structure is normally unchanged). Used when the user opts to
     * keep the externally changed file instead of overwriting it.
     */
    private void reloadFromDisk() {
        Charset current = model != null ? model.getDocument().getCharset() : null;
        Map<String, Integer> widths = currentColumnWidths();
        beginLoad(current,
                () -> {
                    restoreColumnWidths(widths);
                    setModified(false);
                },
                this::defaultLoadError);
    }

    // ---- helpers ---------------------------------------------------------------------------

    private void stopEditing() {
        if (table.isEditing() && table.getCellEditor() != null) {
            table.getCellEditor().stopCellEditing();
        }
    }

    /** Abandons an in-progress cell edit without committing its value. */
    private void cancelEditing() {
        if (table.isEditing() && table.getCellEditor() != null) {
            table.getCellEditor().cancelCellEditing();
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
        int deleted = model.getDocument().getDeletedRecordCount();
        if (deleted > 0) {
            sb.append("  |  ").append(DbfBundle.message("editor.status.deleted", deleted));
        }
        int signature = model.getDocument().getSignature();
        sb.append("  |  ").append(DbfBundle.message("editor.status.version",
                model.getDocument().getVersion().getDisplayName(), String.format("0x%02X", signature & 0xFF)));
        sb.append("  |  ").append(model.getDocument().getCharset().displayName());
        if (modified) {
            sb.append("  |  ").append(DbfBundle.message("editor.status.modified"));
        }
        if (saving) {
            sb.append("  |  ").append(DbfBundle.message("editor.status.saving"));
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
        // Gate on editingReady() like the toolbar Find button: before the document is loaded (or after a
        // failed load) the table still has its default (non-Dbf) model, which the search controller
        // cannot work on.
        bindAction("Find", () -> {
            if (editingReady()) {
                search.activate();
            }
        });
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
                e.getPresentation().setEnabled(editingReady() && !table.isEditing()
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
                // `loading`: while a (re)load is in flight a save is a silent no-op, so a "save?"
                // prompt would promise something it cannot deliver — and the pending edits are
                // already condemned by the reload anyway.
                if (!closing.equals(file) || !modified || loadError || model == null || loading) {
                    return;
                }
                if (DbfFileWriterService.hasUnwritableColumns(model.getDocument())) {
                    return;
                }
                int answer = Messages.showYesNoDialog(project,
                        DbfBundle.message("save.confirm.message", closing.getName(), closing.getPresentableUrl()),
                        DbfBundle.message("save.confirm.title"), null);
                if (answer == Messages.YES) {
                    // The editor is about to be disposed, so the async pipeline (whose continuations
                    // expire on dispose) would never finish the write — save synchronously instead.
                    saveBlocking();
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
        disposed = true;
    }

    /**
     * Outcome of a background read: on success the raw bytes (for the save baseline) and the parsed
     * document; on failure the captured {@code error}. Exactly one of {@code document}/{@code error}
     * is non-null.
     */
    private static final class LoadResult {
        final byte[] bytes;
        final DbfDocument document;
        final Throwable error;

        private LoadResult(byte[] bytes, DbfDocument document, Throwable error) {
            this.bytes = bytes;
            this.document = document;
            this.error = error;
        }

        static LoadResult success(byte[] bytes, DbfDocument document) {
            return new LoadResult(bytes, document, null);
        }

        static LoadResult failure(Throwable error) {
            return new LoadResult(null, null, error);
        }
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
