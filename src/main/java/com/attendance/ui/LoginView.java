package com.attendance.ui;

import com.attendance.db.DatabaseConnection;
import com.attendance.exception.AuthenticationException;
import com.attendance.exception.DataAccessException;
import com.attendance.model.Role;
import com.attendance.service.AuthService;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

public class LoginView {

    private final SceneManager sceneManager;
    private final AuthService authService = new AuthService();
    private final VBox root;

    public LoginView(SceneManager sceneManager) {
        this.sceneManager = sceneManager;
        this.root = build();
    }

    public VBox getRoot() {
        return root;
    }

    private VBox build() {
        Label title = new Label("QR Attendance System");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");

        Label subtitle = new Label("CSE203 - OOP using Java Mini Project");
        subtitle.setStyle("-fx-text-fill: #666;");

        ComboBox<Role> roleCombo = new ComboBox<>();
        roleCombo.getItems().addAll(Role.STUDENT, Role.FACULTY, Role.ADMIN);
        roleCombo.getSelectionModel().selectFirst();
        roleCombo.setMaxWidth(Double.MAX_VALUE);
        roleCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Role r) {
                return switch (r) {
                    case STUDENT -> "Student";
                    case FACULTY -> "Faculty";
                    case ADMIN -> "Administrator";
                };
            }
            @Override
            public Role fromString(String s) { return null; }
        });

        TextField usernameField = new TextField();
        updateUsernamePrompt(usernameField, roleCombo.getValue());
        roleCombo.setOnAction(e -> updateUsernamePrompt(usernameField, roleCombo.getValue()));

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Password");

        Label statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: #c0392b;");
        statusLabel.setWrapText(true);

        Button loginButton = new Button("Login");
        loginButton.setDefaultButton(true);
        loginButton.setMaxWidth(Double.MAX_VALUE);
        loginButton.setOnAction(e -> attemptLogin(roleCombo.getValue(), usernameField.getText(), passwordField.getText(), statusLabel));

        VBox box = new VBox(12, title, subtitle, roleCombo, usernameField, passwordField, loginButton, statusLabel);
        box.setPadding(new Insets(30));
        box.setAlignment(Pos.TOP_CENTER);
        return box;
    }

    private void updateUsernamePrompt(TextField field, Role role) {
        field.setPromptText(switch (role) {
            case STUDENT -> "Register Number";
            case FACULTY -> "Faculty ID";
            case ADMIN -> "Admin Username";
        });
    }

    private void attemptLogin(Role role, String username, String password, Label statusLabel) {
        String dbProblem = DatabaseConnection.describeProblem();
        if (dbProblem != null) {
            statusLabel.setText(dbProblem);
            return;
        }

        try {
            AuthService.LoginResult result = authService.login(username, password, role);
            switch (result.user.getRole()) {
                case STUDENT -> sceneManager.showStudentDashboard(result.user, result.studentProfile);
                case FACULTY -> sceneManager.showFacultyDashboard(result.user, result.facultyProfile);
                case ADMIN -> sceneManager.showAdminDashboard(result.user);
            }
        } catch (AuthenticationException ex) {
            statusLabel.setText(ex.getMessage());
        } catch (DataAccessException ex) {
            new Alert(Alert.AlertType.ERROR, "Database error: " + ex.getMessage()).showAndWait();
        }
    }
}
