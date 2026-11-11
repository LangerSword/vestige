package dev.jvd.gui;

/**
 * Entry point of the desktop app.
 *
 * <p>Kept separate from {@link MainWindow} so the window class carries no
 * {@code main} method of its own and stays testable as a pure view. The
 * shade plugin's manifest points here.
 *
 * <p>The arguments are passed through to the window, so {@code --screenshot=FILE}
 * and {@code --demo} work from the packaged jar.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        int code = MainWindow.launch(args);
        if (code != 0) System.exit(code);
    }
}
