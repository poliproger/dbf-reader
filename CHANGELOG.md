<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# DBF Reader Changelog

## Unreleased

## 1.2.0 - 2026-06-10

### Added

- **Find in table (Cmd-F)** — a search bar, opened with Cmd-F or the toolbar Find button, highlights
  every matching cell and lets you step through the matches (Enter / Shift+Enter, or the IDE's Find
  Next/Previous shortcuts) with a "N of M" counter, without hiding any rows. Supports Match Case,
  Whole Words and regular expressions.
- **Go to Column (Cmd-F12)** — the File Structure shortcut opens a speed-search popup listing every
  field; start typing to filter and press Enter to jump to that column.
- **Copy as TSV (Cmd-C)** — the IDE Copy shortcut copies the selected cells to the clipboard as
  tab-separated text, exactly as displayed in the table.
- **External-change detection on save** — if another program changed the file on disk since it was
  opened, Save offers to overwrite the file or reload it from disk.
- **Deleted-records safeguard** — records marked as deleted (the `*` flag) are skipped on read and
  would be silently removed by a rewrite. The status bar now shows how many such records the file
  contains, and Save asks for confirmation before dropping them permanently.

### Changed

- Replaced the row-hiding Filter field with the new Find search bar.

### Fixed

- Changing the encoding no longer resets column widths.
- Selecting a logical (checkbox) cell no longer flips its value — only a click on the checkbox
  glyph itself toggles it.
- The Find shortcut no longer crashes the editor when the file failed to load.
- The record count in the status bar now updates on every row add/delete, not only on the first
  change after opening or saving.
- Clicking a column header while the search row filter is active no longer sorts the view after a
  column was added, edited or deleted.

## 1.1.0 - 2026-06-09

### Added

- Date fields now have a calendar picker: an editing date cell shows a button that opens IntelliJ's
  native calendar, alongside the existing keyboard entry.
- Auto-fit column widths to their headers on open and when adding a column.
- The unsaved-changes prompt shown when closing now names the file and its full path.

### Fixed

- Editing or deleting a column no longer resets column widths.
- Typing a column size without pressing Enter no longer discards the value when confirming the dialog.

## 1.0.0 - 2026-06-08

### Added

- **Table editor for `.dbf` files** — dBASE/xBase files open as a grid (records as rows, fields as
  columns) inside the IDE instead of being shown as raw binary. Registered for the `.dbf` file
  association.
- **Cell editing** for the C (character), N (numeric), F (float), L (logical) and D (date) field
  types, with type-aware editors and validation. Logical columns use a checkbox.
- **Row operations** — add and delete records.
- **Column operations** — add and delete columns, and edit a column's name, data type and size
  (length/decimals). Existing values are converted on a best-effort basis; values that cannot be
  converted are cleared.
- **Encoding handling** — character fields are decoded with the correct charset: the encoding is
  auto-detected from the DBF language-driver byte (including Cyrillic code pages such as
  windows-1251 and IBM866/cp866), and can be overridden per file from a toolbar combo box (UTF-8,
  windows-1251, IBM866/cp866, windows-1252, ISO-8859-1).
- **Configurable default encoding** (Settings | Tools | DBF Reader) — used for files that do not
  declare a code page (language-driver byte = 0).
- **Saving** — the whole file is rewritten on an explicit Save (toolbar button or the Save-All
  shortcut). An optional one-time `<name>.dbf.bak` backup can be created before the first overwrite
  of a session (off by default; toggle in settings).
- **Read-only for unsupported types** — memo and extended field types are shown read-only. Save is
  disabled while any non-writable column is present; converting such a column to a writable type
  (C, N, F, L, D) re-enables it.
