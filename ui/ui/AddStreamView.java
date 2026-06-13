package ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/**
 * Add stream — resolve an infohash via the DHT, or share a file. Static layout for
 * this increment; the input wires to {@code TransferService.download} and the drop
 * zone to {@code share(Path)} in a later increment.
 */
final class AddStreamView {

	private final VBox root = new VBox(16);

	AddStreamView() {
		root.setAlignment(Pos.TOP_CENTER);
		VBox column = new VBox(18);
		column.setMaxWidth(720);
		VBox head = new VBox(4, Ui.h1("Add a stream"),
				Ui.subtitle("Resolve an infohash through the DHT, or share a file of your own"));
		head.setAlignment(Pos.CENTER);
		column.getChildren().addAll(
				head,
				pasteCard(),
				resolvedCard(),
				divider(),
				dropZone());
		root.getChildren().add(column);
	}

	Node getRoot() {
		return root;
	}

	private Region pasteCard() {
		VBox card = Ui.card();
		card.getChildren().add(Ui.cap("Paste an infohash"));

		TextField input = new TextField("4287ad37811db73f862115ba0960cc6c9d54569e");
		input.getStyleClass().add("mono");
		HBox.setHgrow(input, Priority.ALWAYS);
		input.setStyle("-fx-background-color: -color-surface-in; -fx-text-fill: -color-text;"
				+ " -fx-background-radius: 12; -fx-border-radius: 12; -fx-border-color: #2c2638;"
				+ " -fx-border-width: 1; -fx-padding: 13 15; -fx-font-family: 'JetBrains Mono';");
		Label resolve = new Label("Resolve →");
		resolve.getStyleClass().add("btn-primary");
		card.getChildren().add(Ui.row(10, Pos.CENTER_LEFT, input, resolve));

		card.getChildren().add(lookupViz());
		return card;
	}

	private Region lookupViz() {
		HBox viz = new HBox(10);
		viz.setAlignment(Pos.CENTER_LEFT);
		viz.setPadding(new Insets(13, 15, 13, 15));
		viz.setStyle("-fx-background-color: -color-surface-in; -fx-background-radius: 12;"
				+ " -fx-border-radius: 12; -fx-border-color: -color-border-soft; -fx-border-width: 1;");
		viz.getChildren().addAll(
				chip("YOU", Ui.ACCENT),
				dash(), Ui.mono("FIND_VALUE", Ui.ACCENT_SOFT, 11), dash(),
				hollow(), hollow(), hollow(),
				dash(), Ui.mono("VALUE", Ui.GREEN_TEXT, 11), dash(),
				Ui.mono("24 seeds", Ui.GREEN, 11));
		return viz;
	}

	private Region dash() {
		Region r = new Region();
		r.setPrefSize(22, 1);
		r.setMinSize(18, 1);
		r.setMaxWidth(40);
		HBox.setHgrow(r, Priority.ALWAYS);
		r.setStyle("-fx-border-color: #3a3148; -fx-border-width: 0 0 1 0;"
				+ " -fx-border-style: segments(3, 3);");
		return r;
	}

	private Region chip(String text, Color color) {
		Label l = Ui.mono(text, color, 11);
		l.setPadding(new Insets(4, 9, 4, 9));
		l.setBackground(Ui.fill(Color.web("#1d1430"), 6));
		l.setBorder(Ui.line(color.deriveColor(0, 1, 1, 0.4), 6));
		return l;
	}

	private Circle hollow() {
		Circle c = new Circle(7);
		c.setFill(Color.TRANSPARENT);
		c.setStroke(Ui.CYAN);
		c.setStrokeWidth(1.4);
		return c;
	}

