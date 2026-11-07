package com.attendance.ui;

import com.attendance.model.Faculty;
import com.attendance.model.Student;
import com.attendance.model.User;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;

/** Swaps the root node on one Stage so every screen reuses the same window; also owns the light/dark theme. */
public class SceneManager {

    private final Stage stage;
    private boolean dark;

    public SceneManager(Stage stage) {
        this.stage = stage;
    }

    public void show(Parent root, String title, double width, double height) {
        Scene scene = new Scene(root, width, height);
        applyTheme(scene);
        stage.setScene(scene);
        stage.setTitle(title);
        stage.setResizable(true);
        stage.show();
    }

    private void applyTheme(Scene scene) {
        scene.getStylesheets().setAll(
                getClass().getResource(dark ? "/css/dark.css" : "/css/light.css").toExternalForm());
    }

    /** A small button for each dashboard's top bar that flips between light and dark mode. */
    public Button themeButton() {
        Button button = new Button(dark ? "Light mode" : "Dark mode");
        button.setOnAction(e -> {
            dark = !dark;
            applyTheme(stage.getScene());
            button.setText(dark ? "Light mode" : "Dark mode");
        });
        return button;
    }

    public void showLogin() {
        show(new LoginView(this).getRoot(), "QR Attendance System - Login", 440, 400);
    }

    public void showStudentDashboard(User user, Student student) {
        show(new StudentDashboardView(this, user, student).getRoot(),
                "QR Attendance System - " + student.getFullName(), 920, 680);
    }

    public void showFacultyDashboard(User user, Faculty faculty) {
        show(new FacultyDashboardView(this, user, faculty).getRoot(),
                "QR Attendance System - " + faculty.getFullName(), 1040, 700);
    }

    public void showAdminDashboard(User user) {
        show(new AdminDashboardView(this, user).getRoot(),
                "QR Attendance System - Administrator", 1000, 680);
    }
}
