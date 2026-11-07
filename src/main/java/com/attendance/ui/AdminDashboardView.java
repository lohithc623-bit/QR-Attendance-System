package com.attendance.ui;

import com.attendance.exception.DataAccessException;
import com.attendance.model.Faculty;
import com.attendance.model.SchoolClass;
import com.attendance.model.Student;
import com.attendance.model.Subject;
import com.attendance.model.User;
import com.attendance.service.AdminService;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Optional;

public class AdminDashboardView {

    private final SceneManager sceneManager;
    private final User user;
    private final AdminService adminService = new AdminService();

    public AdminDashboardView(SceneManager sceneManager, User user) {
        this.sceneManager = sceneManager;
        this.user = user;
    }

    public BorderPane getRoot() {
        BorderPane root = new BorderPane();
        root.setTop(buildTopBar());

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(buildClassesTab(), buildSubjectsTab(), buildFacultyTab(), buildStudentsTab());
        root.setCenter(tabs);
        return root;
    }

    private HBox buildTopBar() {
        Label header = new Label("Administrator: " + user.getFullName());
        header.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        Button logout = new Button("Logout");
        logout.setOnAction(e -> sceneManager.showLogin());
        HBox bar = new HBox(20, header, sceneManager.themeButton(), logout);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10));
        HBox.setHgrow(header, Priority.ALWAYS);
        return bar;
    }

    // --- Classes ---------------------------------------------------------------------------

    private Tab buildClassesTab() {
        TableView<SchoolClass> table = new TableView<>();
        addCol(table, "Class Name", "className");
        addCol(table, "Department", "department");
        addCol(table, "Semester", "semester");
        addCol(table, "Section", "section");
        refreshClasses(table);

        Button addButton = new Button("Add Class");
        addButton.setOnAction(e -> {
            Optional<SchoolClass> result = showClassDialog();
            result.ifPresent(c -> {
                try {
                    adminService.createClass(c.getClassName(), c.getDepartment(), c.getSemester(), c.getSection());
                    refreshClasses(table);
                } catch (DataAccessException ex) {
                    showError("Failed to create class: " + ex.getCause());
                }
            });
        });

        Button deleteButton = new Button("Delete Selected");
        deleteButton.setOnAction(e -> {
            SchoolClass selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            if (confirm("Delete class '" + selected.getClassName() + "'? This also removes its subjects and students.")) {
                adminService.deleteClass(selected.getId());
                refreshClasses(table);
            }
        });

        return wrapTab("Classes", table, addButton, deleteButton);
    }

    private Optional<SchoolClass> showClassDialog() {
        Dialog<SchoolClass> dialog = new Dialog<>();
        dialog.setTitle("Add Class");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextField nameField = new TextField();
        nameField.setPromptText("e.g. II CSE (AI & ML) A");
        TextField deptField = new TextField();
        deptField.setPromptText("e.g. CSE (AI & ML)");
        TextField semField = new TextField();
        semField.setPromptText("e.g. 3");
        TextField sectionField = new TextField();
        sectionField.setPromptText("e.g. A");

        GridPane grid = formGrid(
                "Class Name:", nameField,
                "Department:", deptField,
                "Semester:", semField,
                "Section:", sectionField);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != ButtonType.OK) return null;
            try {
                return new SchoolClass(0, nameField.getText().trim(), deptField.getText().trim(),
                        Integer.parseInt(semField.getText().trim()), sectionField.getText().trim());
            } catch (NumberFormatException ex) {
                showError("Semester must be a number.");
                return null;
            }
        });
        return dialog.showAndWait();
    }

    // --- Subjects ---------------------------------------------------------------------------

    private Tab buildSubjectsTab() {
        TableView<Subject> table = new TableView<>();
        addCol(table, "Code", "subjectCode");
        addCol(table, "Subject Name", "subjectName");
        refreshSubjects(table);

        Button addButton = new Button("Add Subject");
        addButton.setOnAction(e -> {
            Optional<Subject> result = showSubjectDialog();
            result.ifPresent(s -> {
                try {
                    adminService.createSubject(s.getSubjectCode(), s.getSubjectName(), s.getClassId(), s.getFacultyId());
                    refreshSubjects(table);
                } catch (DataAccessException ex) {
                    showError("Failed to create subject: " + ex.getCause());
                }
            });
        });

        Button deleteButton = new Button("Delete Selected");
        deleteButton.setOnAction(e -> {
            Subject selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            if (confirm("Delete subject '" + selected.getSubjectCode() + "'? This also removes its attendance records.")) {
                adminService.deleteSubject(selected.getId());
                refreshSubjects(table);
            }
        });

        return wrapTab("Subjects", table, addButton, deleteButton);
    }

    private Optional<Subject> showSubjectDialog() {
        List<SchoolClass> classes = adminService.listClasses();
        List<Faculty> facultyList = adminService.listFaculty();
        if (classes.isEmpty() || facultyList.isEmpty()) {
            showError("Add at least one class and one faculty member first.");
            return Optional.empty();
        }

        Dialog<Subject> dialog = new Dialog<>();
        dialog.setTitle("Add Subject");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextField codeField = new TextField();
        codeField.setPromptText("e.g. CSE203");
        TextField nameField = new TextField();
        nameField.setPromptText("e.g. Object Oriented Programming using Java");
        ComboBox<SchoolClass> classCombo = new ComboBox<>(FXCollections.observableArrayList(classes));
        ComboBox<Faculty> facultyCombo = new ComboBox<>(FXCollections.observableArrayList(facultyList));
        facultyCombo.setConverter(facultyDisplay());

        GridPane grid = formGrid(
                "Subject Code:", codeField,
                "Subject Name:", nameField,
                "Class:", classCombo,
                "Faculty:", facultyCombo);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != ButtonType.OK) return null;
            if (classCombo.getValue() == null || facultyCombo.getValue() == null) {
                showError("Select both a class and a faculty member.");
                return null;
            }
            return new Subject(0, codeField.getText().trim(), nameField.getText().trim(),
                    classCombo.getValue().getId(), facultyCombo.getValue().getId());
        });
        return dialog.showAndWait();
    }

    // --- Faculty ---------------------------------------------------------------------------

    private Tab buildFacultyTab() {
        TableView<Faculty> table = new TableView<>();
        addCol(table, "Faculty Code", "facultyCode");
        addCol(table, "Name", "fullName");
        addCol(table, "Department", "department");
        refreshFaculty(table);

        Button addButton = new Button("Add Faculty");
        addButton.setOnAction(e -> {
            Optional<Faculty> result = showFacultyDialog();
            result.ifPresent(f -> refreshFaculty(table));
        });

        Button deleteButton = new Button("Delete Selected");
        deleteButton.setOnAction(e -> {
            Faculty selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            if (confirm("Delete faculty '" + selected.getFullName() + "'? This also removes their subjects.")) {
                adminService.deleteFaculty(selected.getId());
                refreshFaculty(table);
            }
        });

        return wrapTab("Faculty", table, addButton, deleteButton);
    }

    private Optional<Faculty> showFacultyDialog() {
        Dialog<Faculty> dialog = new Dialog<>();
        dialog.setTitle("Add Faculty");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextField codeField = new TextField();
        codeField.setPromptText("e.g. FAC1002 (used to log in)");
        TextField nameField = new TextField();
        nameField.setPromptText("Full name");
        TextField deptField = new TextField();
        deptField.setPromptText("Department");
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Initial password");

        GridPane grid = formGrid(
                "Faculty ID:", codeField,
                "Full Name:", nameField,
                "Department:", deptField,
                "Password:", passwordField);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != ButtonType.OK) return null;
            if (codeField.getText().isBlank() || passwordField.getText().isBlank()) {
                showError("Faculty ID and password are required.");
                return null;
            }
            try {
                return adminService.createFaculty(codeField.getText().trim(), nameField.getText().trim(),
                        deptField.getText().trim(), passwordField.getText());
            } catch (IllegalArgumentException ex) {
                showError(ex.getMessage());
                return null;
            } catch (DataAccessException ex) {
                showError("Failed to create faculty (ID may already be in use): " + ex.getCause());
                return null;
            }
        });
        return dialog.showAndWait();
    }

    // --- Students ---------------------------------------------------------------------------

    private Tab buildStudentsTab() {
        TableView<Student> table = new TableView<>();
        addCol(table, "Register No.", "registerNumber");
        addCol(table, "Name", "fullName");
        addCol(table, "Department", "department");
        addCol(table, "Semester", "semester");
        addCol(table, "Section", "section");
        refreshStudents(table);

        Button addButton = new Button("Add Student");
        addButton.setOnAction(e -> {
            Optional<Student> result = showStudentDialog();
            result.ifPresent(s -> refreshStudents(table));
        });

        Button deleteButton = new Button("Delete Selected");
        deleteButton.setOnAction(e -> {
            Student selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            if (confirm("Delete student '" + selected.getFullName() + "'? This also removes their attendance records.")) {
                adminService.deleteStudent(selected.getId());
                refreshStudents(table);
            }
        });

        return wrapTab("Students", table, addButton, deleteButton);
    }

    private Optional<Student> showStudentDialog() {
        List<SchoolClass> classes = adminService.listClasses();
        if (classes.isEmpty()) {
            showError("Add at least one class first.");
            return Optional.empty();
        }

        Dialog<Student> dialog = new Dialog<>();
        dialog.setTitle("Add Student");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextField regNoField = new TextField();
        regNoField.setPromptText("e.g. 9923CS0003 (used to log in)");
        TextField nameField = new TextField();
        nameField.setPromptText("Full name");
        ComboBox<SchoolClass> classCombo = new ComboBox<>(FXCollections.observableArrayList(classes));
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Initial password");

        GridPane grid = formGrid(
                "Register No.:", regNoField,
                "Full Name:", nameField,
                "Class:", classCombo,
                "Password:", passwordField);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != ButtonType.OK) return null;
            if (regNoField.getText().isBlank() || passwordField.getText().isBlank() || classCombo.getValue() == null) {
                showError("Register number, class, and password are required.");
                return null;
            }
            SchoolClass selectedClass = classCombo.getValue();
            try {
                return adminService.createStudent(regNoField.getText().trim(), nameField.getText().trim(),
                        selectedClass.getDepartment(), selectedClass.getSemester(), selectedClass.getSection(),
                        selectedClass.getId(), passwordField.getText());
            } catch (IllegalArgumentException ex) {
                showError(ex.getMessage());
                return null;
            } catch (DataAccessException ex) {
                showError("Failed to create student (register number may already be in use): " + ex.getCause());
                return null;
            }
        });
        return dialog.showAndWait();
    }

    // --- Shared helpers ---------------------------------------------------------------------------

    private <T> void addCol(TableView<T> table, String header, String property) {
        TableColumn<T, Object> col = new TableColumn<>(header);
        col.setCellValueFactory(new PropertyValueFactory<>(property));
        table.getColumns().add(col);
    }

    private Tab wrapTab(String title, TableView<?> table, Button addButton, Button deleteButton) {
        HBox buttonBar = new HBox(10, addButton, deleteButton);
        buttonBar.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(10, buttonBar, table);
        box.setPadding(new Insets(15));
        VBox.setVgrow(table, Priority.ALWAYS);
        return new Tab(title, box);
    }

    private GridPane formGrid(Object... labelsAndFields) {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(15));
        for (int i = 0; i < labelsAndFields.length; i += 2) {
            grid.add(new Label((String) labelsAndFields[i]), 0, i / 2);
            grid.add((javafx.scene.Node) labelsAndFields[i + 1], 1, i / 2);
        }
        return grid;
    }

    private javafx.util.StringConverter<Faculty> facultyDisplay() {
        return new javafx.util.StringConverter<>() {
            @Override
            public String toString(Faculty f) { return f == null ? "" : f.getFacultyCode() + " - " + f.getFullName(); }
            @Override
            public Faculty fromString(String s) { return null; }
        };
    }

    private void refreshClasses(TableView<SchoolClass> table) {
        try {
            table.setItems(FXCollections.observableArrayList(adminService.listClasses()));
        } catch (DataAccessException e) {
            showError("Failed to load classes: " + e.getCause());
        }
    }

    private void refreshSubjects(TableView<Subject> table) {
        try {
            table.setItems(FXCollections.observableArrayList(adminService.listSubjects()));
        } catch (DataAccessException e) {
            showError("Failed to load subjects: " + e.getCause());
        }
    }

    private void refreshFaculty(TableView<Faculty> table) {
        try {
            table.setItems(FXCollections.observableArrayList(adminService.listFaculty()));
        } catch (DataAccessException e) {
            showError("Failed to load faculty: " + e.getCause());
        }
    }

    private void refreshStudents(TableView<Student> table) {
        try {
            table.setItems(FXCollections.observableArrayList(adminService.listStudents()));
        } catch (DataAccessException e) {
            showError("Failed to load students: " + e.getCause());
        }
    }

    private boolean confirm(String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.YES, ButtonType.NO);
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.YES;
    }

    private void showError(String message) {
        new Alert(Alert.AlertType.ERROR, message).showAndWait();
    }
}
