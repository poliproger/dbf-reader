package com.poliproger.dbfreader.ui.cell;

import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBTextField;
import com.linuxense.javadbf.DBFDataType;
import com.poliproger.dbfreader.model.DbfColumnDef;
import com.poliproger.dbfreader.ui.DbfValueFormatter;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractCellEditor;
import javax.swing.BorderFactory;
import javax.swing.JTable;
import javax.swing.border.Border;
import javax.swing.table.TableCellEditor;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.DocumentFilter;
import java.awt.Component;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.EventObject;

/**
 * Text field cell editor that parses the typed text into the proper value type (String, BigDecimal,
 * Date) and rejects invalid input by refusing to stop editing. Character fields are length-limited.
 * Logical fields are not handled here — the table uses the default Boolean checkbox editor for them.
 */
public final class DbfTextCellEditor extends AbstractCellEditor implements TableCellEditor {

    private static final Border NORMAL_BORDER = BorderFactory.createEmptyBorder(1, 3, 1, 3);
    private static final Border ERROR_BORDER = BorderFactory.createLineBorder(JBColor.RED, 1);

    private final DbfColumnDef def;
    private final JBTextField field = new JBTextField();

    public DbfTextCellEditor(DbfColumnDef def, Charset charset) {
        this.def = def;
        field.setBorder(NORMAL_BORDER);
        if (isCharacter(def.getType())) {
            ((AbstractDocument) field.getDocument()).setDocumentFilter(new MaxLengthFilter(def.getLength(), charset));
        }
    }

    @Override
    public boolean isCellEditable(EventObject anEvent) {
        // Require a double-click to enter edit mode (mirrors DefaultCellEditor's clickCountToStart=2);
        // AbstractCellEditor's default returns true for any event, so a single click would start an
        // edit and a no-op commit on the next navigation. Keyboard/programmatic starts stay enabled.
        if (anEvent instanceof MouseEvent) {
            return ((MouseEvent) anEvent).getClickCount() >= 2;
        }
        return true;
    }

    @Override
    public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected,
                                                 int row, int column) {
        field.setBorder(NORMAL_BORDER);
        field.setText(DbfValueFormatter.format(value, def));
        return field;
    }

    @Override
    public Object getCellEditorValue() {
        try {
            return parse(field.getText());
        } catch (ParseException | NumberFormatException e) {
            return null;
        }
    }

    @Override
    public boolean stopCellEditing() {
        try {
            parse(field.getText());
        } catch (ParseException | NumberFormatException e) {
            field.setBorder(ERROR_BORDER);
            return false;
        }
        return super.stopCellEditing();
    }

    private @Nullable Object parse(String raw) throws ParseException {
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        switch (def.getType()) {
            case NUMERIC:
            case FLOATING_POINT:
                BigDecimal bd = new BigDecimal(text);
                if (bd.scale() > def.getDecimalCount()) {
                    throw new NumberFormatException("too many decimals");
                }
                if (!def.fitsNumeric(bd)) {
                    throw new NumberFormatException("value too long for field");
                }
                return bd;
            case DATE:
                SimpleDateFormat sdf = new SimpleDateFormat(DbfValueFormatter.DATE_PATTERN);
                sdf.setLenient(false);
                return sdf.parse(text);
            default:
                return text;
        }
    }

    private static boolean isCharacter(DBFDataType type) {
        return type == DBFDataType.CHARACTER || type == DBFDataType.VARCHAR;
    }

    /**
     * Restricts the document so its {@code charset} encoding stays within {@code maxBytes} — DBF
     * character fields are sized in bytes, so inserted text is truncated on byte (not char)
     * boundaries to mirror what javadbf writes.
     */
    private static final class MaxLengthFilter extends DocumentFilter {
        private final int maxBytes;
        private final Charset charset;

        MaxLengthFilter(int maxBytes, Charset charset) {
            this.maxBytes = maxBytes;
            this.charset = charset;
        }

        @Override
        public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr)
                throws BadLocationException {
            if (string == null || string.isEmpty()) {
                return;
            }
            Document doc = fb.getDocument();
            int room = maxBytes - byteLength(doc.getText(0, doc.getLength()));
            String fitted = DbfValueFormatter.truncateToBytes(string, room, charset);
            if (!fitted.isEmpty()) {
                super.insertString(fb, offset, fitted, attr);
            }
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
                throws BadLocationException {
            if (text == null || text.isEmpty()) {
                super.replace(fb, offset, length, text, attrs);
                return;
            }
            Document doc = fb.getDocument();
            String full = doc.getText(0, doc.getLength());
            String remaining = full.substring(0, offset) + full.substring(offset + length);
            int room = maxBytes - byteLength(remaining);
            super.replace(fb, offset, length, DbfValueFormatter.truncateToBytes(text, room, charset), attrs);
        }

        private int byteLength(String s) {
            return s.getBytes(charset).length;
        }
    }
}
