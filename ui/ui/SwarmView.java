package ui;

import java.util.ArrayList;
import java.util.List;

import app.NodeRuntime;
import core.kademlia.Contact;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.RotateTransition;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.util.Duration;

/**
 * Swarm map — the hero screen. Five stat cards, a polar graph well (the YOU node
 * with a rotating dashed orbit, peer nodes on two elliptical rings each in its own
 * tint/size with a ↓-rate caption, tinted animated flow lines), and a peer table.
 * Semi-live: peer nodes/rows are this node's real routing-table contacts; per-peer
 * tint/size/rate are derived deterministically from the contact id until the
 * transfer layer exposes real speeds. Polar layout ports {@code buildSwarm()}.
 */
final class SwarmView {

	/** Per-peer tint palette (mirrors the design's buildSwarm colours). */
	private static final Color[] TINTS = {
			Ui.ACCENT, Ui.CYAN, Ui.PINK, Ui.GREEN, Ui.AMBER, Ui.ACCENT_SOFT};

	private final NodeRuntime runtime;
	private final VBox root = new VBox(14);

	private final Label connectedNumber = bigNumber("—", Ui.TEXT_HI);
	private final SwarmGraph graph;
	private final VBox peerTableBody = new VBox(0);

	SwarmView(NodeRuntime runtime) {
		this.runtime = runtime;
		this.graph = new SwarmGraph();

		Region well = graphWell();
		VBox.setVgrow(well, Priority.ALWAYS); // the well absorbs leftover height (no global scroll)
		root.getChildren().addAll(header(), statCards(), well, peerTable());

		refresh();
		Timeline ticker = new Timeline(new KeyFrame(Duration.seconds(2), e -> refresh()));
		ticker.setCycleCount(Animation.INDEFINITE);
		ticker.play();
	}

	Node getRoot() {
		return root;
	}

	private Region header() {
		Label refreshing = Ui.pillGreen("● auto-refreshing");
		Label title = new Label("Live peers sharing ");
		title.setTextFill(Ui.TEXT_SOFT);
		title.setStyle("-fx-font-size: 13px;");
		Label film = new Label("Tears of Steel");
		film.setTextFill(Color.web("#c7bfd6"));
		film.setStyle("-fx-font-size: 13px; -fx-font-weight: 600;");
		Label rest = new Label(" · the more peers, the faster the stream");
		rest.setTextFill(Ui.TEXT_SOFT);
		rest.setStyle("-fx-font-size: 13px;");
		HBox sub = new HBox(title, film, rest);
		sub.setAlignment(Pos.CENTER_LEFT);

		return Ui.row(12, Pos.CENTER_LEFT, new VBox(3, Ui.h1("Swarm map"), sub), Ui.spacer(), refreshing);
	}

	// ------------------------------------------------------------- stat cards

	private Region statCards() {
		GridPane g = new GridPane();
		g.setHgap(14);
		for (int i = 0; i < 5; i++) {
			ColumnConstraints c = new ColumnConstraints();
			c.setPercentWidth(20);
			g.getColumnConstraints().add(c);
		}
		g.add(statCard("CONNECTED PEERS", connectedNumber, null), 0, 0);
		g.add(statCard("SWARM HEALTH", bigNumber("Excellent", Ui.GREEN), null), 1, 0);
		g.add(statCard("DOWNLOAD", bigNumber("11.4", Ui.CYAN), "MB/s"), 2, 0);
		g.add(statCard("UPLOAD", bigNumber("3.2", Ui.PINK), "MB/s"), 3, 0);
		g.add(statCard("SHARE RATIO", bigNumber("1.84", Ui.TEXT_HI), null), 4, 0);
		return g;
	}

	private Region statCard(String caption, Label number, String unit) {
		HBox value = new HBox(6, number);
		value.setAlignment(Pos.BOTTOM_LEFT);
		if (unit != null) {
			Label u = new Label(unit);
			u.setTextFill(Ui.TEXT_LO);
			u.setStyle("-fx-font-size: 13px; -fx-font-weight: 600;");
			value.getChildren().add(u);
		}
		VBox v = Ui.card();
		v.setSpacing(8);
		v.getChildren().addAll(value, Ui.cap(caption));
		return v;
	}

