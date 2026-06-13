package ui;

import java.util.List;

import app.NodeRuntime;
import core.kademlia.NodeId;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;

/**
 * DHT inspector — the engine/nerd view, wired to <em>live</em> Kademlia state via
 * the read-seam ({@code kademlia.self()}, {@code routingTable().bucketSizes()},
 * {@code storedKeys()}). A 1s {@link Timeline} on the FX thread refreshes it; the
 * reads hit thread-safe snapshots, never the receive loop.
 */
final class DhtView {

	private final NodeRuntime runtime;
	private final VBox root = new VBox(14);

	private final Label idLabel = Ui.mono("", Ui.TEXT, 15);
	private final Label endpointLabel = Ui.mono("", Ui.TEXT_LO, 12);
	private final Label bucketsFig = figure(Ui.TEXT_HI);
	private final Label contactsFig = figure(Ui.ACCENT_SOFT);
	private final Label storedFig = figure(Ui.GREEN);
	private final VBox bucketRows = new VBox(7);
	private final VBox storedRows = new VBox(8);

	DhtView(NodeRuntime runtime) {
		this.runtime = runtime;
		root.setFillWidth(true);

		Region cols = twoColumn();
		VBox.setVgrow(cols, Priority.ALWAYS); // two-column section absorbs leftover height
		root.getChildren().addAll(header(), identityCard(), cols);

		refresh();
		Timeline ticker = new Timeline(new KeyFrame(Duration.seconds(1), e -> refresh()));
		ticker.setCycleCount(Animation.INDEFINITE);
		ticker.play();
	}

	Node getRoot() {
		return root;
	}

	private Region header() {
		VBox h = new VBox(3, Ui.h1("DHT inspector"),
				Ui.mono("Kademlia routing internals · 160-bit ID space · XOR metric · k = 20", Ui.TEXT_SOFT, 12.5));
		return h;
	}

	// --------------------------------------------------------------- identity

	private Region identityCard() {
		VBox left = new VBox(8);
		Label cap = Ui.cap("This node · NodeId (SHA-1)");
		left.getChildren().addAll(cap, idLabel, endpointLabel);

		HBox figs = new HBox(28,
				figureBlock("BUCKETS", bucketsFig),
				figureBlock("CONTACTS", contactsFig),
				figureBlock("STORED KEYS", storedFig));
		figs.setAlignment(Pos.CENTER_RIGHT);

		HBox card = new HBox(left, Ui.spacer(), figs);
		card.setAlignment(Pos.CENTER_LEFT);
		card.setPadding(new Insets(20));
		card.setStyle("-fx-background-color: linear-gradient(to bottom right, #1d1430, #15111d 70%);"
				+ " -fx-background-radius: 16; -fx-border-radius: 16;"
				+ " -fx-border-color: -color-border-violet; -fx-border-width: 1;");
		return card;
	}

	private VBox figureBlock(String caption, Label fig) {
		VBox v = new VBox(2, fig, Ui.cap(caption));
		v.setAlignment(Pos.CENTER_RIGHT);
		return v;
	}

	private static Label figure(Color fill) {
		// JetBrains Mono — these are engine metric values (BUCKETS/CONTACTS/STORED).
		Label l = new Label("—");
		l.setTextFill(fill);
		l.getStyleClass().add("mono");
		l.setStyle("-fx-font-size: 22px; -fx-font-weight: 800;");
		return l;
	}

	// --------------------------------------------------------------- two columns

	private Region twoColumn() {
		GridPane grid = new GridPane();
		grid.setHgap(16);
		ColumnConstraints c1 = new ColumnConstraints();
		c1.setPercentWidth(54);
		ColumnConstraints c2 = new ColumnConstraints();
		c2.setPercentWidth(46);
		grid.getColumnConstraints().addAll(c1, c2);

		Region left = leftColumn();
		Region rpc = rpcPanel();
		grid.add(left, 0, 0);
		grid.add(rpc, 1, 0);
		GridPane.setVgrow(left, Priority.ALWAYS);
		GridPane.setVgrow(rpc, Priority.ALWAYS);
		GridPane.setHgrow(left, Priority.ALWAYS);
		GridPane.setHgrow(rpc, Priority.ALWAYS);
		return grid;
	}

