package ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/**
 * Library — your seeds + downloads and continue-watching. Static layout for this
 * increment; the cards will bind to {@code TransferService} share/download state
 * once it exposes a live listing.
 */
final class LibraryView {

	private final VBox root = new VBox(14);

	LibraryView() {
		root.getChildren().addAll(header(), continueWatching(),
				section("Your shares", "● seeding", Ui.GREEN, true),
				section("Downloads", null, null, false));
	}

	Node getRoot() {
		return root;
	}

	private Region header() {
		TextField search = new TextField();
		search.setPromptText("Search library…");
		search.setPrefWidth(240);
		search.setStyle("-fx-background-color: -color-surface-in; -fx-text-fill: -color-text;"
				+ " -fx-prompt-text-fill: -color-text-dim; -fx-background-radius: 10; -fx-border-radius: 10;"
				+ " -fx-border-color: -color-border; -fx-border-width: 1; -fx-padding: 8 12;");
		Label share = new Label("+ Share a file");
		share.getStyleClass().add("btn-primary");
		return Ui.row(12, Pos.CENTER_LEFT,
				new VBox(3, Ui.h1("Library"), Ui.subtitle("Files you seed and stream, all from the swarm")),
				Ui.spacer(), search, share);
	}

	private Region continueWatching() {
		HBox content = new HBox(20);
		content.setPadding(new Insets(18));
		content.setAlignment(Pos.CENTER_LEFT);

		StackPane thumb = poster(230, 150);
		Circle glow = new Circle(30, Color.web("#c64ff0", 0.22));
		Circle play = new Circle(26, Color.web("#c64ff0"));
		play.setEffect(new javafx.scene.effect.DropShadow(26, Color.web("#c64ff0", 0.6)));
		Label tri = new Label("▶");
		tri.setTextFill(Color.WHITE);
		tri.setStyle("-fx-font-size: 18px;");
		thumb.getChildren().add(new StackPane(glow, play, tri));

		Label title = new Label("Tears of Steel");
		title.setTextFill(Ui.TEXT_HI);
		title.setStyle("-fx-font-size: 24px; -fx-font-weight: 800;");
		VBox info = new VBox(8,
				Ui.cap("Continue watching", Ui.ACCENT_SOFT),
				title,
				Ui.mono("2160p · HEVC · 42:13 watched of 1:32:55", Ui.TEXT_LO, 12),
				progressLine(0.45));
		info.setAlignment(Pos.CENTER_LEFT);
		HBox.setHgrow(info, Priority.ALWAYS);
		content.getChildren().addAll(thumb, info);

		// ambient glow blob behind, top-right
		Circle blob = new Circle(150, Color.web("#c64ff0", 0.18));
		blob.setEffect(new javafx.scene.effect.GaussianBlur(70));
		StackPane.setAlignment(blob, Pos.TOP_RIGHT);
		StackPane.setMargin(blob, new Insets(-40, -30, 0, 0));

		StackPane card = new StackPane(blob, content);
		card.setStyle("-fx-background-color: linear-gradient(to right, #211433, #15111d 60%);"
				+ " -fx-background-radius: 18; -fx-border-radius: 18;"
				+ " -fx-border-color: -color-border-violet; -fx-border-width: 1;");
		card.setClip(roundedClip(card, 18));
		return card;
	}

	/** A rounded rectangle that tracks the region's size, to clip the glow blob. */
	private javafx.scene.shape.Rectangle roundedClip(Region r, double radius) {
		javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
		clip.setArcWidth(radius * 2);
		clip.setArcHeight(radius * 2);
		clip.widthProperty().bind(r.widthProperty());
		clip.heightProperty().bind(r.heightProperty());
		return clip;
	}

	private Region section(String title, String pillText, Color pillColor, boolean shares) {
		VBox box = new VBox(12);
		HBox head = Ui.row(10, Pos.CENTER_LEFT, Ui.sectionTitle(title));
		if (pillText != null) {
			head.getChildren().add(Ui.pillGreen(pillText));
		}
		FlowPane grid = new FlowPane(14, 14);
		String[] titles = shares
				? new String[] {"Sintel", "Tears of Steel", "Cosmos Laundromat"}
				: new String[] {"Elephants Dream", "Caminandes", "Spring"};
		String[] status = shares
				? new String[] {"seeding", "seeding", "seeding"}
				: new String[] {"67% ↓", "✓ Complete", "23% ↓"};
		for (int i = 0; i < titles.length; i++) {
			grid.getChildren().add(mediaCard(titles[i], status[i], shares));
		}
		box.getChildren().addAll(head, grid);
		return box;
	}

	private Region mediaCard(String title, String status, boolean seeding) {
		VBox card = new VBox(0);
		card.setPrefWidth(230);
		card.getStyleClass().add("ws-card");

		StackPane poster = poster(230, 130);
		Label res = Ui.mono("1080p", Ui.TEXT, 10);
		res.setPadding(new Insets(2, 7, 2, 7));
		res.setBackground(Ui.fill(Color.web("#100d17", 0.75), 5));
		StackPane.setAlignment(res, Pos.TOP_LEFT);
		StackPane.setMargin(res, new Insets(8));
		Label pill = seeding ? Ui.pillGreen("seeding")
				: status.startsWith("✓") ? Ui.pillAccent("complete") : Ui.pillAccent("downloading");
		StackPane.setAlignment(pill, Pos.TOP_RIGHT);
		StackPane.setMargin(pill, new Insets(8));
		poster.getChildren().addAll(res, pill);

		VBox body = new VBox(5,
				Ui.body(title, Ui.TEXT_HI),
				Ui.row(8, Pos.CENTER_LEFT, Ui.mono("412 MB", Ui.TEXT_LO, 11),
						Ui.spacer(), Ui.mono(status, seeding ? Ui.GREEN : Ui.ACCENT, 11)));
		body.setPadding(new Insets(12));
		card.getChildren().addAll(poster, body);
		return card;
	}

	private StackPane poster(double w, double h) {
		StackPane p = new StackPane();
		p.setPrefSize(w, h);
		p.setMinSize(w, h);
		// subtle diagonal-stripe placeholder, like the reference posters
		p.setStyle("-fx-background-color: linear-gradient(from 0px 0px to 9px 9px, repeat,"
				+ " #221830 0%, #221830 50%, #1b1428 50%, #1b1428 100%);"
				+ " -fx-background-radius: 12;");
		Label tag = Ui.mono("poster", Ui.TEXT_DIM, 10);
		p.getChildren().add(tag);
		return p;
	}

	private Region progressLine(double frac) {
		StackPane track = new StackPane();
		track.setPrefHeight(4);
		track.setMaxWidth(320);
		track.setBackground(Ui.fill(Ui.BORDER, 2));
		Region fill = new Region();
		fill.prefWidthProperty().bind(track.widthProperty().multiply(frac));
		fill.setMaxWidth(Double.MAX_VALUE);
		fill.setStyle("-fx-background-color: linear-gradient(to right, #9b3ec9, #c64ff0); -fx-background-radius: 2;");
		StackPane.setAlignment(fill, Pos.CENTER_LEFT);
		track.getChildren().add(fill);
		return track;
	}
}
