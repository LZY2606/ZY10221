package com.github.davidmoten.rtree.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.github.davidmoten.rtree.Context;
import com.github.davidmoten.rtree.Entry;
import com.github.davidmoten.rtree.Leaf;
import com.github.davidmoten.rtree.Node;
import com.github.davidmoten.rtree.NonLeaf;
import com.github.davidmoten.rtree.geometry.Geometry;
import com.github.davidmoten.rtree.geometry.ListPair;

import rx.Subscriber;
import rx.functions.Func1;

import static java.util.Optional.of;

public final class NonLeafHelper {

    private NonLeafHelper() {
        // prevent instantiation
    }

    public static <T, S extends Geometry> void search(Func1<? super Geometry, Boolean> criterion,
            Subscriber<? super Entry<T, S>> subscriber, NonLeaf<T, S> node) {
        if (!criterion.call(node.geometry().mbr()))
            return;

        int numChildren = node.count();
        for (int i = 0; i < numChildren; i++) {
            if (subscriber.isUnsubscribed()) {
                return;
            } else {
                Node<T, S> child = node.child(i);
                child.searchWithoutBackpressure(criterion, subscriber);
            }
        }
    }

    public static <T, S extends Geometry> List<Node<T, S>> add(
            Entry<? extends T, ? extends S> entry, NonLeaf<T, S> node) {
        Context<T, S> context = node.context();
        List<Node<T, S>> children = node.children();
        final Node<T, S> child = context.selector().select(entry.geometry().mbr(), children);
        List<Node<T, S>> list = child.add(entry);
        List<? extends Node<T, S>> children2 = Util.replace(children, child, list);
        if (children2.size() <= context.maxChildren())
            return Collections.singletonList(
                    (Node<T, S>) context.factory().createNonLeaf(children2, context));
        else {
            ListPair<? extends Node<T, S>> pair = context.splitter().split(children2,
                    context.minChildren());
            return makeNonLeaves(pair, context);
        }
    }

    private static <T, S extends Geometry> List<Node<T, S>> makeNonLeaves(
            ListPair<? extends Node<T, S>> pair, Context<T, S> context) {
        List<Node<T, S>> list = new ArrayList<Node<T, S>>();
        list.add(context.factory().createNonLeaf(pair.group1().list(), context));
        list.add(context.factory().createNonLeaf(pair.group2().list(), context));
        return list;
    }

    /**
     * Result of deleting an entry from a non-leaf node, richer than
     * {@link NodeAndEntries} for internal use while the result bubbles up
     * through the recursion.
     *
     * <p>
     * The recursive walk retains the per-child outcome so that, when this node
     * itself dissolves, the surviving leaf entries of its whole subtree can be
     * assembled without ambiguity: a surviving child contributes all of its
     * leaf entries (from the replacement node), while a dissolved child
     * contributes the survivors it itself reported. This structural split
     * never compares entries by value and therefore treats repeated values
     * with identical geometries as distinct entries.
     */
    private static final class DeleteResult<T, S extends Geometry> {

        /** Replacement node, or absent when this node dissolved. */
        Optional<Node<T, S>> node;

        /** Surviving entries released for root-level reinsertion. */
        final List<Entry<T, S>> released = new ArrayList<Entry<T, S>>();

        int countDeleted;
    }

    public static <T, S extends Geometry> NodeAndEntries<T, S> delete(
            Entry<? extends T, ? extends S> entry, boolean all, NonLeaf<T, S> node) {
        DeleteResult<T, S> result = deleteRecursive(entry, all, node);
        return new NodeAndEntries<T, S>(result.node, result.released, result.countDeleted);
    }