	private Region leftColumn() {
		VBox buckets = Ui.card();
		HBox bHead = Ui.row(10, Pos.CENTER_LEFT, Ui.h2("Routing table · k-buckets"),
				Ui.spacer(), Ui.mono("distance = id ⊕ target", Ui.TEXT_DIM, 11));
		buckets.getChildren().addAll(bHead, bucketRows);

		VBox stored = Ui.card();
		stored.getChildren().addAll(Ui.h2("Stored keys"), storedRows);

		VBox col = new VBox(16, buckets, stored);
		return col;
	}

	private Region rpcPanel() {
		Label live = new Label("● live");
		live.setTextFill(Ui.GREEN);
		live.setStyle("-fx-font-size: 11px; -fx-font-weight: 700;");
		HBox head = Ui.row(8, Pos.CENTER_LEFT, Ui.h2("RPC activity"), Ui.spacer(), live);
		head.setPadding(new Insets(15, 16, 15, 16));
		head.setStyle("-fx-background-color: #15111d; -fx-background-radius: 14 14 0 0;"
				+ " -fx-border-color: transparent transparent #221d2c transparent; -fx-border-width: 0 0 1 0;");

		VBox log = new VBox(7);
		log.setPadding(new Insets(14, 16, 16, 16));
		// Placeholder rows until the engine emits an RPC event stream (next increment).
		String[][] rows = {
				{"14:22:08", "←", "FIND_VALUE", "VALUE · 24 p"},
				{"14:22:08", "→", "VALUE", "sent 612 B"},
				{"14:22:06", "→", "PING", "PONG · 24ms"},
				{"14:22:04", "→", "FIND_NODE", "14 contacts"},
				{"14:22:03", "→", "STORE", "STORED ok"},
				{"14:22:01", "→", "FIND_NODE", "20 contacts"},
				{"14:21:59", "←", "PING", "PONG · 19ms"},
				{"14:21:57", "→", "STORE", "STORED ok"},
		};
		for (String[] r : rows) {
			log.getChildren().add(rpcLine(r[0], r[1], r[2], r[3]));
		}
		// The log scrolls within the panel (its own ScrollPane) — no global scroll.
		ScrollPane logScroll = new ScrollPane(log);
		logScroll.setFitToWidth(true);
		logScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		logScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;"
				+ " -fx-padding: 0; -fx-background-insets: 0;");
		VBox panel = new VBox(head, logScroll);
		panel.setStyle("-fx-background-color: #100d17; -fx-background-radius: 14;"
				+ " -fx-border-radius: 14; -fx-border-color: -color-border; -fx-border-width: 1;");
		VBox.setVgrow(logScroll, Priority.ALWAYS);
		return panel;
	}

	private Region rpcLine(String time, String dir, String type, String result) {
		Label t = Ui.mono(time, Ui.TEXT_DIM, 11);
		t.setMinWidth(58);
		Label d = Ui.mono(dir, dir.equals("→") ? Ui.PINK : Ui.CYAN, 12);
		Label ty = Ui.mono(type, typeColor(type), 11.5);
		ty.setStyle("-fx-font-size: 11.5px; -fx-font-weight: 700;");
		ty.setMinWidth(86);
		Label res = Ui.mono(result, result.contains("timeout") ? Ui.RED : Ui.TEXT_LO, 11);
		return Ui.row(10, Pos.CENTER_LEFT, t, d, ty, Ui.spacer(), res);
	}

	private Color typeColor(String type) {
		return switch (type) {
			case "FIND_NODE", "FIND_VALUE" -> Ui.ACCENT;
			case "STORE" -> Ui.AMBER;
			case "PING" -> Ui.CYAN;
			default -> Ui.GREEN; // replies: VALUE / NODES / PONG / STORED
		};
	}

	// --------------------------------------------------------------- live refresh

	private void refresh() {
		var kad = runtime.kademlia();
		NodeId id = kad.self().getId();
		idLabel.setText(group(id.toString()));
		long up = runtime.uptimeMillis() / 1000;
		endpointLabel.setText("udp://" + kad.self().getHost() + ":" + kad.self().getPort()
				+ " · " + (runtime.isSeed() ? "seed node" : "peer node")
				+ " · up " + up / 60 + "m " + up % 60 + "s");

		int[] sizes = kad.routingTable().bucketSizes();
		int nonEmpty = 0;
		int contacts = 0;
		for (int s : sizes) {
			if (s > 0) {
				nonEmpty++;
			}
			contacts += s;
		}
		bucketsFig.setText(nonEmpty + "/160");
		contactsFig.setText(Integer.toString(contacts));
		storedFig.setText(Integer.toString(kad.storedKeyCount()));

		rebuildBucketRows(sizes);
		rebuildStoredRows(kad.storedKeys());
	}

	private void rebuildBucketRows(int[] sizes) {
		bucketRows.getChildren().clear();
		boolean any = false;
		for (int i = sizes.length - 1; i >= 0 && bucketRows.getChildren().size() < 12; i--) {
			if (sizes[i] == 0) {
				continue;
			}
			any = true;
			bucketRows.getChildren().add(bucketRow(i, sizes[i]));
		}
		if (!any) {
			bucketRows.getChildren().add(Ui.mono("no contacts yet — bootstrap in progress…", Ui.TEXT_DIM, 11));
		}
	}

	private Region bucketRow(int index, int count) {
		Label label = Ui.mono("b" + index, Ui.ACCENT_SOFT, 11.5);
		label.setMinWidth(42);

		StackPane track = new StackPane();
		track.setBackground(Ui.fill(Ui.SURFACE_IN, 999));
		track.setPrefHeight(7);
		track.setMaxWidth(Double.MAX_VALUE);
		Region fillBar = new Region();
		double frac = Math.min(1.0, count / 20.0);
		fillBar.setMaxWidth(Double.MAX_VALUE);
		StackPane.setAlignment(fillBar, Pos.CENTER_LEFT);
		// Approximate the percentage fill: scale via a translate-free width binding.
		fillBar.prefWidthProperty().bind(track.widthProperty().multiply(frac));
		String color = count >= 18 ? "linear-gradient(to right, #9b3ec9, #c64ff0)"
				: count >= 8 ? "#5b4a73" : "#34284a";
		fillBar.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 999;");
		track.getChildren().add(fillBar);
		StackPane.setAlignment(fillBar, Pos.CENTER_LEFT);
		HBox.setHgrow(track, Priority.ALWAYS);

		Label n = Ui.mono(count + "/20", Ui.TEXT_LO, 11);
		n.setMinWidth(40);
		return Ui.row(10, Pos.CENTER_LEFT, label, track, n);
	}

	private void rebuildStoredRows(List<NodeId> keys) {
		storedRows.getChildren().clear();
		if (keys.isEmpty()) {
			storedRows.getChildren().add(Ui.mono("no keys stored locally", Ui.TEXT_DIM, 11));
			return;
		}
		for (NodeId key : keys.subList(0, Math.min(keys.size(), 8))) {
			Label chip = Ui.mono(key.toString().substring(0, 10) + "…", Ui.ACCENT, 11.5);
			chip.setPadding(new Insets(3, 8, 3, 8));
			chip.setBackground(Ui.fill(Color.web("#1d1430"), 5));
			Label meta = Ui.mono("infohash → seed metadata", Ui.TEXT_LO, 11);
			Label ttl = Ui.mono("ttl ∞", Ui.TEXT_DIM, 11);
			storedRows.getChildren().add(Ui.row(10, Pos.CENTER_LEFT, chip, meta, Ui.spacer(), ttl));
		}
	}

	private static String group(String hex) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < hex.length(); i += 8) {
			if (i > 0) {
				sb.append(' ');
			}
			sb.append(hex, i, Math.min(hex.length(), i + 8));
		}
		return sb.toString();
	}
}
