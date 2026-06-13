package ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import app.NodeRuntime;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;

/**
 * The window frame: a 46px custom title bar (traffic-light dots + brand on the
 * left, a live-status cluster centered, line-icon window controls on the right,
 * draggable) and a 230px sidebar with BROWSE / ENGINE sections that swaps the five
 * screens. Recreated from {@code reference_screens/*.png} (Direction A).
 */
final class AppShell {

	private final BorderPane root = new BorderPane();
	private final StackPane content = new StackPane();
	private final List<NavItem> navItems = new ArrayList<>();

	AppShell(NodeRuntime runtime, Stage stage) {
		root.setBackground(Ui.fill(Ui.BG_APP, 0));
		root.setTop(titleBar(runtime, stage));

		HBox bodyRow = new HBox(sidebar(runtime), contentArea());
		root.setCenter(bodyRow);

		// Build the five screens. Display names must match what NavItem registers.
		register("Library", new LibraryView().getRoot());
		register("Now Playing", new PlayerView().getRoot());
		register("Swarm", new SwarmView(runtime).getRoot());
		register("Add Stream", new AddStreamView().getRoot());
		register("DHT Inspector", new DhtView(runtime).getRoot());

		select(navItems.get(2)); // open on the Swarm map — the hero
	}

	BorderPane getRoot() {
		return root;
	}

	// --------------------------------------------------------------- title bar

	private Region titleBar(NodeRuntime runtime, Stage stage) {
		Circle close = trafficDot(Ui.RED);
		Circle min = trafficDot(Ui.AMBER);
		Circle full = trafficDot(Ui.GREEN);
		close.setOnMouseClicked(e -> stage.close());
		min.setOnMouseClicked(e -> stage.setIconified(true));
		full.setOnMouseClicked(e -> stage.setMaximized(!stage.isMaximized()));

		HBox left = new HBox(8, close, min, full, gap(8), brandMark(), wordmark());
		left.setAlignment(Pos.CENTER_LEFT);

		HBox center = new HBox(10, statusPill(), peersPill(), speedReadout());
		center.setAlignment(Pos.CENTER);

		HBox right = new HBox(16, winIcon(Icons.expand(Ui.TEXT_DIM), null),
				winIcon(Icons.minimize(Ui.TEXT_DIM), () -> stage.setIconified(true)),
				winIcon(Icons.close(Ui.TEXT_SOFT), stage::close));
		right.setAlignment(Pos.CENTER_RIGHT);

		HBox spread = new HBox(left, Ui.spacer(), right);
		spread.setAlignment(Pos.CENTER_LEFT);
		spread.setPadding(new Insets(0, 16, 0, 16));

		StackPane bar = new StackPane(spread, center);
		bar.setPrefHeight(46);
		bar.setMinHeight(46);
		bar.setStyle("-fx-background-color: linear-gradient(to bottom, #181320, #141019);"
				+ " -fx-border-color: transparent transparent #272131 transparent; -fx-border-width: 0 0 1 0;");

		final double[] off = new double[2];
		bar.setOnMousePressed(e -> {
			off[0] = e.getScreenX() - stage.getX();
			off[1] = e.getScreenY() - stage.getY();
		});
		bar.setOnMouseDragged(e -> {
			stage.setX(e.getScreenX() - off[0]);
			stage.setY(e.getScreenY() - off[1]);
		});
		return bar;
	}

	private Region brandMark() {
		// four-dot swarm glyph: magenta (glow) + white + pink + green
		Circle magenta = new Circle(4.5, 4, 3.4, Color.web("#c64ff0"));
		magenta.setEffect(new javafx.scene.effect.DropShadow(8, Color.web("#c64ff0", 0.7)));
		Circle white = new Circle(14, 5, 2.2, Color.web("#f4f1f8"));
		Circle pink = new Circle(5, 14, 2.4, Color.web("#ee7fb0"));
		Circle green = new Circle(14.5, 14.5, 2.6, Color.web("#46d39a"));
		Pane p = new Pane(magenta, white, pink, green);
		p.setPrefSize(20, 20);
		p.setMinSize(20, 20);
		p.setMaxSize(20, 20);
		return p;
	}