	/** Big stat number — JetBrains Mono 26/800 (metric values use mono per DESIGN_TOKENS). */
	private static Label bigNumber(String text, Color fill) {
		Label l = new Label(text);
		l.setTextFill(fill);
		l.getStyleClass().add("mono");
		l.setStyle("-fx-font-size: 26px; -fx-font-weight: 800;");
		return l;
	}

	// -------------------------------------------------------------- graph well

	private Region graphWell() {
		StackPane well = new StackPane(graph);
		well.setMinHeight(280);   // can shrink on short windows…
		well.setPrefHeight(460);  // …but prefers a generous hero size (grows via VGrow)
		// base panel fill + a prominent deep-purple radial glow layered on top
		well.setStyle("-fx-background-color: #131019,"
				+ " radial-gradient(center 50% 48%, radius 65%, rgba(198,79,240,0.16), rgba(198,79,240,0.04) 55%, transparent 72%);"
				+ " -fx-background-radius: 18; -fx-border-radius: 18;"
				+ " -fx-border-color: -color-border; -fx-border-width: 1;");
		StackPane.setMargin(graph, new Insets(1));

		well.getChildren().add(legend());
		return well;
	}

	private Region legend() {
		HBox card = new HBox(18, swatch(Ui.ACCENT, "active transfer"), swatch(Color.web("#322b40"), "known peer"));
		card.setAlignment(Pos.CENTER_LEFT);
		card.setPadding(new Insets(9, 14, 9, 14));
		card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
		card.setStyle("-fx-background-color: rgba(15,13,21,0.8); -fx-background-radius: 11;"
				+ " -fx-border-radius: 11; -fx-border-color: -color-border-strong; -fx-border-width: 1;");
		StackPane.setAlignment(card, Pos.BOTTOM_LEFT);
		StackPane.setMargin(card, new Insets(0, 0, 16, 16));
		return card;
	}

	private Region swatch(Color c, String text) {
		Region line = new Region();
		line.setPrefSize(16, 2);
		line.setMinSize(16, 2);
		line.setBackground(Ui.fill(c, 1));
		return Ui.row(7, Pos.CENTER_LEFT, line, Ui.mono(text, Ui.TEXT_LO, 11));
	}

	// -------------------------------------------------------------- peer table

	private Region peerTable() {
		VBox card = Ui.card();
		card.setSpacing(0);
		card.setPadding(new Insets(0));

		// The rows scroll inside the card so a large swarm never grows the page.
		ScrollPane bodyScroll = new ScrollPane(peerTableBody);
		bodyScroll.setFitToWidth(true);
		bodyScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		bodyScroll.setMaxHeight(220);
		bodyScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;"
				+ " -fx-padding: 0; -fx-background-insets: 0;");

