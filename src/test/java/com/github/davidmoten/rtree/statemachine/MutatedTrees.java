package com.github.davidmoten.rtree.statemachine;

import java.util.List;

import com.github.davidmoten.rtree.Context;
import com.github.davidmoten.rtree.Entry;
import com.github.davidmoten.rtree.Factory;
import com.github.davidmoten.rtree.Leaf;
import com.github.davidmoten.rtree.Node;
import com.github.davidmoten.rtree.NonLeaf;
import com.github.davidmoten.rtree.Selector;
import com.github.davidmoten.rtree.SelectorMinimalAreaIncrease;
import com.github.davidmoten.rtree.SelectorRStar;
import com.github.davidmoten.rtree.Splitter;
import com.github.davidmoten.rtree.SplitterQuadratic;
import com.github.davidmoten.rtree.SplitterRStar;
import com.github.davidmoten.rtree.geometry.Geometries;
import com.github.davidmoten.rtree.geometry.Geometry;
import com.github.davidmoten.rtree.geometry.Rectangle;
import com.github.davidmoten.rtree.internal.NodeAndEntries;

import rx.Subscriber;
import rx.functions.Func1;

/**
 * Test-only deliberately mutated node factory used to prove the state-machine
 * tests catch a tree that skips MBR recomputation after delete.
 *
 * <p>
 * While {@link #staleMbrArmed} is set, every leaf/non-leaf produced by this
 * factory reports an MBR one unit larger than the tight bounding rectangle of
 * its children. The factory is armed only across a delete (not an add) in the
 * mutation test, isolating the defect to the post-delete rebuild path. The
 * mutation lives entirely in test code; production is untouched.
 */
final class MutatedTrees {

    private MutatedTrees() {
        // no instances
    }

    static volatile boolean staleMbrArmed;

    static Selector selector(boolean star) {
        return star ? new SelectorRStar() : new SelectorMinimalAreaIncrease();
    }

    static Splitter splitter(boolean star) {
        return star ? new SplitterRStar() : new SplitterQuadratic();
    }

    static void armStaleMbrOnDelete() {
        staleMbrArmed = true;
    }

    static void disarm() {
        staleMbrArmed = false;
    }

    static Context<String, Geometry> context(int minChildren, int maxChildren, boolean star,
            Factory<String, Geometry> factory) {
        return new Context<String, Geometry>(minChildren, maxChildren, selector(star),
                splitter(star), factory);
    }

    static Rectangle enlarge(Rectangle r) {
        return Geometries.rectangle(r.x1() - 1.0, r.y1() - 1.0, r.x2() + 1.0, r.y2() + 1.0);
    }

    /** Factory that reports deliberately stale MBRs while armed. */
    static final class StaleMbrFactory implements Factory<String, Geometry> {

        @Override
        public Leaf<String, Geometry> createLeaf(List<Entry<String, Geometry>> entries,
                Context<String, Geometry> context) {
            Leaf<String, Geometry> leaf = contextFactory(context).createLeaf(entries, context);
            return staleMbrArmed ? new StaleMbrLeaf(leaf) : leaf;
        }

        @Override
        public NonLeaf<String, Geometry> createNonLeaf(
                List<? extends Node<String, Geometry>> children, Context<String, Geometry> context) {
            NonLeaf<String, Geometry> nonLeaf = contextFactory(context)
                    .createNonLeaf(children, context);
            return staleMbrArmed ? new StaleMbrNonLeaf(nonLeaf) : nonLeaf;
        }

        @SuppressWarnings("unchecked")
        private Factory<String, Geometry> contextFactory(Context<String, Geometry> context) {
            return (Factory<String, Geometry>) (Factory<?, ?>) FactoriesHolder.DEFAULT;
        }

        @Override
        public Entry<String, Geometry> createEntry(String value, Geometry geometry) {
            return com.github.davidmoten.rtree.Entries.entry(value, geometry);
        }
    }

    /** Indirection to avoid an initialization cycle in the nested factory. */
    private static final class FactoriesHolder {
        static final Factory<String, Geometry> DEFAULT = com.github.davidmoten.rtree.Factories
                .defaultFactory();
    }

    /** Wraps a leaf and lies about its MBR. */
    static final class StaleMbrLeaf implements Leaf<String, Geometry> {

        private final Leaf<String, Geometry> delegate;
        private final Rectangle stale;

        StaleMbrLeaf(Leaf<String, Geometry> delegate) {
            this.delegate = delegate;
            this.stale = enlarge(delegate.geometry().mbr());
        }

        @Override
        public Geometry geometry() {
            return stale;
        }

        @Override
        public List<Entry<String, Geometry>> entries() {
            return delegate.entries();
        }

        @Override
        public Entry<String, Geometry> entry(int i) {
            return delegate.entry(i);
        }

        @Override
        public int count() {
            return delegate.count();
        }

        @Override
        public Context<String, Geometry> context() {
            return delegate.context();
        }

        @Override
        public List<Node<String, Geometry>> add(
                Entry<? extends String, ? extends Geometry> entry) {
            return delegate.add(entry);
        }

        @Override
        public NodeAndEntries<String, Geometry> delete(
                Entry<? extends String, ? extends Geometry> entry, boolean all) {
            return delegate.delete(entry, all);
        }

        @Override
        public void searchWithoutBackpressure(Func1<? super Geometry, Boolean> condition,
                Subscriber<? super Entry<String, Geometry>> subscriber) {
            delegate.searchWithoutBackpressure(condition, subscriber);
        }
    }

    /** Wraps a non-leaf and lies about its MBR. */
    static final class StaleMbrNonLeaf implements NonLeaf<String, Geometry> {

        private final NonLeaf<String, Geometry> delegate;
        private final Rectangle stale;

        StaleMbrNonLeaf(NonLeaf<String, Geometry> delegate) {
            this.delegate = delegate;
            this.stale = enlarge(delegate.geometry().mbr());
        }

        @Override
        public Geometry geometry() {
            return stale;
        }

        @Override
        public int count() {
            return delegate.count();
        }

        @Override
        public Node<String, Geometry> child(int i) {
            return delegate.child(i);
        }

        @Override
        public List<Node<String, Geometry>> children() {
            return delegate.children();
        }

        @Override
        public Context<String, Geometry> context() {
            return delegate.context();
        }

        @Override
        public List<Node<String, Geometry>> add(
                Entry<? extends String, ? extends Geometry> entry) {
            return delegate.add(entry);
        }

        @Override
        public NodeAndEntries<String, Geometry> delete(
                Entry<? extends String, ? extends Geometry> entry, boolean all) {
            return delegate.delete(entry, all);
        }

        @Override
        public void searchWithoutBackpressure(Func1<? super Geometry, Boolean> condition,
                Subscriber<? super Entry<String, Geometry>> subscriber) {
            delegate.searchWithoutBackpressure(condition, subscriber);
        }
    }

}
