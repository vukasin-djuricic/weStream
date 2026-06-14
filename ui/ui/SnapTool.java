package ui;

import java.io.File;
import java.util.List;

import javax.imageio.ImageIO;

import app.NodeRuntime;
import core.kademlia.Contact;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

/**
 * Headless snapshot harness for the visual-compare loop. Boots a node, injects a
 * handful of mock routing-table contacts (so the swarm graph / table / DHT views
 * populate like the design mocks), builds the {@link AppShell} on the requested
 * screen at the reference viewport (924×540), and writes a PNG — all offscreen via
 * the Monocle/software pipeline so it works with no attached display.
 *
 * <p>Run:  {@code ./gradlew snapshot -PsnapArgs="<ScreenName> <outPath>"}.
 * ScreenName is a nav display name ("Swarm", "Now Playing", "Library",
 * "Add Stream", "DHT Inspector"). NOT shipped in the app — a dev tool.
 */
public final class SnapTool extends Application {

	@Override
	public void start(Stage stage) throws Exception {
		Font.loadFont(getClass().getResourceAsStream("/fonts/Manrope-Variable.ttf"), 13);
		Font.loadFont(getClass().getResourceAsStream("/fonts/JetBrainsMono-Variable.ttf"), 12);

		List<String> args = getParameters().getRaw();
		// First arg is a no-space alias (so it survives whitespace arg-splitting);
		// map it to the nav display name.
		String alias = args.isEmpty() ? "Swarm" : args.get(0);
		String screen = switch (alias.toLowerCase()) {
			case "player", "nowplaying" -> "Now Playing";
			case "library" -> "Library";
			case "addstream", "add" -> "Add Stream";
			case "dht", "dhtinspector" -> "DHT Inspector";
			default -> "Swarm";
		};
		String outPath = args.size() > 1 ? args.get(1) : "snap.png";

		NodeRuntime runtime = new NodeRuntime("127.0.0.1", 1100, true, "127.0.0.1", 1100);
		// Mock peers so the hero views are populated (the design screens are mocks).
		int[] ports = {1201, 1202, 1203, 1204, 1205, 1206, 1207, 1208, 1209, 1210, 1211, 1212};
		for (int p : ports) {
			runtime.kademlia().routingTable().update(new Contact("127.0.0.1", p));
		}

		AppShell shell = new AppShell(runtime, stage);
		shell.showScreen(screen);

		// Render at the reference PNG's exact size so the framing (which content is
		// above the fold, where the YOU node clips) matches 1:1.
		Scene scene = new Scene(shell.getRoot(), 924, 540);
		scene.getStylesheets().add(getClass().getResource("/theme.css").toExternalForm());
		stage.initStyle(StageStyle.UNDECORATED);
		stage.setScene(scene);
		stage.show();

		// Let CSS + layout + the first animation pulses settle, then snapshot once.
		PauseTransition pause = new PauseTransition(Duration.millis(900));
		pause.setOnFinished(e -> {
			WritableImage img = scene.snapshot(null);
			try {
				ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", new File(outPath));
				System.out.println("[SnapTool] wrote " + outPath);
			} catch (Exception ex) {
				ex.printStackTrace();
			}
			runtime.close();
			Platform.exit();
		});
		pause.play();
	}

	public static void main(String[] args) {
		launch(args);
	}
}