		card.getChildren().add(tableHeader());
		card.getChildren().add(bodyScroll);
		return card;
	}

	private Region tableHeader() {
		HBox h = tableRow(true, Ui.TEXT_DIM,
				"PEER", "LOCATION", "LATENCY", "PIECES", "DOWN", "UP", "CONN", "XOR DIST");
		h.setBackground(Ui.fill(Ui.SURFACE_IN, 0));
		return h;
	}

	private HBox tableRow(boolean header, Color fill, String... cells) {
		// fr ratios from the design grid template
		double[] weights = {1.6, 1.2, 0.8, 1.4, 0.8, 0.8, 0.8, 1.0};
		HBox row = new HBox();
		row.setPadding(new Insets(11, 16, 11, 16));
		row.setAlignment(Pos.CENTER_LEFT);
		if (!header) {
			row.setBorder(new javafx.scene.layout.Border(new javafx.scene.layout.BorderStroke(
					Ui.BORDER, javafx.scene.layout.BorderStrokeStyle.SOLID, null,
					new javafx.scene.layout.BorderWidths(1, 0, 0, 0))));
		}
		for (int i = 0; i < cells.length; i++) {
			Label l = Ui.mono(cells[i], fill, header ? 10.5 : 11.5);
			l.setMaxWidth(Double.MAX_VALUE);
			HBox.setHgrow(l, Priority.ALWAYS);
			l.setPrefWidth(weights[i] * 80);
			row.getChildren().add(l);
		}
		return row;
	}

	// ----------------------------------------------------------- live refresh

	private void refresh() {
		List<Contact> contacts = runtime.kademlia().routingTable().allContacts();
		connectedNumber.setText(Integer.toString(contacts.size()));
		graph.setPeers(contacts);

		peerTableBody.getChildren().clear();
		if (contacts.isEmpty()) {
			HBox empty = new HBox(Ui.mono("no peers yet — start another node or wait for bootstrap…", Ui.TEXT_DIM, 11.5));
			empty.setPadding(new Insets(14, 16, 14, 16));
			peerTableBody.getChildren().add(empty);
			return;
		}
		var self = runtime.kademlia().self().getId();
		for (int i = 0; i < Math.min(contacts.size(), 10); i++) {
			Contact c = contacts.get(i);
			Color tint = TINTS[i % TINTS.length];
			String id = c.getId().toString();
			String xor = self.distance(c.getId()).toString(16);
			peerTableBody.getChildren().add(peerTableRow(c, id, tint,
					xor.length() > 10 ? xor.substring(0, 10) + "…" : xor));
		}
	}

	private HBox peerTableRow(Contact c, String id, Color tint, String xor) {
		HBox row = new HBox();
		row.setPadding(new Insets(10, 16, 10, 16));
		row.setAlignment(Pos.CENTER_LEFT);
		row.setBorder(new javafx.scene.layout.Border(new javafx.scene.layout.BorderStroke(
				Ui.BORDER, javafx.scene.layout.BorderStrokeStyle.SOLID, null,
				new javafx.scene.layout.BorderWidths(1, 0, 0, 0))));
		double[] w = {1.6, 1.2, 0.8, 1.4, 0.8, 0.8, 0.8, 1.0};

		// PEER: tint-bordered glyph + id
		Label badge = Ui.mono(id.substring(0, 2).toUpperCase(), Color.WHITE, 10);
		badge.setStyle("-fx-font-size: 10px; -fx-font-weight: 800;");
		StackPane b = new StackPane(badge);
		b.setPrefSize(24, 24);
		b.setMinSize(24, 24);
		b.setBackground(Ui.fill(tint.deriveColor(0, 1, 1, 0.20), 7));
		b.setBorder(Ui.line(tint.deriveColor(0, 1, 1, 0.55), 7));
		HBox peer = grow(Ui.row(8, Pos.CENTER_LEFT, b, Ui.mono(id.substring(0, 6), Ui.TEXT, 11.5)), w[0]);

		Node loc = grow(plain(c.getHost() + ":" + c.getPort(), Ui.TEXT_MID), w[1]);
		Node lat = grow(Ui.mono("—", Ui.TEXT_SOFT, 11.5), w[2]);
		Node pieces = grow(piecesCell(0.6, tint), w[3]);
		Node down = grow(Ui.mono("—", Ui.CYAN, 11.5), w[4]);
		Node up = grow(Ui.mono("—", Ui.PINK, 11.5), w[5]);
		Node conn = grow(Ui.mono("TCP", Ui.GREEN, 11.5), w[6]);
		Node dist = grow(Ui.mono(xor, Ui.TEXT_DIM, 11.5), w[7]);

		row.getChildren().addAll(peer, loc, lat, pieces, down, up, conn, dist);
		return row;
	}

	private Region piecesCell(double frac, Color tint) {
		StackPane track = new StackPane();
		track.setPrefSize(60, 4);
		track.setMaxWidth(70);
		track.setBackground(Ui.fill(Ui.BORDER, 2));
		Region fill = new Region();
		fill.prefWidthProperty().bind(track.widthProperty().multiply(frac));
		fill.setMaxWidth(Double.MAX_VALUE);
		fill.setStyle("-fx-background-color: linear-gradient(to right, #9b3ec9, #c64ff0); -fx-background-radius: 2;");
		StackPane.setAlignment(fill, Pos.CENTER_LEFT);
		track.getChildren().add(fill);
		return Ui.row(8, Pos.CENTER_LEFT, track, Ui.mono((int) (frac * 100) + "%", Ui.TEXT_LO, 10.5));
	}

	private Label plain(String text, Color fill) {
		Label l = new Label(text);
		l.setTextFill(fill);
		l.setStyle("-fx-font-size: 12.5px;");
		return l;
	}

	private <T extends Region> T grow(T node, double weight) {
		node.setMaxWidth(Double.MAX_VALUE);
		node.setPrefWidth(weight * 80);
		HBox.setHgrow(node, Priority.ALWAYS);
		return node;
	}

	/**
	 * The graph pane: positions the YOU node and peer nodes by polar math on every
	 * resize ({@code Pane} + manual {@code layoutChildren} = the design's
	 * absolutely-positioned swarm).
	 */
	private static final class SwarmGraph extends Pane {

		private final VBox youNode = youNode();
		private final Circle orbit = new Circle();
		private final List<Node> peerNodes = new ArrayList<>();
		private final List<Line> peerLines = new ArrayList<>();
		private double dashOffset = 0;

		SwarmGraph() {
			orbit.setFill(Color.TRANSPARENT);
			orbit.setStroke(Color.web("#c64ff0", 0.5));
			orbit.setStrokeWidth(1);
			orbit.getStrokeDashArray().addAll(4.0, 4.0);
			getChildren().addAll(orbit, youNode);

			RotateTransition spin = new RotateTransition(Duration.seconds(24), orbit);
			spin.setByAngle(360);
			spin.setCycleCount(Animation.INDEFINITE);
			spin.setInterpolator(Interpolator.LINEAR);
			spin.play();

			Timeline flow = new Timeline(new KeyFrame(Duration.millis(60), e -> {
				dashOffset -= 1;
				for (Line l : peerLines) {
					l.setStrokeDashOffset(dashOffset);
				}
			}));
			flow.setCycleCount(Animation.INDEFINITE);
			flow.play();
		}

		void setPeers(List<Contact> contacts) {
			getChildren().removeAll(peerNodes);
			getChildren().removeAll(peerLines);
			peerNodes.clear();
			peerLines.clear();
			int n = contacts.size();
			for (int i = 0; i < n; i++) {
				Contact c = contacts.get(i);
				Color tint = TINTS[i % TINTS.length];
				int bucket = pseudo(c, 3); // 0 idle / 1 active / 2 fast
				boolean idle = bucket == 0;
				double size = bucket == 2 ? 46 : bucket == 1 ? 38 : 30;
				double rate = 0.3 + pseudo(c, 40) / 10.0;

				Line line = new Line();
				if (idle) {
					line.setStroke(Color.web("#2a2435"));
					line.setStrokeWidth(1);
				} else {
					line.setStroke(tint.deriveColor(0, 1, 1, 0.6));
					line.setStrokeWidth(bucket == 2 ? 2 : 1.4);
					line.getStrokeDashArray().addAll(4.0, 4.0);
				}
				peerLines.add(line);

				Node node = peerNode(c.getId().toString().substring(0, 2).toUpperCase(),
						size, idle ? Color.web("#5f5670") : tint, idle ? null : "↓" + String.format("%.1f", rate));
				// meta: ring, index, count, circle diameter (so layout can center the CIRCLE)
				node.setUserData(new int[] {i % 2, i, n, (int) size});
				peerNodes.add(node);
			}
			getChildren().addAll(peerLines);
			getChildren().addAll(peerNodes);
			requestLayout();
		}

		/** Deterministic pseudo-value from a contact id, in {@code [0, mod)}. */
		private int pseudo(Contact c, int mod) {
			return Math.floorMod(c.getId().hashCode(), mod);
		}

		@Override
		protected void layoutChildren() {
			double w = getWidth();
			double h = getHeight();
			double cx = w / 2;   // dead center of the well
			double cy = h / 2;

			// Orbit ring: a Circle whose centre is (cx,cy); its layout-bounds centre is
			// therefore (cx,cy), so the RotateTransition pivots exactly in place.
			orbit.setRadius(47);
			orbit.setCenterX(cx);
			orbit.setCenterY(cy);

			// YOU node: centre its 74px CIRCLE (the VBox's top child) on (cx,cy).
			layoutCircleAt(youNode, cx, cy, 74);

			for (int k = 0; k < peerNodes.size(); k++) {
				Node node = peerNodes.get(k);
				int[] meta = (int[]) node.getUserData();
				int ring = meta[0];
				int i = meta[1];
				int n = meta[2];
				double circle = meta[3];
				double rx = (ring == 1 ? 0.41 : 0.26) * w;
				double ry = (ring == 1 ? 0.43 : 0.30) * h;
				double ang = (double) i / Math.max(1, n) * 2 * Math.PI - Math.PI / 2;
				double x = cx + rx * Math.cos(ang);
				double y = cy + ry * Math.sin(ang);
				layoutCircleAt(node, x, y, circle);

				// Connection line: from the exact YOU centre to this peer's circle centre.
				Line line = peerLines.get(k);
				line.setStartX(cx);
				line.setStartY(cy);
				line.setEndX(x);
				line.setEndY(y);
			}
		}

		/**
		 * Position {@code node} (a VBox of [circle, optional caption]) so that its
		 * top child — the {@code circleH}px circle — is centred on ({@code x},{@code y}),
		 * regardless of any caption below it.
		 */
		private void layoutCircleAt(Node node, double x, double y, double circleH) {
			double nw = node.prefWidth(-1);
			double nh = node.prefHeight(-1);
			node.resizeRelocate(x - nw / 2, y - circleH / 2, nw, nh);
		}

		private static VBox youNode() {
			Label l = new Label("YOU");
			l.setTextFill(Color.WHITE);
			l.setStyle("-fx-font-weight: 800; -fx-font-size: 13px;");
			StackPane circle = new StackPane(l);
			circle.setPrefSize(74, 74);
			circle.setMinSize(74, 74);
			circle.setMaxSize(74, 74);
			circle.getStyleClass().add("you-node");

			Label chip = Ui.mono("seed · self", Color.web("#c7bfd6"), 10.5);
			chip.setPadding(new Insets(2, 8, 2, 8));
			chip.setStyle("-fx-font-size: 10.5px; -fx-font-family: 'JetBrains Mono';"
					+ " -fx-background-color: #15111d; -fx-background-radius: 6;"
					+ " -fx-border-radius: 6; -fx-border-color: #2c2638; -fx-border-width: 1;"
					+ " -fx-text-fill: #c7bfd6;");

			VBox box = new VBox(8, circle, chip);
			box.setAlignment(Pos.CENTER);
			return box;
		}

		private static Node peerNode(String label, double size, Color tint, String rate) {
			Label l = new Label(label);
			l.setTextFill(tint);
			l.getStyleClass().add("mono");
			l.setStyle("-fx-font-size: 10px; -fx-font-weight: 800;");
			StackPane circle = new StackPane(l);
			circle.setPrefSize(size, size);
			circle.setMinSize(size, size);
			circle.setMaxSize(size, size);
			String hex = toHex(tint);
			circle.setStyle("-fx-background-color: #181320; -fx-background-radius: 999;"
					+ " -fx-border-radius: 999; -fx-border-width: 2; -fx-border-color: " + hex + ";");
			if (rate != null) {
				DropShadow glow = new DropShadow(16, tint.deriveColor(0, 1, 1, 0.6));
				circle.setEffect(glow);
			}

			VBox box = new VBox(4, circle);
			box.setAlignment(Pos.CENTER);
			if (rate != null) {
				box.getChildren().add(Ui.mono(rate, Ui.TEXT_LO, 9.5));
			}
			return box;
		}

		private static String toHex(Color c) {
			return String.format("#%02x%02x%02x",
					(int) (c.getRed() * 255), (int) (c.getGreen() * 255), (int) (c.getBlue() * 255));
		}
	}
}
