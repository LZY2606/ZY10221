package com.github.davidmoten.rtree.statemachine;

import java.util.ArrayList;
import java.util.List;

import com.github.davidmoten.rtree.Entry;
import com.github.davidmoten.rtree.Leaf;
import com.github.davidmoten.rtree.Node;
import com.github.davidmoten.rtree.NonLeaf;
import com.github.davidmoten.rtree.RTree;
import com.github.davidmoten.rtree.geometry.Geometry;
import com.github.davidmoten.rtree.geometry.Rectangle;
import com.github.davidmoten.rtree.statemachine.RefGeometry.Rect;

/**
 * Structural assertions applied to every version (and revisited snapshot) of
 * the tree during state-machine runs.
 *
 * <p>
 * The checked invariants are:
 * <ul>
 * <li>an empty tree has no root and height zero; a non-empty tree has a root
 * whose type agrees with its height (single entry set is a leaf root),</li>
 * <li>every non-root internal node has between {@code minChildren} and
 * {@code maxChildren} children (root may have as few as one child),</li>
 * <li>every node's geometry is the minimal axis-aligned bounding rectangle of
 * its immediate children's geometries (tight MBR; recomputed after delete),</li>
 * <li>the number of leaf entries reachable equals {@code size()}.</li>
 * </ul>
 */
final class TreeInvariants {

    static final double TOLERANCE = 1.0e-9;

    private TreeInvariants() {
        // no instances
    }

    static void assertInvariants(String context, RTree<String, Geometry> tree, int expectedSize) {
        if (expectedSize == 0) {
            assertTrue(context + ": empty tree must report empty", tree.isEmpty());
            assertTrue(context + ": empty tree must have absent root",
                    !tree.root().isPresent());
            assertEquals(context + ": empty tree height", 0, tree.calculateDepth());
            return;
        }
        assertTrue(context + ": non-empty tree must have a root", tree.root().isPresent());
        assertEquals(context + ": size()", expectedSize, tree.size());
        Node<String, Geometry> root = tree.root().get();
        int leafEntries = checkNode(context, root, true, tree.context().minChildren(),
                tree.context().maxChildren());
        assertEquals(context + ": reachable leaf entries vs size()", expectedSize, leafEntries);

        // height/root-type agreement: height 1 iff root is a leaf
        if (root instanceof Leaf) {
            assertEquals(context + ": leaf-root height", 1, tree.calculateDepth());
        } else {
            assertTrue(context + ": non-leaf root height must exceed 1, was "
                    + tree.calculateDepth(), tree.calculateDepth() > 1);
        }
    }

    /**
     * Checks one node and returns the number of leaf entries in its subtree.
     */
    private static int checkNode(String context, Node<String, Geometry> node, boolean isRoot,
            int minChildren, int maxChildren) {
        Rectangle nodeMbr = node.geometry().mbr();
        if (node instanceof Leaf) {
            Leaf<String, Geometry> leaf = (Leaf<String, Geometry>) node;
            assertTrue(context + ": leaf must be non-empty", leaf.count() > 0);
            assertTrue(context + ": leaf children <= max (" + leaf.count() + " > "
                    + maxChildren + ")", leaf.count() <= maxChildren);
            checkMbr(context, "leaf", nodeMbr, leafEntries(leaf));
            return leaf.count();
        }
        NonLeaf<String, Geometry> nonLeaf = (NonLeaf<String, Geometry>) node;
        int count = nonLeaf.count();
        if (isRoot) {
            // A non-leaf root with exactly one non-leaf child adds height
            // without adding branching and must be contracted away; only a
            // single leaf child is legitimate immediately after the last
            // split-promoted level collapses.
            assertTrue(context + ": non-leaf root must not have a single "
                    + "non-leaf child (uncontracted redundant height)",
                    !(count == 1 && !(nonLeaf.child(0) instanceof Leaf)));
        }
        if (!isRoot) {
            assertTrue(context + ": non-root internal node must have >= minChildren (" + count
                    + " < " + minChildren + ")", count >= minChildren);
        } else {
            assertTrue(context + ": root internal node must be non-empty", count >= 1);
        }
        assertTrue(context + ": internal node children <= maxChildren (" + count + " > "
                + maxChildren + ")", count <= maxChildren);
        List<Rect> childMbrs = new ArrayList<Rect>();
        int leafEntries = 0;
        for (int i = 0; i < count; i++) {
            Node<String, Geometry> child = nonLeaf.child(i);
            childMbrs.add(toRect(child.geometry().mbr()));
            leafEntries += checkNode(context, child, false, minChildren, maxChildren);
        }
        checkMbr(context, isRoot ? "root" : "internal", nodeMbr, childMbrs);
        return leafEntries;
    }

    private static List<Rect> leafEntries(Leaf<String, Geometry> leaf) {
        List<Rect> rects = new ArrayList<Rect>();
        for (Entry<String, Geometry> entry : leaf.entries()) {
            rects.add(toRect(entry.geometry().mbr()));
        }
        return rects;
    }

    /** Asserts the node MBR is exactly the union of the child MBRs. */
    private static void checkMbr(String context, String kind, Rectangle actual, List<Rect> children) {
        assertTrue(context + ": " + kind + " MBR needs at least one child", !children.isEmpty());
        Rect expected = children.get(0);
        for (int i = 1; i < children.size(); i++) {
            expected = RefGeometry.union(expected, children.get(i));
        }
        Rect got = toRect(actual);
        assertEqualsRect(context + ": " + kind + " MBR x1", expected.x1, got.x1);
        assertEqualsRect(context + ": " + kind + " MBR y1", expected.y1, got.y1);
        assertEqualsRect(context + ": " + kind + " MBR x2", expected.x2, got.x2);
        assertEqualsRect(context + ": " + kind + " MBR y2", expected.y2, got.y2);
    }

    private static Rect toRect(Rectangle r) {
        return new Rect(r.x1(), r.y1(), r.x2(), r.y2());
    }

    private static void assertEqualsRect(String message, double expected, double actual) {
        if (Math.abs(expected - actual) > TOLERANCE) {
            throw new AssertionError(message + " expected:<" + expected + "> but was:<"
                    + actual + ">");
        }
    }

    private static void assertTrue(String message, boolean condition) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(String message, int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError(message + " expected:<" + expected + "> but was:<"
                    + actual + ">");
        }
    }
}
