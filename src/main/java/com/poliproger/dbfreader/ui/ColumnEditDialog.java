package com.poliproger.dbfreader.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.linuxense.javadbf.DBFDataType;
import com.poliproger.dbfreader.DbfBundle;
import com.poliproger.dbfreader.model.DbfColumnDef;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Dialog for adding or editing a DBF column. Restricts the type to the set of writable field types
 * (C, N, F, L, D) and validates the field name and size against DBF constraints.
 */
public final class ColumnEditDialog extends DialogWrapper {

    /** Writable field types offered to the user. */
    private static final DBFDataType[] TYPES = {
            DBFDataType.CHARACTER, DBFDataType.NUMERIC, DBFDataType.FLOATING_POINT,
            DBFDataType.LOGICAL, DBFDataType.DATE
    };

    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Z0-9_]+");
    private static final int MAX_NAME = 10;

    private final JBTextField nameField = new JBTextField();
    private final ComboBox<DBFDataType> typeCombo = new ComboBox<>(TYPES);
    private final JSpinner lengthSpinner = new JSpinner(new SpinnerNumberModel(10, 1, 254, 1));
    private final JSpinner decimalsSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 18, 1));

    private final Set<String> otherNames;

    public ColumnEditDialog(@Nullable Project project, @Nullable DbfColumnDef existing, Set<String> otherNames) {
        super(project);
        this.otherNames = otherNames;
        setTitle(existing == null
                ? DbfBundle.message("dialog.column.title.add")
                : DbfBundle.message("dialog.column.title.edit"));

        typeCombo.setRenderer(new SimpleListCellRenderer<DBFDataType>() {
            @Override
            public void customize(@NotNull JList<? extends DBFDataType> list, DBFDataType value,
                                  int index, boolean selected, boolean hasFocus) {
                setText(value == null ? "" : value.name() + " (" + (char) value.getCode() + ")");
            }
        });
        typeCombo.addActionListener(e -> syncSpinnerState());

        if (existing != null) {
            nameField.setText(existing.getName());
            typeCombo.setSelectedItem(existing.getType());
            lengthSpinner.setValue(clampLength(existing.getType(), existing.getLength()));
            decimalsSpinner.setValue(existing.getDecimalCount());
        }
        syncSpinnerState();
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = FormBuilder.createFormBuilder()
                .addLabeledComponent(DbfBundle.message("dialog.column.name"), nameField)
                .addLabeledComponent(DbfBundle.message("dialog.column.type"), typeCombo)
                .addLabeledComponent(DbfBundle.message("dialog.column.length"), lengthSpinner)
                .addLabeledComponent(DbfBundle.message("dialog.column.decimals"), decimalsSpinner)
                .getPanel();
        panel.setPreferredSize(new java.awt.Dimension(320, panel.getPreferredSize().height));
        return panel;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return nameField;
    }

    private void syncSpinnerState() {
        DBFDataType type = getSelectedType();
        boolean lengthEditable = type == DBFDataType.CHARACTER
                || type == DBFDataType.NUMERIC || type == DBFDataType.FLOATING_POINT;
        boolean decimals = type == DBFDataType.NUMERIC || type == DBFDataType.FLOATING_POINT;

        lengthSpinner.setEnabled(lengthEditable);
        decimalsSpinner.setEnabled(decimals);
        if (type == DBFDataType.DATE) {
            lengthSpinner.setValue(8);
        } else if (type == DBFDataType.LOGICAL) {
            lengthSpinner.setValue(1);
        }
        if (!decimals) {
            decimalsSpinner.setValue(0);
        }
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        String name = normalizedName();
        if (name.isEmpty()) {
            return new ValidationInfo(DbfBundle.message("dialog.column.error.nameEmpty"), nameField);
        }
        if (name.length() > MAX_NAME) {
            return new ValidationInfo(DbfBundle.message("dialog.column.error.nameTooLong"), nameField);
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            return new ValidationInfo(DbfBundle.message("dialog.column.error.nameChars"), nameField);
        }
        if (otherNames.contains(name)) {
            return new ValidationInfo(DbfBundle.message("dialog.column.error.nameDuplicate"), nameField);
        }

        DBFDataType type = getSelectedType();
        int length = (int) lengthSpinner.getValue();
        int min = type.getMinSize();
        int max = type == DBFDataType.CHARACTER ? 254 : type.getMaxSize();
        if (max <= 0) {
            max = 254;
        }
        if (length < Math.max(1, min) || length > max) {
            return new ValidationInfo(
                    DbfBundle.message("dialog.column.error.length", Math.max(1, min), max), lengthSpinner);
        }
        if (type == DBFDataType.NUMERIC || type == DBFDataType.FLOATING_POINT) {
            int decimals = (int) decimalsSpinner.getValue();
            // With decimals the stored form needs a decimal point plus at least one integer digit, so
            // length must be >= decimals + 2. With no decimals there is no point: length >= 1 (already
            // enforced above) is enough, so a single-digit NUMERIC(1,0) is valid.
            if (decimals < 0 || (decimals > 0 && decimals > length - 2)) {
                return new ValidationInfo(DbfBundle.message("dialog.column.error.decimals"), decimalsSpinner);
            }
        }
        return null;
    }

    public DbfColumnDef getResult() {
        DBFDataType type = getSelectedType();
        int decimals = (type == DBFDataType.NUMERIC || type == DBFDataType.FLOATING_POINT)
                ? (int) decimalsSpinner.getValue() : 0;
        return new DbfColumnDef(normalizedName(), type,
                (int) lengthSpinner.getValue(), decimals);
    }

    /** Field name normalized to the DBF convention: trimmed and upper-cased locale-independently. */
    private String normalizedName() {
        return nameField.getText().trim().toUpperCase(Locale.ROOT);
    }

    private DBFDataType getSelectedType() {
        DBFDataType type = (DBFDataType) typeCombo.getSelectedItem();
        return type == null ? DBFDataType.CHARACTER : type;
    }

    private static int clampLength(DBFDataType type, int length) {
        if (type == DBFDataType.DATE) {
            return 8;
        }
        if (type == DBFDataType.LOGICAL) {
            return 1;
        }
        return Math.max(1, Math.min(length, 254));
    }
}
