package ui;

import java.util.List;

import app.NodeRuntime;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * Phase-5 entry point: one JavaFX window for one weStream node. This is the GUI
 * front-end over the same {@link NodeRuntime} the headless {@code ServentMain}
 * uses, so a node = a user's client (the localhost multi-process run simulates N
 * separate machines → N windows). The engine ({@code core.kademlia} /
 * {@code core.transfer}) stays pure-JDK; this layer only reads from it and drives
 * the player seam.
 *
 * <p>Run:  {@code ./gradlew run --args="<port> [seed]"}.
 * Default port 1100 = the seed; any other port bootstraps to 127.0.0.1:1100.
 */
public final class WeStreamApp extends Application {

	private static final String SEED_HOST = "127.0.0.1";
	private static final int SEED_PORT = 1100;

	private NodeRuntime runtime;

	@Override
	public void init() throws Exception {
		List<String> args = getParameters().getRaw();
		int port = args.isEmpty() ? SEED_PORT : Integer.parseInt(args.get(0));
		boolean seed = port == SEED_PORT || args.contains("seed");
		runtime = new NodeRuntime("127.0.0.1", port, seed, SEED_HOST, SEED_PORT);
	}

	@Override
	public void start(Stage stage) {
		loadFonts();

		AppShell shell = new AppShell(runtime, stage);
		Scene scene = new Scene(shell.getRoot(), 1340, 860);
		scene.getStylesheets().add(getClass().getResource("/theme.css").toExternalForm());

		stage.initStyle(StageStyle.UNDECORATED);
		stage.setScene(scene);
		stage.setTitle("weStream · node " + runtime.port());
		stage.setMinWidth(1100);
		stage.setMinHeight(720);
		stage.show();

		// Join the network only once the window is up, so bootstrap progress can be
		// reflected live (off the FX thread — see NodeRuntime#joinNetwork).
		runtime.joinNetwork();
	}

	@Override
	public void stop() {
		if (runtime != null) {
			runtime.close();
		}
		Platform.exit();
	}

	/** Bundle the two SIL-OFL families before any scene is built (README §Fonts). */
	private void loadFonts() {
		Font.loadFont(getClass().getResourceAsStream("/fonts/Manrope-Variable.ttf"), 13);
		Font.loadFont(getClass().getResourceAsStream("/fonts/JetBrainsMono-Variable.ttf"), 12);
	}

	public static void main(String[] args) {
		launch(args);
	}
}
