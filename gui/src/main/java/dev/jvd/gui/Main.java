package dev.jvd.gui;

/**
 * Entry point of the desktop app.
 *
 * <p>Kept separate from {@link MainWindow} so the window class carries no
 * {@code main} method of its own and stays testable as a pure view. The
 * shade plugin's manifest points here.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        MainWindow.main(args);
    }
}
