# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An **IntelliJ IDEA Community plugin** that opens dBASE/xBase `.dbf` files in a **table editor** for
viewing and editing. It supports editing cell values, adding/removing rows and columns, and changing
a column's name, data type and size. DBF parsing is delegated to the
[javadbf](https://github.com/albfernandez/javadbf) library (`com.linuxense.javadbf`).

Implementation language is **Java** (`src/main/java`), even though the project is Kotlin-enabled.

## Commands

The Gradle wrapper drives everything; `check` runs tests plus the IntelliJ verifier.

```bash
./gradlew build              # compile + test + build the plugin distribution
./gradlew runIde             # launch a sandbox IDE with the plugin loaded
./gradlew test               # run unit tests
./gradlew verifyPlugin       # IntelliJ Plugin Verifier (compatibility from sinceBuild 242)
./gradlew buildPlugin        # plugin .zip under build/distributions/ (bundles javadbf in lib/)
./gradlew publishPlugin      # publish to JetBrains Marketplace (needs credentials)
```

Run a single test:

```bash
./gradlew test --tests "com.poliproger.dbfreader.DbfTypeConverterTest"
./gradlew test --tests "com.poliproger.dbfreader.DbfTypeConverterTest.characterToLogical"
```

Tests live in `src/test/java`, JUnit 4 (`TestFrameworkType.Platform`). The model/io/converter tests
are plain logic tests and do not need platform fixtures.

## Architecture

Data flows: **VirtualFile bytes → DbfDocument (in-memory model) → DbfTableModel → JBTable**, and back
out via the writer on save.

- **`DbfFileType`** (binary) + **`editor/DbfFileEditorProvider`** (`HIDE_DEFAULT_EDITOR`) register the
  `.dbf` association and route opening to the table editor. Wired in `plugin.xml` via the `fileType`
  and `fileEditorProvider` extension points.
- **`editor/DbfFileEditor`** is the main UI: a `JBTable` plus a toolbar (Find, Save, add/delete row,
  add/delete/edit column), an encoding combo box, a row-number gutter (`ui/RowNumberTable` as the
  scroll pane's row header) and a status bar (record count, DBF version, charset, modified flag).
  Toolbar actions are anonymous `DumbAwareAction`s defined inline (no `plugin.xml` action
  registration); editor shortcuts reuse the IDE actions' shortcut sets (`bindAction`): SaveAll →
  save, Find/FindNext/FindPrevious → search, FileStructurePopup → column navigator, $Copy →
  copy-selection-as-TSV. After any *structural* change it must call `installColumnRenderers()`
  again, because `fireTableStructureChanged()` recreates the table's column model and drops
  per-column renderers/editors.
- **`editor/DbfSearchController`** — the Cmd-F search bar: a debounced match pass over the whole
  table, match shading via `ui/cell/CellSearchHighlighter`, an "N of M" counter, Match Case / Whole
  Words / Regex toggles and an optional row filter (a `TableRowSorter` used for *filtering only*,
  sorting disabled). The sorter must be detached before swapping table models (`detachRowSorter`).
  The pure matching logic lives in `ui/DbfTableSearch` (Swing-free, tested).
- **`editor/DbfColumnNavigator`** — the Cmd-F12 "Go to Column" speed-search popup.
- **`model/`** — `DbfDocument` (columns + rows + charset + header signature byte), `DbfColumnDef`,
  `DbfRow` (values stored in a `List` so add/remove-column is cheap), `DbfTableModel` (the
  `AbstractTableModel`), `DbfTypeConverter` (best-effort value conversion on type/size change;
  clears unconvertible values), and `DbfVersion` (header version byte → display name).
- **`io/`** — `DbfFileReaderService` (via `DBFReader`) and `DbfFileWriterService` (via `DBFWriter`).
- **`ui/`** — `DbfValueFormatter`, `ColumnEditDialog`, `DbfHeaderRenderer` (field name + muted
  type/size label), `DbfTableSearch`, `DbfTsvExporter` (Swing-free TSV for the clipboard),
  `RowNumberTable`, and `ui/cell/` — `DbfCellRenderer` / `DbfBooleanCellRenderer` (search-match
  shading), `DbfTextCellEditor`, `DbfDateCellEditor` (text field + calendar popup) and
  `DbfBooleanCellEditor`.
- **`settings/`** — `DbfSettings` (application-level `PersistentStateComponent`, stored in
  `dbf-reader.xml`) and `DbfSettingsConfigurable` (the Settings | Tools | DBF Reader page).
  Registered via the `applicationConfigurable` extension point in `plugin.xml`.

### Constraints that shape the design

- **javadbf has no in-place editing.** The whole file is loaded into `DbfDocument`, mutated in memory,
  and rewritten as a whole on save. Binary files have no platform `Document`, so save is explicit:
  a toolbar button + the SaveAll shortcut, writing via `WriteCommandAction` + `setBinaryContent`.
  Optionally, the original is copied to a one-time `<name>.dbf.bak` backup before the first save of a
  session — gated by `DbfSettings.createBackupOnSave`, **off by default** and toggled in
  Settings | Tools | DBF Reader.
- **External-change detection on save.** Because the model is a detached in-memory copy, another program
  may overwrite the file while it is open. `DbfFileEditor` keeps a SHA-256 `baselineDigest` of the bytes
  the model was loaded from (set in `loadDocument`, rebased after each of our own writes). `save()` calls
  `isModifiedOnDisk()` — which `refresh`es the `VirtualFile` and re-hashes it — and, on a mismatch, offers
  Overwrite / Reload from Disk (`reloadFromDisk()`, discarding in-memory edits) / Cancel before writing.
- **javadbf can only *write* the C, N, F, L, D types.** `DbfColumnDef.isWritable()` wraps
  `DBFDataType.isWriteSupported()`. Memo/extended-type columns render read-only; if a document still
  contains any non-writable column, **Save is disabled** (`DbfFileWriterService.hasUnwritableColumns`)
  to prevent data loss. `ColumnEditDialog` only offers the writable types, so converting a memo
  column to e.g. CHARACTER makes the file saveable again.
- **Logical columns** render via the platform Boolean checkbox (model `getColumnClass` returns
  `Boolean`, wrapped by `DbfBooleanCellRenderer` for search shading) and edit via `DbfBooleanCellEditor`,
  which toggles only on a click on the checkbox glyph itself — not anywhere in the cell — so selecting a
  logical cell does not flip its value. All other types use `DbfCellRenderer` + `DbfTextCellEditor`.
- **Encoding**: resolved by the plugin itself in `DbfFileReaderService.resolveCharset` and passed to
  `DBFReader` explicitly — javadbf's own auto-detection is bypassed because it reads the
  language-driver byte as *signed*, so any code page >= 0x80 (e.g. 0xC9 windows-1251) is misdetected
  and silently falls back to ISO-8859-1. Resolution order: manual combo-box override → the file's
  declared code page (language-driver byte at offset 29, read unsigned via
  `DBFCharsetHelper.getCharsetByByte`) → the default charset from settings
  (`DbfSettings.resolveDefaultCharset`, used only when the file declares none / `LDID = 0`).
  Changing the combo box re-reads the original bytes (discarding in-memory edits after a confirm),
  since character fields are decoded at read time. Full write-up: `notes/encoding-detection.md`.

## Build facts

- IntelliJ IDEA 2025.3.5 SDK, Kotlin JVM 2.2.20, IntelliJ Platform Gradle Plugin 2.16.0, Gradle 9.5.
- `sinceBuild = 242` (IDEA 2024.2+), open `untilBuild`, set in `build.gradle.kts`.
- javadbf is an `implementation` dependency (pinned in `gradle/libs.versions.toml`); the platform
  plugin bundles it into the distribution's `lib/`. License: **LGPL-3.0** (dynamic linking, unmodified).
- Base package `com.poliproger.dbfreader`; plugin id `com.poliproger.dbf-reader`. UI strings go
  through `DbfBundle` / `messages/DbfBundle.properties`.

## Code style

- **Always import classes; never use fully-qualified class names inline in code.** Add a normal
  `import` statement and refer to the class by its simple name in method bodies, signatures and
  declarations (e.g. `JTableHeader header`, not `javax.swing.table.JTableHeader header`). The only
  exception is when two same-named classes genuinely clash in one file.
