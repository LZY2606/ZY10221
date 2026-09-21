package com.github.davidmoten.rtree.statemachine;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import com.github.davidmoten.rtree.geometry.Geometries;

/**
 * Characterizes the existing geometry contract for non-finite coordinates.
 *
 * <p>
 * These tests deliberately pin down the behaviour the library already provides
 * rather than inventing new semantics: the reference model and the state
 * machine use finite grid coordinates, so NaN/Infinity handling is exercised
 * here in isolation and is not delegated to the oracle.
 * <ul>
 * <li>an inverted rectangle (x2 &lt; x1 or y2 &lt; y1), which includes NaN
 * coordinates because every NaN comparison is false, is rejected,</li>
 * <li>an infinite rectangle bound is accepted (no finite-range precondition),</li>
 * <li>a point carries no coordinate precondition and accepts non-finite values,</li>
 * <li>a circle with a NaN radius is rejected.</li>
 * </ul>
 */
public class GeometryNumericContractTest {

    @Test
    public void rectangleWithNaNCoordinateIsRejected() {
        assertRectangleRejected(Double.NaN, 0, 1, 1);
        assertRectangleRejected(0, Double.NaN, 1, 1);
        assertRectangleRejected(0, 0, Double.NaN, 1);
        assertRectangleRejected(0, 0, 1, Double.NaN);
    }

    @Test
    public void invertedRectangleIsRejected() {
        assertRectangleRejected(1, 0, 0, 1);
        assertRectangleRejected(0, 1, 1, 0);
    }

    @Test
    public void infiniteRectangleBoundsAreAcceptedByCurrentContract() {
        Geometries.rectangle(Double.NEGATIVE_INFINITY, 0, 0, 1);
        Geometries.rectangle(0, 0, Double.POSITIVE_INFINITY, 1);
    }

    @Test
    public void pointAcceptsNonFiniteCoordinatesByCurrentContract() {
        // points impose no range precondition; this pins existing behaviour
        Geometries.point(Double.NaN, Double.POSITIVE_INFINITY);
        Geometries.point(Double.NEGATIVE_INFINITY, Double.NaN);
    }

    @Test
    public void circleWithNaNRadiusIsRejected() {
        try {
            Geometries.circle(0, 0, Double.NaN);
            fail("expected IllegalArgumentException for NaN circle radius");
        } catch (IllegalArgumentException expected) {
            assertTrue(true);
        }
    }

    private static void assertRectangleRejected(double x1, double y1, double x2, double y2) {
        try {
            Geometries.rectangle(x1, y1, x2, y2);
            fail("expected IllegalArgumentException for rectangle (" + x1 + "," + y1 + "|" + x2
                    + "," + y2 + ")");
        } catch (IllegalArgumentException expected) {
            assertTrue(true);
        }
    }
}