	private Region wordmark() {
		javafx.scene.text.Text we = new javafx.scene.text.Text("we");
		we.setFill(Ui.TEXT_HI);
		we.setStyle("-fx-font-weight: 800; -fx-font-size: 15px;");
		javafx.scene.text.Text stream = new javafx.scene.text.Text("Stream");
		stream.setFill(Ui.ACCENT);
		stream.setStyle("-fx-font-weight: 800; -fx-font-size: 15px;");
		return new javafx.scene.text.TextFlow(we, stream);
	}

	private Label statusPill() {
		Label l = Ui.pillGreen("● DHT CONNECTED");
		l.setStyle(l.getStyle() + "-fx-font-size: 11px;");
		return l;
	}

	private Label peersPill() {
		Label l = new Label("● 24 peers");
		l.getStyleClass().add("pill");
		l.setStyle("-fx-background-color: #1b1722; -fx-text-fill: #ada3bd;"
				+ " -fx-border-color: #2c2638; -fx-border-width: 1;");
		return l;
	}

	private Region speedReadout() {
		HBox inner = Ui.row(10, Pos.CENTER_LEFT,
				unit("↓ 11.4", " MB/s", Ui.CYAN), unit("↑ 3.2", " MB/s", Ui.PINK));
		inner.setPadding(new Insets(5, 11, 5, 11));
		inner.setStyle("-fx-background-color: #1b1722; -fx-background-radius: 999;"
				+ " -fx-border-radius: 999; -fx-border-color: #2c2638; -fx-border-width: 1;");
		return inner;
	}

	private Region unit(String value, String unit, Color color) {
		return Ui.row(0, Pos.CENTER_LEFT, Ui.mono(value, color, 11.5), Ui.mono(unit, Ui.TEXT_LO, 11.5));
	}

	private Circle trafficDot(Color c) {
		Circle dot = new Circle(6.5, c);
		dot.setStyle("-fx-cursor: hand;");
		return dot;
	}

	private Region winIcon(Node icon, Runnable onClick) {
		StackPane p = new StackPane(icon);
		p.setStyle("-fx-cursor: hand;");
		if (onClick != null) {
			p.setOnMouseClicked(e -> onClick.run());
		}
		return p;
	}

	// ----------------------------------------------------------------- sidebar

	private Region sidebar(NodeRuntime runtime) {
		VBox bar = new VBox(2);
		bar.setPrefWidth(230);
		bar.setMinWidth(230);
		bar.setBackground(Ui.fill(Ui.BG_CHROME, 0));
		bar.setPadding(new Insets(16, 14, 16, 14));

		bar.getChildren().add(sectionLabel("BROWSE"));
		addNav(bar, "Library", Icons::home, null);
		addNav(bar, "Now Playing", Icons::nowPlaying, null);
		addNav(bar, "Swarm", Icons::swarm, "24");
		addNav(bar, "Add Stream", Icons::addStream, null);
		bar.getChildren().add(gap(14));
		bar.getChildren().add(sectionLabel("ENGINE"));
		addNav(bar, "DHT Inspector", Icons::dht, null);

		bar.getChildren().add(Ui.vspacer());
		bar.getChildren().add(footer(runtime));
		return bar;
	}

	private Label sectionLabel(String text) {
		Label l = Ui.cap(text);
		l.setPadding(new Insets(8, 0, 8, 10));
		return l;
	}

	private void addNav(VBox bar, String name, Function<Paint, Node> icon, String badge) {
		NavItem item = new NavItem(name, icon, badge);
		item.node.setOnMouseClicked(e -> select(item));
		navItems.add(item);
		bar.getChildren().add(item.node);
	}

