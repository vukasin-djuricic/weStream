package ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;

/**
 * Minimal line icons (stroke, no fill) for the sidebar nav and title bar, drawn
 * with basic JavaFX shapes so no icon-font dependency is needed. Each returns an
 * ~18px {@link Group} stroked in the given colour, matching the thin-line style of
 * the design handoff.
 */
final class Icons {

	private Icons() {
	}

	private static final double SW = 1.6;

	static Node home(Paint c) {
		SVGPath roof = stroke(new SVGPath(), c);
		roof.setContent("M2 9 L10 2.5 L18 9");
		SVGPath box = stroke(new SVGPath(), c);
		box.setContent("M4 8 V17 H16 V8");
		return new Group(roof, box);
	}

	static Node nowPlaying(Paint c) {
		Circle ring = ring(9, c);
		ring.setCenterX(10);
		ring.setCenterY(10);
		Polygon tri = new Polygon(7.5, 6.0, 7.5, 14.0, 14.0, 10.0);
		tri.setFill(c);
		return new Group(ring, tri);
	}

	static Node swarm(Paint c) {
		// three peers + links — the "people / mesh" glyph
		Circle a = dot(10, 3.5, 2.4, c);
		Circle b = dot(4, 15, 2.4, c);
		Circle d = dot(16, 15, 2.4, c);
		Line l1 = link(10, 3.5, 4, 15, c);
		Line l2 = link(10, 3.5, 16, 15, c);
		Line l3 = link(4, 15, 16, 15, c);
		return new Group(l1, l2, l3, a, b, d);
	}

	static Node addStream(Paint c) {
		Circle ring = ring(9, c);
		ring.setCenterX(10);
		ring.setCenterY(10);
		Line h = stroke(new Line(6, 10, 14, 10), c);
		Line v = stroke(new Line(10, 6, 10, 14), c);
		return new Group(ring, h, v);
	}

	static Node dht(Paint c) {
		Circle outer = ring(8.5, c);
		outer.setCenterX(10);
		outer.setCenterY(10);
		Circle inner = ring(4, c);
		inner.setCenterX(10);
		inner.setCenterY(10);
		Circle core = dot(10, 10, 1.6, c);
		core.setFill(c);
		return new Group(outer, inner, core);
	}

	// ---- title-bar window controls ----

	static Node expand(Paint c) {
		SVGPath p = stroke(new SVGPath(), c);
		p.setContent("M3 8 V3 H8 M14 3 H19 V8 M19 14 V19 H14 M8 19 H3 V14");
		return new Group(p);
	}

	static Node minimize(Paint c) {
		return new Group(stroke(new Line(3, 11, 16, 11), c));
	}

	static Node close(Paint c) {
		Line a = stroke(new Line(4, 4, 15, 15), c);
		Line b = stroke(new Line(15, 4, 4, 15), c);
		return new Group(a, b);
	}

	// ----------------------------------------------------------------- helpers

	private static Circle ring(double r, Paint c) {
		Circle ci = new Circle(r);
		ci.setFill(Color.TRANSPARENT);
		ci.setStroke(c);
		ci.setStrokeWidth(SW);
		return ci;
	}

	private static Circle dot(double x, double y, double r, Paint c) {
		Circle ci = new Circle(x, y, r);
		ci.setFill(Color.TRANSPARENT);
		ci.setStroke(c);
		ci.setStrokeWidth(SW);
		return ci;
	}

	private static Line link(double x1, double y1, double x2, double y2, Paint c) {
		Line l = new Line(x1, y1, x2, y2);
		l.setStroke(Color.web(toRgb(c), 0.45));
		l.setStrokeWidth(1.2);
		return l;
	}

	private static <T extends javafx.scene.shape.Shape> T stroke(T shape, Paint c) {
		shape.setFill(Color.TRANSPARENT);
		shape.setStroke(c);
		shape.setStrokeWidth(SW);
		shape.setStrokeLineCap(StrokeLineCap.ROUND);
		return shape;
	}

	private static String toRgb(Paint c) {
		if (c instanceof Color col) {
			return String.format("#%02x%02x%02x",
					(int) (col.getRed() * 255), (int) (col.getGreen() * 255), (int) (col.getBlue() * 255));
		}
		return "#b3aac0";
	}
}
