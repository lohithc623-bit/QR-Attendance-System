package com.attendance;

import com.attendance.ui.SceneManager;
import javafx.application.Application;
import javafx.stage.Stage;

public class Main extends Application {

    @Override
    public void start(Stage primaryStage) {
        SceneManager sceneManager = new SceneManager(primaryStage);
        sceneManager.showLogin();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
