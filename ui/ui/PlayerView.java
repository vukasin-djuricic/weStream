package ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/**
 * Now Playing — watch a stream while seeing live swarm activity. Static layout for
 * this increment: the video surface is the seam where vlcj/libVLC will render
 * (next increment), and the sliding-window strip is the seam to
 * {@code SlidingWindowPicker.setPlayhead}. Mock data until those land.
 */
final class PlayerView {

	private final HBox root = new HBox(16);

	PlayerView() {
		root.getChildren().addAll(videoColumn(), rightRail());
		HBox.setHgrow(root.getChildren().get(0), Priority.ALWAYS);
	}

	Node getRoot() {
		return root;
	}

	private Region videoColumn() {
		VBox col = new VBox(14);

		Label title = Ui.h1("Tears of Steel");
		HBox titleRow = Ui.row(10, Pos.CENTER_LEFT, title, Ui.pillGreen("● STREAMING"),
				Ui.spacer(), ghost("Cast"), ghost("Share"));
		Label meta = Ui.mono("2160p · HEVC | 1.74 GB | infohash 9f2a…c081", Ui.TEXT_LO, 12);

		col.getChildren().addAll(titleRow, meta, videoSurface(), scrubber(), slidingWindow());
		return col;
	}

	private Region videoSurface() {
		StackPane surface = new StackPane();
		surface.setMinHeight(420);
		surface.setStyle("-fx-background-color: linear-gradient(from 0px 0px to 14px 14px, repeat,"
				+ " #16121e 0%, #16121e 50%, #14101b 50%, #14101b 100%);"
				+ " -fx-background-radius: 16; -fx-border-radius: 16;"
				+ " -fx-border-color: -color-border; -fx-border-width: 1;");

		Circle glow = new Circle(34);
		glow.setFill(Color.web("#c64ff0", 0.18));
		Circle play = new Circle(32, Color.web("#c64ff0", 0.14));
		play.setStroke(Color.web("#c64ff0", 0.5));
		play.setStrokeWidth(1);
		play.setEffect(new javafx.scene.effect.DropShadow(28, Color.web("#c64ff0", 0.55)));
		Label tri = new Label("▶");
		tri.setTextFill(Color.WHITE);
		tri.setStyle("-fx-font-size: 22px;");
		StackPane button = new StackPane(glow, play, tri);

		Label buffer = Ui.mono("buffer 6.2s ahead", Ui.CYAN, 11);
		buffer.setPadding(new Insets(4, 9, 4, 9));
		buffer.setBackground(Ui.fill(Color.web("#100d17", 0.7), 999));
		StackPane.setAlignment(buffer, Pos.TOP_LEFT);
		StackPane.setMargin(buffer, new Insets(14));

		Label vlc = Ui.mono("vlcj/libVLC renders here (next increment)", Ui.TEXT_DIM, 10.5);
		StackPane.setAlignment(vlc, Pos.BOTTOM_CENTER);
		StackPane.setMargin(vlc, new Insets(0, 0, 14, 0));

		surface.getChildren().addAll(button, buffer, vlc);
		return surface;
	}

	private Region scrubber() {
		StackPane track = new StackPane();
		track.setPrefHeight(6);
		track.setMaxWidth(Double.MAX_VALUE);
		track.setBackground(Ui.fill(Ui.BORDER, 3));

		Region buffered = new Region();
		buffered.setMaxWidth(Double.MAX_VALUE);
		buffered.prefWidthProperty().bind(track.widthProperty().multiply(0.62));
		buffered.setBackground(Ui.fill(Color.web("#6cc8e8", 0.32), 3));
		StackPane.setAlignment(buffered, Pos.CENTER_LEFT);

		Region played = new Region();
		played.setMaxWidth(Double.MAX_VALUE);
		played.prefWidthProperty().bind(track.widthProperty().multiply(0.45));
		played.setStyle("-fx-background-color: linear-gradient(to right, #9b3ec9, #c64ff0); -fx-background-radius: 3;");
		StackPane.setAlignment(played, Pos.CENTER_LEFT);

		track.getChildren().addAll(buffered, played);

		HBox times = Ui.row(8, Pos.CENTER_LEFT,
				Ui.mono("00:42:13", Ui.TEXT_LO, 11), Ui.spacer(), Ui.mono("−00:50:42", Ui.TEXT_LO, 11));
		return new VBox(8, track, times);
	}

	private Region slidingWindow() {
		VBox card = Ui.card();
		HBox head = Ui.row(10, Pos.CENTER_LEFT, Ui.h2("Sliding window"),
				Ui.spacer(), Ui.mono("SlidingWindowPicker · W=32", Ui.TEXT_LO, 11));

		HBox cells = new HBox(3);
		// have = accent, in-flight = cyan, missing = surface
		for (int i = 0; i < 44; i++) {
			Region cell = new Region();
			cell.setPrefSize(12, 26);
			Color c = i < 18 ? Ui.ACCENT : i < 26 ? Ui.CYAN : Color.web("#2a2435");
			cell.setBackground(Ui.fill(c, 2));
			HBox.setHgrow(cell, Priority.ALWAYS);
			cell.setMaxWidth(Double.MAX_VALUE);
			cells.getChildren().add(cell);
		}

		HBox legend = Ui.row(16, Pos.CENTER_LEFT,
				legendDot(Ui.ACCENT, "have"), legendDot(Ui.CYAN, "in-flight"),
				legendDot(Color.web("#2a2435"), "missing"), Ui.spacer(),
				Ui.mono("piece 612 · playhead   requesting 8 · window end 644", Ui.TEXT_DIM, 10.5));

		card.getChildren().addAll(head, cells, legend);
		return card;
	}

