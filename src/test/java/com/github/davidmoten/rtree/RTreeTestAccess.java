package com.github.davidmoten.rtree;

import java.util.Optional;

import com.github.davidmoten.rtree.geometry.Geometry;

/**
 * Package-private access bridge for the state-machine mutation tests, which
 * live in a sub-package and therefore cannot call package-private members of
 * {@link RTree}. Test-only; not part of the public API.
 */
public final class RTreeTestAccess {

    private RTreeTestAccess() {
        // no instances
    }

    /** Builds a tree with a custom (possibly mutated) node factory. */
    public static <T, S extends Geometry> RTree<T, S> create(int size,
            Optional<? extends Node<T, S>> root, Context<T, S> context) {
        return RTree.create(root, size, context);
    }

    /** Delete that deliberately skips root single-child contraction. */
    public static <T, S extends Geometry> RTree<T, S> deleteWithoutRootContraction(
            RTree<T, S> tree, T value, S geometry, boolean all) {
        return tree.deleteWithoutRootContraction(value, geometry, all);
    }
}
