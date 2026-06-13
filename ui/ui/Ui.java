package ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.BorderStrokeStyle;
import javafx.scene.layout.BorderWidths;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

/**
 * Small factory of styled JavaFX nodes for the Phase-5 player, so screen code
 * reads like layout, not CSS plumbing. Visuals come from Direction A "Midnight
 * Neon" — values mirror {@code design_handoff_phase5_player/DESIGN_TOKENS.md} and
 * the looked-up colors in {@code theme.css}. Engine/numeric text uses the
 * {@code .mono} (JetBrains Mono) class; everything else is Manrope.
 */
final class Ui {

	private Ui() {
	}

	// ---- design tokens (mirror DESIGN_TOKENS.md; kept here for code that needs Color) ----
	static final Color BG_APP      = Color.web("#0f0d15");
	static final Color BG_CHROME   = Color.web("#141019");
	static final Color SURFACE     = Color.web("#15111d");
	static final Color SURFACE_IN  = Color.web("#100d17");
	static final Color BORDER      = Color.web("#221d2c");
	static final Color BORDER_STRONG = Color.web("#2c2638");
	static final Color TEXT_HI     = Color.web("#f4f1f8");
	static final Color TEXT        = Color.web("#e7e1ef");
	static final Color TEXT_MID    = Color.web("#b3aac0");
	static final Color TEXT_SOFT   = Color.web("#8b8299");
	static final Color TEXT_LO     = Color.web("#756c85");
	static final Color TEXT_DIM    = Color.web("#5f5670");
	static final Color ACCENT      = Color.web("#c64ff0");
	static final Color ACCENT_DEEP = Color.web("#9b3ec9");
	static final Color ACCENT_SOFT = Color.web("#c08fe8");
	static final Color CYAN        = Color.web("#6cc8e8");
	static final Color PINK        = Color.web("#ee7fb0");
	static final Color GREEN       = Color.web("#46d39a");
	static final Color GREEN_TEXT  = Color.web("#74e3b0");
	static final Color AMBER       = Color.web("#f4bf4f");
	static final Color RED         = Color.web("#f0795e");

	// ---------------------------------------------------------------- text

	static Label h1(String text) {
		Label l = new Label(text);
		l.getStyleClass().add("h1");
		return l;
	}

	static Label h2(String text) {
		Label l = new Label(text);
		l.getStyleClass().add("h2");
		return l;
	}

	static Label subtitle(String text) {
		Label l = new Label(text);
		l.getStyleClass().add("subtitle");
		return l;
	}

	/**
	 * Caps caption (section/stat captions): JetBrains Mono, dim, uppercased. NOTE:
	 * the design's 0.06em letter-spacing is intentionally NOT applied — JavaFX CSS
	 * has no letter-spacing property, so we keep the caption untracked rather than
	 * set a silently-ignored rule.
	 */
	static Label cap(String text) {
		return cap(text, TEXT_LO);
	}

	/** Caps caption in an explicit color (e.g. accent-soft "CONTINUE WATCHING"). */
	static Label cap(String text, Color fill) {
		Label l = new Label(text.toUpperCase());
		l.getStyleClass().add("label-cap");
		l.setTextFill(fill);
		return l;
	}

	/** Bold Manrope section title (e.g. "Your shares") — NOT a tracked mono caption. */
	static Label sectionTitle(String text) {
		Label l = new Label(text);
		l.setTextFill(TEXT_HI);
		l.setStyle("-fx-font-size: 13px; -fx-font-weight: 800;");
		return l;
	}

	/** Monospace engine data (ids, speeds, hashes, k-bucket labels). */
	static Label mono(String text) {
		Label l = new Label(text);
		l.getStyleClass().add("mono");
		l.setTextFill(TEXT_LO);
		l.setStyle("-fx-font-size: 12px;");
		return l;
	}

	static Label mono(String text, Color fill, double size) {
		Label l = mono(text);
		l.setTextFill(fill);
		l.setStyle("-fx-font-size: " + size + "px;");
		return l;
	}

	static Label body(String text, Color fill) {
		Label l = new Label(text);
		l.setTextFill(fill);
		l.setStyle("-fx-font-size: 13px; -fx-font-weight: 700;");
		return l;
	}

	// ---------------------------------------------------------------- containers

	/** A flat card surface (1px border, radius 14) per the design's `.ws-card`. */
	static VBox card() {
		VBox v = new VBox();
		v.getStyleClass().add("ws-card");
		v.setPadding(new Insets(18));
		v.setSpacing(14);
		return v;
	}

	static HBox row(double spacing, Pos align, javafx.scene.Node... children) {
		HBox h = new HBox(spacing, children);
		h.setAlignment(align);
		return h;
	}

	static Region spacer() {
		Region r = new Region();
		HBox.setHgrow(r, javafx.scene.layout.Priority.ALWAYS);
		return r;
	}

	static Region vspacer() {
		Region r = new Region();
		VBox.setVgrow(r, javafx.scene.layout.Priority.ALWAYS);
		return r;
	}

	// ---------------------------------------------------------------- pills

	static Label pillGreen(String text) {
		Label l = pill(text);
		l.getStyleClass().add("pill-green");
		return l;
	}

	static Label pillAccent(String text) {
		Label l = pill(text);
		l.getStyleClass().add("pill-accent");
		return l;
	}

	static Label pill(String text) {
		Label l = new Label(text);
		l.getStyleClass().add("pill");
		return l;
	}

	// ---------------------------------------------------------------- raw fills

	/** Background helper for code-built panels (looked-up colors aren't available off-CSS). */
	static Background fill(Color c, double radius) {
		return new Background(new BackgroundFill(c, new CornerRadii(radius), Insets.EMPTY));
	}

	static Border line(Color c, double radius) {
		return new Border(new BorderStroke(c, BorderStrokeStyle.SOLID,
				new CornerRadii(radius), new BorderWidths(1)));
	}
}
