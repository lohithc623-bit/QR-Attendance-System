package com.attendance.ui;

import com.attendance.exception.DataAccessException;
import com.attendance.model.AttendanceRecord;
import com.attendance.model.AttendanceStatus;
import com.attendance.model.Student;
import com.attendance.model.SubjectAttendanceSummary;
import com.attendance.model.User;
import com.attendance.service.AttendanceService;
import com.attendance.util.QRCodeUtil;
import com.attendance.util.QRTokenService;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class StudentDashboardView {

    private final SceneManager sceneManager;
    private final User user;
    private final Student student;
    private final AttendanceService attendanceService = new AttendanceService();
    private Timeline qrTimeline;

    public StudentDashboardView(SceneManager sceneManager, User user, Student student) {
        this.sceneManager = sceneManager;
        this.user = user;
        this.student = student;
    }

    public BorderPane getRoot() {
        List<SubjectAttendanceSummary> summaries = loadSummaries();   // one query, shared by ring + table
        BorderPane root = new BorderPane();
        root.setTop(buildTopBar());
        root.setLeft(buildProfileAndQrPane(summaries));
        root.setCenter(buildTabs(summaries));
        return root;
    }

    private List<SubjectAttendanceSummary> loadSummaries() {
        try {
            return attendanceService.getSubjectWiseSummary(student.getId());
        } catch (DataAccessException e) {
            showError("Failed to load attendance: " + e.getMessage());
            return List.of();
        }
    }

    private HBox buildTopBar() {
        Label header = new Label("Student Dashboard");
        header.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Button logout = new Button("Logout");
        logout.setOnAction(e -> {
            if (qrTimeline != null) qrTimeline.stop();
            sceneManager.showLogin();
        });
        HBox bar = new HBox(12, header, sceneManager.themeButton(), logout);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10));
        HBox.setHgrow(header, Priority.ALWAYS);
        header.setMaxWidth(Double.MAX_VALUE);
        return bar;
    }

    private VBox buildProfileAndQrPane(List<SubjectAttendanceSummary> summaries) {
        GridPane profile = new GridPane();
        profile.setHgap(8);
        profile.setVgap(6);
        addRow(profile, 0, "Name:", student.getFullName());
        addRow(profile, 1, "Register No.:", student.getRegisterNumber());
        addRow(profile, 2, "Department:", student.getDepartment());
        addRow(profile, 3, "Semester:", String.valueOf(student.getSemester()));
        addRow(profile, 4, "Section:", student.getSection());

        double overall = AttendanceService.overallPercentage(summaries);
        boolean anyClasses = summaries.stream().anyMatch(s -> s.getTotalClasses() > 0);
        boolean low = anyClasses && overall < AttendanceStatus.WARNING_THRESHOLD;

        Label warning = new Label("Below the 75% attendance requirement");
        warning.setStyle("-fx-text-fill: #c0392b; -fx-font-weight: bold;");
        warning.setWrapText(true);
        warning.setMaxWidth(220);
        warning.setVisible(low);
        warning.setManaged(low);

        // Rotating QR: regenerated whenever the time window changes; the bar shows the time left.
        ImageView qrImageView = new ImageView();
        ProgressBar countdown = new ProgressBar(1);
        countdown.setPrefWidth(200);
        Label qrLabel = new Label("Live QR - show this to faculty. It refreshes every "
                + QRTokenService.stepSeconds() + " s, so screenshots stop working.");
        qrLabel.setWrapText(true);
        qrLabel.setMaxWidth(220);
        String[] lastPayload = {""};
        Runnable tick = () -> {
            Instant now = Instant.now();
            String payload = QRTokenService.generate(student.getId(), student.getQrToken(), now);
            if (!payload.equals(lastPayload[0])) {
                lastPayload[0] = payload;
                qrImageView.setImage(QRCodeUtil.generateQRImage(payload, 200));
            }
            countdown.setProgress(QRTokenService.secondsUntilRotation(now) / (double) QRTokenService.stepSeconds());
        };
        tick.run();
        qrTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> tick.run()));
        qrTimeline.setCycleCount(Animation.INDEFINITE);
        qrTimeline.play();

        VBox box = new VBox(14, profile, buildRing(overall, low), warning, qrImageView, countdown, qrLabel);
        box.setPadding(new Insets(15));
        box.setPrefWidth(260);
        box.setAlignment(Pos.TOP_CENTER);
        return box;
    }

    /** Circular percentage gauge: green at/above 75%, red below. */
    private StackPane buildRing(double percent, boolean low) {
        Color color = low ? Color.web("#c0392b") : Color.web("#1e8449");
        Circle track = new Circle(55, 55, 46);
        track.setFill(null);
        track.setStroke(Color.web("#c8c8c8"));
        track.setStrokeWidth(10);
        Arc arc = new Arc(55, 55, 46, 46, 90, -360 * Math.min(100, percent) / 100.0);
        arc.setType(ArcType.OPEN);
        arc.setFill(null);
        arc.setStroke(color);
        arc.setStrokeWidth(10);
        Pane canvas = new Pane(track, arc);
        canvas.setPrefSize(110, 110);
        canvas.setMaxSize(110, 110);

        Label pct = new Label(String.format("%.0f%%", percent));
        pct.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: " + (low ? "#c0392b" : "#1e8449") + ";");
        Label caption = new Label("Overall");
        VBox center = new VBox(pct, caption);
        center.setAlignment(Pos.CENTER);
        StackPane ring = new StackPane(canvas, center);
        ring.setMaxSize(110, 110);
        return ring;
    }

    private void addRow(GridPane grid, int row, String label, String value) {
        Label l = new Label(label);
        l.setStyle("-fx-font-weight: bold;");
        grid.add(l, 0, row);
        grid.add(new Label(value), 1, row);
    }

    private TabPane buildTabs(List<SubjectAttendanceSummary> summaries) {
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(buildSubjectWiseTab(summaries), buildHistoryTab());
        return tabs;
    }

    private Tab buildSubjectWiseTab(List<SubjectAttendanceSummary> summaries) {
        TableView<SubjectAttendanceSummary> table = new TableView<>();

        TableColumn<SubjectAttendanceSummary, String> codeCol = new TableColumn<>("Code");
        codeCol.setCellValueFactory(new PropertyValueFactory<>("subjectCode"));
        TableColumn<SubjectAttendanceSummary, String> nameCol = new TableColumn<>("Subject");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("subjectName"));
        nameCol.setPrefWidth(220);
        TableColumn<SubjectAttendanceSummary, Integer> presentCol = new TableColumn<>("Present");
        presentCol.setCellValueFactory(new PropertyValueFactory<>("presentCount"));
        TableColumn<SubjectAttendanceSummary, Integer> totalCol = new TableColumn<>("Total Classes");
        totalCol.setCellValueFactory(new PropertyValueFactory<>("totalClasses"));
        TableColumn<SubjectAttendanceSummary, String> pctCol = new TableColumn<>("Percentage");
        pctCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                String.format("%.1f%%", data.getValue().getPercentage())));
        TableColumn<SubjectAttendanceSummary, String> alertCol = new TableColumn<>("Alert");
        alertCol.setPrefWidth(260);
        alertCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                data.getValue().isBelowThreshold()
                        ? "Below 75% - attend the next " + data.getValue().classesNeededToRecover() + " class(es) in a row"
                        : ""));
        alertCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                setStyle(!empty && item != null && !item.isEmpty() ? "-fx-text-fill: #c0392b; -fx-font-weight: bold;" : "");
            }
        });

        table.getColumns().addAll(List.of(codeCol, nameCol, presentCol, totalCol, pctCol, alertCol));
        table.setPlaceholder(new Label("No subjects or attendance yet."));
        table.setItems(FXCollections.observableArrayList(summaries));

        VBox box = new VBox(10, table);
        box.setPadding(new Insets(12));
        VBox.setVgrow(table, Priority.ALWAYS);
        return new Tab("Subject-wise Attendance", box);
    }

    private Tab buildHistoryTab() {
        TableView<AttendanceRecord> table = new TableView<>();
        TableColumn<AttendanceRecord, String> dateCol = new TableColumn<>("Date");
        dateCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                data.getValue().getAttendanceDate().format(DateTimeFormatter.ofPattern("dd-MM-yyyy"))));
        TableColumn<AttendanceRecord, Integer> periodCol = new TableColumn<>("Period");
        periodCol.setCellValueFactory(new PropertyValueFactory<>("period"));
        TableColumn<AttendanceRecord, String> subjectCol = new TableColumn<>("Subject");
        subjectCol.setCellValueFactory(new PropertyValueFactory<>("subjectName"));
        subjectCol.setPrefWidth(220);
        TableColumn<AttendanceRecord, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(new PropertyValueFactory<>("status"));
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                setStyle(empty || item == null ? "" : ("ABSENT".equals(item)
                        ? "-fx-text-fill: #c0392b; -fx-font-weight: bold;" : "-fx-text-fill: #1e8449; -fx-font-weight: bold;"));
            }
        });
        TableColumn<AttendanceRecord, String> timeCol = new TableColumn<>("Marked At");
        timeCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                data.getValue().getMarkedAt().format(DateTimeFormatter.ofPattern("HH:mm:ss"))));

        table.getColumns().addAll(List.of(dateCol, periodCol, subjectCol, statusCol, timeCol));
        table.setPlaceholder(new Label("No attendance marked yet."));

        List<AttendanceRecord> fullHistory;
        try {
            fullHistory = attendanceService.getAttendanceHistory(student.getId());
            table.setItems(FXCollections.observableArrayList(fullHistory));
        } catch (DataAccessException e) {
            showError("Failed to load attendance history: " + e.getMessage());
            fullHistory = List.of();
        }

        DatePicker datePicker = new DatePicker(LocalDate.now());
        Button filterButton = new Button("Show Selected Day Only");
        Button showAllButton = new Button("Show Full History");
        List<AttendanceRecord> finalFullHistory = fullHistory;
        filterButton.setOnAction(e -> {
            try {
                table.setItems(FXCollections.observableArrayList(
                        attendanceService.getAttendanceForDate(student.getId(), datePicker.getValue())));
            } catch (DataAccessException ex) {
                showError("Failed to load daily attendance: " + ex.getMessage());
            }
        });
        showAllButton.setOnAction(e -> table.setItems(FXCollections.observableArrayList(finalFullHistory)));

        HBox filterBar = new HBox(10, new Label("Daily attendance for:"), datePicker, filterButton, showAllButton);
        filterBar.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(10, filterBar, table);
        box.setPadding(new Insets(12));
        VBox.setVgrow(table, Priority.ALWAYS);
        return new Tab("Attendance History", box);
    }

    private void showError(String message) {
        new Alert(Alert.AlertType.ERROR, message).showAndWait();
    }
}
