<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# DBF Reader Changelog

## [Unreleased]

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
