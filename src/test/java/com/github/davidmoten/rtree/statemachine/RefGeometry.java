package com.github.davidmoten.rtree.statemachine;

/**
 * Independent axis-aligned rectangle maths used by the state-machine reference
 * model. It deliberately does not call the production geometry implementation
 * so that the oracle cannot accidentally inherit a production bug.
 *
 * <p>
 * Coordinates come from a small integral grid and are stored as doubles; the
 * production tree is exercised with double-precision geometries so MBR
 * comparisons are exact. Equality of borders counts as an intersection
 * (geometries may merely touch at an edge).
 */
final class RefGeometry {

    private RefGeometry() {
        // no instances
    }

    /** Axis-aligned rectangle on the test grid. */
    static final class Rect {
        final double x1;
        final double y1;
        final double x2;
        final double y2;

        Rect(double x1, double y1, double x2, double y2) {
            if (x2 < x1 || y2 < y1) {
                throw new IllegalArgumentException("inverted rectangle");
            }
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
        }

        static Rect point(double x, double y) {
            return new Rect(x, y, x, y);
        }

        boolean isPoint() {
            return x1 == x2 && y1 == y2;
        }

        @Override
        public String toString() {
            if (isPoint()) {
                return "p(" + fmt(x1) + "," + fmt(y1) + ")";
            }
            return "r(" + fmt(x1) + "," + fmt(y1) + "|" + fmt(x2) + "," + fmt(y2) + ")";
        }

        private static String fmt(double d) {
            return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
        }

        @Override
        public int hashCode() {
            long bits = Double.doubleToLongBits(x1);
            int result = (int) (bits ^ (bits >>> 32));
            result = 31 * result + (int) (Double.doubleToLongBits(y1)
                    ^ (Double.doubleToLongBits(y1) >>> 32));
            result = 31 * result + (int) (Double.doubleToLongBits(x2)
                    ^ (Double.doubleToLongBits(x2) >>> 32));
            result = 31 * result + (int) (Double.doubleToLongBits(y2)
                    ^ (Double.doubleToLongBits(y2) >>> 32));
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof Rect)) {
                return false;
            }
            Rect other = (Rect) obj;
            return Double.compare(x1, other.x1) == 0 && Double.compare(y1, other.y1) == 0
                    && Double.compare(x2, other.x2) == 0 && Double.compare(y2, other.y2) == 0;
        }
    }

    /** Closed-interval intersection; touching borders intersect. */
    static boolean intersects(Rect a, Rect b) {
        return a.x1 <= b.x2 && b.x1 <= a.x2 && a.y1 <= b.y2 && b.y1 <= a.y2;
    }

    /** Euclidean gap between two rectangles; zero when they intersect/touch. */
    static double distance(Rect a, Rect b) {
        double dx = gap(a.x1, a.x2, b.x1, b.x2);
        double dy = gap(a.y1, a.y2, b.y1, b.y2);
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static double gap(double a1, double a2, double b1, double b2) {
        if (a2 < b1) {
            return b1 - a2;
        }
        if (b2 < a1) {
            return a1 - b2;
        }
        return 0;
    }

    /** Minimal enclosing rectangle of two rectangles. */
    static Rect union(Rect a, Rect b) {
        return new Rect(Math.min(a.x1, b.x1), Math.min(a.y1, b.y1),
                Math.max(a.x2, b.x2), Math.max(a.y2, b.y2));
    }
}