    private static <T, S extends Geometry> DeleteResult<T, S> deleteRecursive(
            Entry<? extends T, ? extends S> entry, boolean all, NonLeaf<T, S> node) {
        DeleteResult<T, S> outcome = new DeleteResult<T, S>();

        // Replacement (or untouched) children retained by this node after the
        // delete. A child with an absent result dissolved and is omitted.
        List<Node<T, S>> remainingChildren = new ArrayList<Node<T, S>>();
        boolean changed = false;

        List<? extends Node<T, S>> children = node.children();
        for (int c = 0; c < children.size(); c++) {
            Node<T, S> child = children.get(c);
            if (changed) {
                // all == false: the first subtree containing the entry was
                // already processed; every later child is untouched.
                remainingChildren.add(child);
                continue;
            }
            if (!entry.geometry().intersects(child.geometry().mbr())) {
                // the entry cannot be in this child; it is untouched
                remainingChildren.add(child);
                continue;
            }

            boolean childChanged;
            if (child instanceof Leaf) {
                NodeAndEntries<T, S> r = child.delete(entry, all);
                applyLeafResult(r, outcome, remainingChildren);
                childChanged = !r.node().isPresent() || r.node().get() != child;
            } else {
                DeleteResult<T, S> r = deleteRecursive(entry, all,
                        (NonLeaf<T, S>) child);
                applyNonLeafResult(r, outcome, remainingChildren);
                childChanged = !r.node.isPresent() || r.node.get() != child;
            }
            if (childChanged) {
                changed = true;
            }
            if (changed && !all) {
                continue;
            }
        }

        if (!changed) {
            outcome.node = of(node);
            outcome.released.clear();
            outcome.countDeleted = 0;
            return outcome;
        }

        if (remainingChildren.size() < node.context().minChildren()) {
            // This node underflows and dissolves. Entries released by dissolved
            // descendants are already in outcome.released; add the full leaf
            // contents of the surviving children so that the parent receives
            // every surviving leaf entry of this subtree once.
            for (Node<T, S> survivor : remainingChildren) {
                collectLeafEntries(survivor, outcome.released);
            }
            outcome.node = Optional.empty();
            return outcome;
        }

        outcome.node = of(node.context().factory().createNonLeaf(remainingChildren,
                node.context()));
        return outcome;
    }

    /**
     * Collects all leaf entries of the subtree rooted at {@code node}.
     *
     * @param node
     *            subtree root
     * @param entries
     *            accumulator receiving the leaf entries
     */
    static <T, S extends Geometry> void collectLeafEntries(Node<T, S> node,
            List<Entry<T, S>> entries) {
        if (node instanceof Leaf) {
            entries.addAll(((Leaf<T, S>) node).entries());
        } else {
            NonLeaf<T, S> nonLeaf = (NonLeaf<T, S>) node;
            for (int i = 0; i < nonLeaf.count(); i++) {
                collectLeafEntries(nonLeaf.child(i), entries);
            }
        }
    }

    private static <T, S extends Geometry> void applyLeafResult(
            NodeAndEntries<T, S> result, DeleteResult<T, S> outcome,
            List<Node<T, S>> remainingChildren) {
        outcome.countDeleted += result.countDeleted();
        if (result.node().isPresent()) {
            remainingChildren.add(result.node().get());
            outcome.released.addAll(result.entriesToAdd());
        } else {
            // leaf dissolved; its surviving entries are queued for reinsertion
            outcome.released.addAll(result.entriesToAdd());
        }
    }

    private static <T, S extends Geometry> void applyNonLeafResult(
            DeleteResult<T, S> result, DeleteResult<T, S> outcome,
            List<Node<T, S>> remainingChildren) {
        outcome.countDeleted += result.countDeleted;
        if (result.node.isPresent()) {
            // child survived (possibly replaced by a smaller node); it keeps
            // every leaf entry of its subtree, so only entries it released
            // from dissolved descendants are forwarded for reinsertion
            remainingChildren.add(result.node.get());
            outcome.released.addAll(result.released);
        } else {
            // child dissolved; the complete survivor set of its subtree is
            // already assembled in result.released
            outcome.released.addAll(result.released);
        }
    }

}