	private Region footer(NodeRuntime runtime) {
		Circle dot = new Circle(4, Ui.GREEN);
		Label role = new Label("This node · " + (runtime.isSeed() ? "seed" : "peer"));
		role.setTextFill(Ui.TEXT);
		role.setStyle("-fx-font-weight: 700; -fx-font-size: 12.5px;");
		HBox top = Ui.row(7, Pos.CENTER_LEFT, dot, role);

		String hex = runtime.kademlia().self().getId().toString();
		Label id = Ui.mono("id " + hex.substring(0, 8) + "…" + hex.substring(hex.length() - 7), Ui.TEXT_LO, 10.5);
		Label ep = Ui.mono("127.0.0.1:" + runtime.port(), Ui.TEXT_LO, 10.5);

		VBox f = new VBox(4, top, id, ep);
		f.setBackground(Ui.fill(Color.web("#1a1623"), 12));
		f.setBorder(Ui.line(Ui.BORDER, 12));
		f.setPadding(new Insets(12, 14, 12, 14));
		return f;
	}

	// ----------------------------------------------------------------- content

	/**
	 * The content area is the StackPane itself — NO global ScrollPane. It fills the
	 * window (HGrow + the HBox stretches it to full height) and each screen resizes
	 * to fit; only overflow-prone lists (Swarm peer table, DHT RPC log) carry their
	 * own ScrollPane. Padding kept tight (16) so screens fit a 1080p viewport.
	 */
	private Region contentArea() {
		content.setBackground(Ui.fill(Ui.BG_APP, 0));
		content.setPadding(new Insets(16, 22, 16, 22));
		content.setAlignment(Pos.TOP_LEFT);
		HBox.setHgrow(content, Priority.ALWAYS);
		return content;
	}

	// ------------------------------------------------------------ view switching

	private void register(String name, Node view) {
		view.setVisible(false);
		view.setUserData(name);
		// Let the screen fill the content area vertically/horizontally (so e.g. the
		// Swarm well can grow) — StackPane only stretches a child up to its max size.
		if (view instanceof Region r) {
			r.setMaxWidth(Double.MAX_VALUE);
			r.setMaxHeight(Double.MAX_VALUE);
		}
		content.getChildren().add(view);
	}

	private void select(NavItem chosen) {
		for (NavItem item : navItems) {
			item.setActive(item == chosen);
		}
		for (Node view : content.getChildren()) {
			view.setVisible(chosen.name.equals(view.getUserData()));
		}
	}

	private static Region gap(double size) {
		Region r = new Region();
		r.setMinHeight(size);
		r.setMinWidth(size);
		return r;
	}

	/**
	 * A sidebar nav row: icon + label (+ optional badge). The active state is the
	 * {@code .nav-item.active} pill (magenta fill + inset 3px left accent bar from
	 * CSS) plus a brightened icon/label.
	 */
	private static final class NavItem {
		final String name;
		final HBox node;
		private final Label label;
		private final StackPane iconBox;
		private final Function<Paint, Node> iconFactory;

		NavItem(String name, Function<Paint, Node> iconFactory, String badge) {
			this.name = name;
			this.iconFactory = iconFactory;

			iconBox = new StackPane(iconFactory.apply(Ui.TEXT_MID));
			iconBox.setMinWidth(22);

			label = new Label(name);
			label.setTextFill(Color.web("#c7bfd6"));
			label.setStyle("-fx-font-size: 13.5px; -fx-font-weight: 700;");

			node = new HBox(11, iconBox, label);
			node.setAlignment(Pos.CENTER_LEFT);
			node.setPadding(new Insets(10, 12, 10, 12));
			node.getStyleClass().add("nav-item");

			if (badge != null) {
				Label b = Ui.mono(badge, Ui.ACCENT_SOFT, 11);
				b.setPadding(new Insets(1, 7, 1, 7));
				b.setBackground(Ui.fill(Color.web("#2a1d3a"), 999));
				node.getChildren().addAll(Ui.spacer(), b);
			}
		}

		void setActive(boolean active) {
			iconBox.getChildren().setAll(iconFactory.apply(active ? Ui.ACCENT : Ui.TEXT_MID));
			label.setTextFill(active ? Ui.TEXT_HI : Color.web("#c7bfd6"));
			if (active) {
				if (!node.getStyleClass().contains("active")) {
					node.getStyleClass().add("active");
				}
			} else {
				node.getStyleClass().remove("active");
			}
		}
	}
}