	private Region resolvedCard() {
		HBox card = new HBox(16);
		card.setPadding(new Insets(18));
		card.setStyle("-fx-background-color: linear-gradient(to bottom right, #211433, #15111d 70%);"
				+ " -fx-background-radius: 16; -fx-border-radius: 16;"
				+ " -fx-border-color: -color-border-violet; -fx-border-width: 1;");

		StackPane thumb = new StackPane(Ui.mono("poster", Ui.TEXT_DIM, 10));
		thumb.setPrefSize(120, 150);
		thumb.setMinSize(120, 150);
		thumb.setStyle("-fx-background-color: linear-gradient(from 0px 0px to 9px 9px, repeat,"
				+ " #221830 0%, #221830 50%, #1b1428 50%, #1b1428 100%); -fx-background-radius: 12;");

		VBox info = new VBox(10);
		HBox.setHgrow(info, Priority.ALWAYS);
		info.getChildren().add(Ui.row(8, Pos.CENTER_LEFT, Ui.pillGreen("● RESOLVED"),
				Ui.mono("announced 8s ago", Ui.TEXT_DIM, 11)));
		info.getChildren().add(Ui.h1("Tears of Steel"));

		GridPane facts = new GridPane();
		facts.setHgap(28);
		facts.add(fact("PIECE SIZE", "256 KB"), 0, 0);
		facts.add(fact("PIECES", "6,812"), 1, 0);
		facts.add(fact("TOTAL", "1.74 GB"), 2, 0);
		info.getChildren().add(facts);

		Label stream = new Label("▶  Stream now");
		stream.getStyleClass().add("btn-primary");
		stream.setMaxWidth(Double.MAX_VALUE);
		stream.setAlignment(Pos.CENTER);
		HBox.setHgrow(stream, Priority.ALWAYS);
		Label download = new Label("⭳  Download");
		download.getStyleClass().add("btn-ghost");
		HBox actions = Ui.row(12, Pos.CENTER_LEFT, stream, download);
		info.getChildren().add(actions);

		card.getChildren().addAll(thumb, info);
		return card;
	}

	private Region fact(String cap, String value) {
		Label v = Ui.mono(value, Ui.TEXT_HI, 14);
		v.setStyle("-fx-font-size: 14px; -fx-font-weight: 800;");
		return new VBox(2, v, Ui.cap(cap));
	}

	private Region divider() {
		Label l = Ui.cap("Or share your own");
		HBox h = Ui.row(12, Pos.CENTER, line(), l, line());
		HBox.setHgrow(h.getChildren().get(0), Priority.ALWAYS);
		HBox.setHgrow(h.getChildren().get(2), Priority.ALWAYS);
		return h;
	}

	private Region line() {
		Region r = new Region();
		r.setPrefHeight(1);
		r.setMaxWidth(Double.MAX_VALUE);
		r.setBackground(Ui.fill(Ui.BORDER, 0));
		return r;
	}

	private Region dropZone() {
		VBox zone = new VBox(12);
		zone.setAlignment(Pos.CENTER);
		zone.setPadding(new Insets(34));
		zone.setStyle("-fx-background-color: -color-surface-in; -fx-background-radius: 16;"
				+ " -fx-border-radius: 16; -fx-border-color: #3a3148;"
				+ " -fx-border-width: 1.5; -fx-border-style: segments(8, 6) line-cap round;");

		StackPane tile = new StackPane(new Label("⭳"));
		tile.setPrefSize(52, 52);
		tile.setMaxSize(52, 52);
		tile.setStyle("-fx-background-color: -fill-accent-12; -fx-background-radius: 14;");
		((Label) tile.getChildren().get(0)).setTextFill(Ui.ACCENT);
		((Label) tile.getChildren().get(0)).setStyle("-fx-font-size: 22px;");

		Label headline = new Label("Drag a file here to share");
		headline.setTextFill(Ui.TEXT_HI);
		headline.setStyle("-fx-font-size: 15px; -fx-font-weight: 700;");

		Label explain = new Label("weStream hashes it with SHA-1, splits it into 256 KB pieces,"
				+ " and announces you as the first seed in the DHT.");
		explain.setTextFill(Ui.TEXT_SOFT);
		explain.setWrapText(true);
		explain.setMaxWidth(420);
		explain.setStyle("-fx-font-size: 12.5px; -fx-text-alignment: center; -fx-line-spacing: 3px;");
		zone.getChildren().addAll(tile, headline, explain);
		return zone;
	}
}
