package com.attendance.ui;

import com.attendance.exception.AccessDeniedException;
import com.attendance.exception.DataAccessException;
import com.attendance.exception.DuplicateAttendanceException;
import com.attendance.exception.InvalidQRCodeException;
import com.attendance.exception.StudentClassMismatchException;
import com.attendance.exception.StudentNotRegisteredException;
import com.attendance.model.Faculty;
import com.attendance.model.RosterEntry;
import com.attendance.model.SchoolClass;
import com.attendance.model.Student;
import com.attendance.model.StudentAttendanceSummary;
import com.attendance.model.Subject;
import com.attendance.model.User;
import com.attendance.service.AttendanceService;
import com.attendance.util.CsvUtil;
import com.attendance.util.QRCodeUtil;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class FacultyDashboardView {

    private final SceneManager sceneManager;
    private final User user;
    private final Faculty faculty;
    private final AttendanceService attendanceService = new AttendanceService();

    private final ComboBox<SchoolClass> classCombo = new ComboBox<>();
    private final ComboBox<Subject> subjectCombo = new ComboBox<>();
    private final DatePicker datePicker = new DatePicker(LocalDate.now());
    private final ComboBox<Integer> periodCombo = new ComboBox<>();
    private final Label scanStatusLabel = new Label("Select class, subject, date and period, then scan students' QR codes.");
    private final Label countsLabel = new Label();
    private final TableView<RosterEntry> rosterTable = new TableView<>();
    private Timeline autoRefresh;

    public FacultyDashboardView(SceneManager sceneManager, User user, Faculty faculty) {
        this.sceneManager = sceneManager;
        this.user = user;
        this.faculty = faculty;
    }

    public BorderPane getRoot() {
        BorderPane root = new BorderPane();
        root.setTop(buildTopBar());
        root.setCenter(buildTabs());
        return root;
    }

    private HBox buildTopBar() {
        Label header = new Label("Faculty: " + faculty.getFullName() + " (" + faculty.getFacultyCode() + ", " + faculty.getDepartment() + ")");
        header.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        Button logout = new Button("Logout");
        logout.setOnAction(e -> {
            if (autoRefresh != null) autoRefresh.stop();
            sceneManager.showLogin();
        });
        HBox bar = new HBox(12, header, sceneManager.themeButton(), logout);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10));
        HBox.setHgrow(header, Priority.ALWAYS);
        header.setMaxWidth(Double.MAX_VALUE);
        return bar;
    }

    private TabPane buildTabs() {
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(buildScanTab(), buildReportsTab());
        return tabs;
    }

    // --- Tab 1: live session ---------------------------------------------------------------

    private Tab buildScanTab() {
        try {
            classCombo.setItems(FXCollections.observableArrayList(attendanceService.getClassesForFaculty(faculty.getId())));
        } catch (DataAccessException e) {
            showError("Failed to load classes: " + e.getMessage());
        }
        classCombo.setPromptText("Select class");
        classCombo.setOnAction(e -> { refreshSubjects(); refreshRoster(); });
        subjectCombo.setPromptText("Select subject");
        subjectCombo.setOnAction(e -> refreshRoster());
        datePicker.setOnAction(e -> refreshRoster());
        for (int p = 1; p <= AttendanceService.MAX_PERIOD; p++) periodCombo.getItems().add(p);
        periodCombo.setPromptText("Period");
        periodCombo.setOnAction(e -> refreshRoster());

        Button cameraButton = new Button("Live camera scan");
        cameraButton.setOnAction(e -> openCameraScan(cameraButton));
        Button fileButton = new Button("Scan image file...");
        fileButton.setOnAction(e -> scanImageFile(fileButton));
        Button finishButton = new Button("Finish session (mark the rest absent)");
        finishButton.setOnAction(e -> finishSession());

        HBox selectors = new HBox(10,
                new Label("Class:"), classCombo, new Label("Subject:"), subjectCombo,
                new Label("Date:"), datePicker, new Label("Period:"), periodCombo);
        selectors.setAlignment(Pos.CENTER_LEFT);
        HBox actions = new HBox(10, cameraButton, fileButton, finishButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        scanStatusLabel.setWrapText(true);
        countsLabel.setStyle("-fx-font-weight: bold;");

        TableColumn<RosterEntry, String> regCol = new TableColumn<>("Register No.");
        regCol.setCellValueFactory(new PropertyValueFactory<>("registerNumber"));
        TableColumn<RosterEntry, String> nameCol = new TableColumn<>("Student Name");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("studentName"));
        nameCol.setPrefWidth(200);

        TableColumn<RosterEntry, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(new PropertyValueFactory<>("statusLabel"));
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                if (empty || item == null) { setStyle(""); return; }
                setStyle(switch (item) {
                    case "PRESENT" -> "-fx-text-fill: #1e8449; -fx-font-weight: bold;";
                    case "ABSENT" -> "-fx-text-fill: #c0392b; -fx-font-weight: bold;";
                    default -> "-fx-text-fill: #888;";
                });
            }
        });
        TableColumn<RosterEntry, String> methodCol = new TableColumn<>("How");
        methodCol.setCellValueFactory(new PropertyValueFactory<>("method"));
        TableColumn<RosterEntry, String> timeCol = new TableColumn<>("Marked At");
        timeCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                data.getValue().getMarkedAt() == null ? "" : data.getValue().getMarkedAt().format(DateTimeFormatter.ofPattern("HH:mm:ss"))));

        // Manual override: one button per row that flips PRESENT <-> ABSENT.
        TableColumn<RosterEntry, Void> overrideCol = new TableColumn<>("Override");
        overrideCol.setCellFactory(col -> new TableCell<>() {
            private final Button button = new Button();
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) { setGraphic(null); return; }
                RosterEntry entry = getTableRow().getItem();
                boolean present = "PRESENT".equals(entry.getStatus());
                button.setText(present ? "Mark absent" : "Mark present");
                button.setOnAction(e -> overrideStatus(entry, present ? "ABSENT" : "PRESENT"));
                setGraphic(button);
            }
        });
        overrideCol.setPrefWidth(120);

        rosterTable.getColumns().addAll(List.of(regCol, nameCol, statusCol, methodCol, timeCol, overrideCol));
        rosterTable.setPlaceholder(new Label("Choose class, subject, date and period to see the live list."));

        // Live list: refresh every 3 s so scans from any device show up without clicking anything.
        autoRefresh = new Timeline(new KeyFrame(Duration.seconds(3), e -> {
            if (rosterTable.getScene() == null) { autoRefresh.stop(); return; }
            refreshRoster();
        }));
        autoRefresh.setCycleCount(Animation.INDEFINITE);
        autoRefresh.play();

        VBox box = new VBox(10, selectors, actions, scanStatusLabel, countsLabel, rosterTable);
        box.setPadding(new Insets(15));
        VBox.setVgrow(rosterTable, Priority.ALWAYS);
        return new Tab("Mark Attendance", box);
    }

    private void refreshSubjects() {
        SchoolClass selectedClass = classCombo.getValue();
        if (selectedClass == null) return;
        try {
            subjectCombo.setItems(FXCollections.observableArrayList(
                    attendanceService.getSubjectsForFaculty(faculty.getId(), selectedClass.getId())));
        } catch (DataAccessException e) {
            showError("Failed to load subjects: " + e.getMessage());
        }
    }

    private boolean selectionComplete() {
        return classCombo.getValue() != null && subjectCombo.getValue() != null
                && datePicker.getValue() != null && periodCombo.getValue() != null;
    }

    private void refreshRoster() {
        if (!selectionComplete()) return;
        try {
            List<RosterEntry> roster = attendanceService.getRoster(faculty.getId(), classCombo.getValue().getId(),
                    subjectCombo.getValue().getId(), datePicker.getValue(), periodCombo.getValue());
            rosterTable.setItems(FXCollections.observableArrayList(roster));
            long present = roster.stream().filter(r -> "PRESENT".equals(r.getStatus())).count();
            long absent = roster.stream().filter(r -> "ABSENT".equals(r.getStatus())).count();
            countsLabel.setText("Present: " + present + "    Absent: " + absent
                    + "    Not marked yet: " + (roster.size() - present - absent));
            if (autoRefresh != null && autoRefresh.getStatus() != Animation.Status.RUNNING) autoRefresh.play();
        } catch (DataAccessException | AccessDeniedException | IllegalArgumentException e) {
            // No modal dialog here: this also runs from the 3-second timer, where showAndWait is illegal.
            if (autoRefresh != null) autoRefresh.stop();
            setScanStatus("Could not load the live list: " + e.getMessage(), false);
        }
    }

    // --- scanning ------------------------------------------------------------------------------

    /** Runs the full rule chain for one decoded payload and turns every outcome into a message. */
    private ScanDialog.Outcome processPayload(String payload, int classId, int subjectId, LocalDate date, int period) {
        try {
            Student student = attendanceService.markAttendance(payload, faculty.getId(), classId, subjectId, date, period);
            return new ScanDialog.Outcome(true, student.getFullName() + " (" + student.getRegisterNumber() + ") marked present");
        } catch (InvalidQRCodeException | StudentClassMismatchException
                 | StudentNotRegisteredException | DuplicateAttendanceException e) {
            return new ScanDialog.Outcome(false, e.getMessage());
        } catch (AccessDeniedException | IllegalArgumentException | DataAccessException e) {
            return new ScanDialog.Outcome(false, e.getMessage());
        }
    }

    private void openCameraScan(Button anchor) {
        if (!selectionComplete()) {
            setScanStatus("Select class, subject, date and period first.", false);
            return;
        }
        final int classId = classCombo.getValue().getId();
        final int subjectId = subjectCombo.getValue().getId();
        final LocalDate date = datePicker.getValue();
        final int period = periodCombo.getValue();
        ScanDialog.open(anchor.getScene().getWindow(),
                payload -> processPayload(payload, classId, subjectId, date, period),
                this::refreshRoster);
    }

    private void scanImageFile(Button anchor) {
        if (!selectionComplete()) {
            setScanStatus("Select class, subject, date and period first.", false);
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select a photo/screenshot of the student's QR code");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Image files", "*.png", "*.jpg", "*.jpeg"));
        File file = chooser.showOpenDialog((Stage) anchor.getScene().getWindow());
        if (file == null) return;
        try {
            String payload = QRCodeUtil.decodeQRImageFile(file);
            ScanDialog.Outcome outcome = processPayload(payload, classCombo.getValue().getId(),
                    subjectCombo.getValue().getId(), datePicker.getValue(), periodCombo.getValue());
            setScanStatus(outcome.message(), outcome.success());
            refreshRoster();
        } catch (IOException e) {
            setScanStatus("Could not read that image file: " + e.getMessage(), false);
        }
    }

    private void overrideStatus(RosterEntry entry, String newStatus) {
        try {
            attendanceService.setStatus(faculty.getId(), entry.getStudentId(), classCombo.getValue().getId(),
                    subjectCombo.getValue().getId(), datePicker.getValue(), periodCombo.getValue(), newStatus);
            setScanStatus(entry.getStudentName() + " set to " + newStatus + " (manual override)", true);
            refreshRoster();
        } catch (StudentClassMismatchException | StudentNotRegisteredException
                 | AccessDeniedException | IllegalArgumentException | DataAccessException e) {
            setScanStatus(e.getMessage(), false);
        }
    }

    private void finishSession() {
        if (!selectionComplete()) {
            setScanStatus("Select class, subject, date and period first.", false);
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Mark every student who has not been scanned as ABSENT for this period?",
                javafx.scene.control.ButtonType.YES, javafx.scene.control.ButtonType.NO);
        if (confirm.showAndWait().orElse(javafx.scene.control.ButtonType.NO) != javafx.scene.control.ButtonType.YES) return;
        try {
            int n = attendanceService.finalizeSession(faculty.getId(), classCombo.getValue().getId(),
                    subjectCombo.getValue().getId(), datePicker.getValue(), periodCombo.getValue());
            setScanStatus(n + " student(s) marked absent. A late student can still be scanned or overridden.", true);
            refreshRoster();
        } catch (AccessDeniedException | IllegalArgumentException | DataAccessException e) {
            setScanStatus(e.getMessage(), false);
        }
    }

    private void setScanStatus(String message, boolean good) {
        scanStatusLabel.setText(message);
        scanStatusLabel.setStyle(good ? "-fx-text-fill: #1e8449;" : "-fx-text-fill: #c0392b;");
    }

    // --- Tab 2: reports -------------------------------------------------------------------------

    private Tab buildReportsTab() {
        List<SchoolClass> classes;
        try {
            classes = attendanceService.getClassesForFaculty(faculty.getId());
        } catch (DataAccessException e) {
            showError("Failed to load classes: " + e.getMessage());
            classes = List.of();
        }
        ComboBox<SchoolClass> reportClassCombo = new ComboBox<>(FXCollections.observableArrayList(classes));
        reportClassCombo.setPromptText("Select class");
        ComboBox<Subject> reportSubjectCombo = new ComboBox<>();
        reportSubjectCombo.setPromptText("Select subject");
        reportSubjectCombo.setDisable(true);

        ToggleGroup reportType = new ToggleGroup();
        RadioButton classWise = new RadioButton("Class-wise (all subjects combined)");
        classWise.setToggleGroup(reportType);
        classWise.setSelected(true);
        RadioButton subjectWise = new RadioButton("Subject-wise");
        subjectWise.setToggleGroup(reportType);
        subjectWise.selectedProperty().addListener((obs, o, isSubject) -> reportSubjectCombo.setDisable(!isSubject));
        reportClassCombo.setOnAction(e -> {
            SchoolClass selected = reportClassCombo.getValue();
            if (selected != null) {
                reportSubjectCombo.setItems(FXCollections.observableArrayList(
                        attendanceService.getSubjectsForFaculty(faculty.getId(), selected.getId())));
            }
        });

        TableView<StudentAttendanceSummary> reportTable = new TableView<>();
        TableColumn<StudentAttendanceSummary, String> regCol = new TableColumn<>("Register No.");
        regCol.setCellValueFactory(new PropertyValueFactory<>("registerNumber"));
        TableColumn<StudentAttendanceSummary, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("studentName"));
        TableColumn<StudentAttendanceSummary, Integer> presentCol = new TableColumn<>("Present");
        presentCol.setCellValueFactory(new PropertyValueFactory<>("presentCount"));
        TableColumn<StudentAttendanceSummary, Integer> totalCol = new TableColumn<>("Total Classes");
        totalCol.setCellValueFactory(new PropertyValueFactory<>("totalClasses"));
        TableColumn<StudentAttendanceSummary, String> pctCol = new TableColumn<>("Percentage");
        pctCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                String.format("%.1f%%", data.getValue().getPercentage())));
        pctCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                StudentAttendanceSummary row = getTableRow() == null ? null : getTableRow().getItem();
                setStyle(!empty && row != null && row.getTotalClasses() > 0 && row.getPercentage() < 75
                        ? "-fx-text-fill: #c0392b; -fx-font-weight: bold;" : "");
            }
        });
        reportTable.getColumns().addAll(List.of(regCol, nameCol, presentCol, totalCol, pctCol));
        reportTable.setPlaceholder(new Label("Choose a class (and subject, if subject-wise) then click Generate."));

        Button generateButton = new Button("Generate Report");
        generateButton.setOnAction(e -> {
            List<StudentAttendanceSummary> rows = loadReport(reportClassCombo, reportSubjectCombo, subjectWise.isSelected());
            if (rows != null) reportTable.setItems(FXCollections.observableArrayList(rows));
        });

        Button exportButton = new Button("Export CSV...");
        exportButton.setOnAction(e -> {
            List<StudentAttendanceSummary> rows = loadReport(reportClassCombo, reportSubjectCombo, subjectWise.isSelected());
            if (rows == null) return;
            reportTable.setItems(FXCollections.observableArrayList(rows));
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Save attendance report");
            chooser.setInitialFileName(subjectWise.isSelected() ? "subject-attendance-report.csv" : "class-attendance-report.csv");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV (opens in Excel)", "*.csv"));
            File file = chooser.showSaveDialog(exportButton.getScene().getWindow());
            if (file == null) return;
            List<List<String>> out = new ArrayList<>();
            for (StudentAttendanceSummary r : rows) {
                out.add(List.of(r.getRegisterNumber(), r.getStudentName(), String.valueOf(r.getPresentCount()),
                        String.valueOf(r.getTotalClasses()), String.format("%.1f%%", r.getPercentage())));
            }
            try {
                CsvUtil.write(file.toPath(), List.of("Register No.", "Name", "Present", "Total Classes", "Percentage"), out);
                new Alert(Alert.AlertType.INFORMATION, "Saved " + file.getName()).showAndWait();
            } catch (IOException ex) {
                showError("Could not save the file: " + ex.getMessage());
            }
        });

        HBox controls = new HBox(10, new Label("Class:"), reportClassCombo, classWise, subjectWise,
                reportSubjectCombo, generateButton, exportButton);
        controls.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(12, controls, reportTable);
        box.setPadding(new Insets(15));
        VBox.setVgrow(reportTable, Priority.ALWAYS);
        return new Tab("Attendance Reports", box);
    }

    /** @return the report rows, or null (after showing a message) if the selection is incomplete or fails. */
    private List<StudentAttendanceSummary> loadReport(ComboBox<SchoolClass> classBox, ComboBox<Subject> subjectBox, boolean subjectWise) {
        SchoolClass selectedClass = classBox.getValue();
        if (selectedClass == null) {
            new Alert(Alert.AlertType.WARNING, "Select a class first.").showAndWait();
            return null;
        }
        try {
            if (subjectWise) {
                Subject subject = subjectBox.getValue();
                if (subject == null) {
                    new Alert(Alert.AlertType.WARNING, "Select a subject for a subject-wise report.").showAndWait();
                    return null;
                }
                return attendanceService.getSubjectWiseReport(faculty.getId(), subject.getId(), selectedClass.getId());
            }
            return attendanceService.getClassWiseReport(faculty.getId(), selectedClass.getId());
        } catch (DataAccessException | AccessDeniedException e) {
            showError("Failed to generate report: " + e.getMessage());
            return null;
        }
    }

    private void showError(String message) {
        new Alert(Alert.AlertType.ERROR, message).showAndWait();
    }
}
