package com.attendance;

/**
 * Entry point for the runnable fat jar. JavaFX refuses to start when the jar's main class itself
 * extends Application, so this plain class just forwards to Main.
 */
public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) {
        Main.main(args);
    }
}
