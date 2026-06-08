package com.poliproger.dbfreader.ui.cell;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.fields.ExtendableTextComponent;
import com.intellij.ui.components.fields.ExtendableTextField;
import com.intellij.util.ui.CalendarView;
import com.intellij.util.ui.JBUI;
import com.poliproger.dbfreader.DbfBundle;
import com.poliproger.dbfreader.model.DbfColumnDef;
import com.poliproger.dbfreader.ui.DbfValueFormatter;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractCellEditor;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.border.Border;
import javax.swing.table.TableCellEditor;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.MouseEvent;
import java.text.ParseException;
import java.util.Date;
import java.util.EventObject;

/**
 * Cell editor for DBF date ({@code D}) fields. Keeps an editable {@code yyyy-MM-dd} text field (so
 * keyboard entry, clearing to an empty date and the invalid-input red border all keep working as for
 * any other type), and adds a trailing button that opens IntelliJ's native {@link CalendarView} in a
 * popup. Picking a date writes its {@code yyyy-MM-dd} form into the field and commits the edit, so the
 * value is parsed and stored through the same path as typed input.
 */
public final class DbfDateCellEditor extends AbstractCellEditor implements TableCellEditor {

    private static final Border NORMAL_BORDER = BorderFactory.createEmptyBorder(1, 3, 1, 3);
    private static final Border ERROR_BORDER = BorderFactory.createLineBorder(JBColor.RED, 1);

    private final DbfColumnDef def;
    private final ExtendableTextField field = new ExtendableTextField();
    private @Nullable JBPopup popup;

    public DbfDateCellEditor(DbfColumnDef def) {
        this.def = def;
        field.setBorder(NORMAL_BORDER);
        field.addExtension(ExtendableTextComponent.Extension.create(
                AllIcons.General.Ellipsis, DbfBundle.message("editor.date.picker.tooltip"), this::showCalendarPopup));
    }

    @Override
    public boolean isCellEditable(EventObject anEvent) {
        // Require a double-click to enter edit mode, mirroring DbfTextCellEditor; keyboard/programmatic
        // starts stay enabled. Once editing, the trailing button opens the calendar.
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
            return DbfValueFormatter.parseDate(field.getText());
        } catch (ParseException e) {
            return null;
        }
    }

    @Override
    public boolean stopCellEditing() {
        try {
            DbfValueFormatter.parseDate(field.getText());
        } catch (ParseException e) {
            field.setBorder(ERROR_BORDER);
            return false;
        }
        return super.stopCellEditing();
    }

    private void showCalendarPopup() {
        if (popup != null && popup.isVisible()) {
            return;
        }
        CalendarView calendar = new CalendarView(CalendarView.Mode.DATE);
        Date initial = currentDate();
        calendar.setDate(initial != null ? initial : new Date());

        JButton okButton = new JButton(DbfBundle.message("editor.date.picker.ok"));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.add(okButton);

        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(JBUI.Borders.empty(8));
        content.add(calendar, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);

        popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(content, calendar.getDaysCombo())
                .setRequestFocus(true)
                .setResizable(false)
                .setMovable(false)
                .createPopup();

        // Format the picked date to yyyy-MM-dd and commit: the text round-trip drops any time component
        // CalendarView carries, so the stored value is a clean date-only Date.
        Runnable apply = () -> {
            field.setText(DbfValueFormatter.format(calendar.getDate(), def));
            if (popup != null) {
                popup.cancel();
            }
            stopCellEditing();
        };
        okButton.addActionListener(e -> apply.run());
        calendar.registerEnterHandler(apply);

        popup.showUnderneathOf(field);
    }

    private @Nullable Date currentDate() {
        try {
            return DbfValueFormatter.parseDate(field.getText());
        } catch (ParseException e) {
            return null;
        }
    }
}