	private Region legendDot(Color c, String text) {
		Region dot = new Region();
		dot.setPrefSize(10, 10);
		dot.setBackground(Ui.fill(c, 2));
		return Ui.row(6, Pos.CENTER_LEFT, dot, Ui.mono(text, Ui.TEXT_LO, 11));
	}

	private Region rightRail() {
		VBox rail = new VBox(14);
		rail.setPrefWidth(322);
		rail.setMinWidth(322);
		rail.setPadding(new Insets(20, 18, 20, 18));
		rail.setStyle("-fx-background-color: #131019;"
				+ " -fx-border-color: transparent transparent transparent #221d2c; -fx-border-width: 0 0 0 1;");
		HBox head = Ui.row(8, Pos.CENTER_LEFT, Ui.h2("Live swarm"), Ui.spacer(), Ui.pillAccent("24 peers"));

		HBox tiles = Ui.row(12, Pos.CENTER_LEFT, statTile("DOWNLOAD", "11.4 MB/s", Ui.CYAN),
				statTile("UPLOAD", "3.2 MB/s", Ui.PINK));
		HBox.setHgrow(tiles.getChildren().get(0), Priority.ALWAYS);
		HBox.setHgrow(tiles.getChildren().get(1), Priority.ALWAYS);

		VBox peers = new VBox(12);
		String[][] data = {
				{"A3", "a3f91c", "AMS · 13ms", "3.1", "0.4", "0.82"},
				{"7C", "7c2104", "FRA · 9ms", "2.4", "1.1", "0.71"},
				{"E1", "e10b8d", "NYC · 88ms", "1.9", "0.6", "0.64"},
				{"B5", "b5d330", "LON · 21ms", "1.6", "0.2", "0.55"},
				{"2F", "2f9a17", "SGP · 167ms", "1.2", "0.5", "0.40"},
				{"9D", "9d4e62", "PAR · 18ms", "0.9", "0.8", "0.33"},
		};
		Color[] badges = {Ui.ACCENT, Ui.CYAN, Ui.PINK, Ui.GREEN, Ui.AMBER, Ui.ACCENT_SOFT};
		for (int i = 0; i < data.length; i++) {
			peers.getChildren().add(peerRow(data[i], badges[i]));
		}
		rail.getChildren().addAll(head, tiles, peers, Ui.vspacer(),
				ghost("View full swarm map →"));
		return rail;
	}

	private Region statTile(String cap, String value, Color fill) {
		VBox v = new VBox(4, statValue(value, fill), Ui.cap(cap));
		v.setPadding(new Insets(12));
		v.setMaxWidth(Double.MAX_VALUE);
		v.setBackground(Ui.fill(Ui.SURFACE, 10));
		v.setBorder(Ui.line(Ui.BORDER, 10));
		return v;
	}

	private Label statValue(String text, Color fill) {
		Label l = new Label(text);
		l.setTextFill(fill);
		l.getStyleClass().add("mono");
		l.setStyle("-fx-font-size: 16px; -fx-font-weight: 800;");
		return l;
	}

	private Region peerRow(String[] d, Color badgeColor) {
		Label code = Ui.mono(d[0], Color.WHITE, 11);
		code.setStyle("-fx-font-size: 11px; -fx-font-weight: 800;");
		StackPane badge = new StackPane(code);
		badge.setPrefSize(30, 30);
		badge.setMinSize(30, 30);
		badge.setBackground(Ui.fill(badgeColor.deriveColor(0, 1, 1, 0.22), 8));
		badge.setBorder(Ui.line(badgeColor.deriveColor(0, 1, 1, 0.55), 8));

		Label id = Ui.mono(d[1], Ui.TEXT, 12);
		Label loc = Ui.mono(d[2], Ui.TEXT_DIM, 10.5);
		Region haveBar = miniBar(Double.parseDouble(d[5]), badgeColor);
		VBox idCol = new VBox(3, Ui.row(8, Pos.CENTER_LEFT, id, loc), haveBar);
		HBox.setHgrow(idCol, Priority.ALWAYS);

		VBox speeds = new VBox(2,
				Ui.mono("↓" + d[3], Ui.CYAN, 11),
				Ui.mono("↑" + d[4], Ui.PINK, 11));
		speeds.setAlignment(Pos.CENTER_RIGHT);
		HBox card = Ui.row(10, Pos.CENTER_LEFT, badge, idCol, speeds);
		card.setPadding(new Insets(10, 11, 10, 11));
		card.setBackground(Ui.fill(Ui.SURFACE, 12));
		card.setBorder(Ui.line(Ui.BORDER, 12));
		return card;
	}

	private Region miniBar(double frac, Color color) {
		StackPane track = new StackPane();
		track.setPrefHeight(4);
		track.setMaxWidth(Double.MAX_VALUE);
		track.setBackground(Ui.fill(Ui.BORDER, 2));
		Region fill = new Region();
		fill.prefWidthProperty().bind(track.widthProperty().multiply(frac));
		fill.setMaxWidth(Double.MAX_VALUE);
		fill.setStyle("-fx-background-color: linear-gradient(to right, #9b3ec9, #c64ff0); -fx-background-radius: 2;");
		StackPane.setAlignment(fill, Pos.CENTER_LEFT);
		track.getChildren().add(fill);
		return track;
	}

	private Label ghost(String text) {
		Label l = new Label(text);
		l.getStyleClass().add("btn-ghost");
		return l;
	}
}
