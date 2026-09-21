package com.github.davidmoten.rtree.statemachine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.github.davidmoten.rtree.Entry;
import com.github.davidmoten.rtree.InternalStructure;
import com.github.davidmoten.rtree.RTree;
import com.github.davidmoten.rtree.Serializer;
import com.github.davidmoten.rtree.Serializers;
import com.github.davidmoten.rtree.geometry.Geometries;
import com.github.davidmoten.rtree.geometry.Geometry;
import com.github.davidmoten.rtree.geometry.Rectangle;
import com.github.davidmoten.rtree.statemachine.RefGeometry.Rect;
import com.github.davidmoten.rtree.statemachine.RefModel.Item;
import com.github.davidmoten.rtree.statemachine.RefModel.ItemDistance;

import rx.Observable;

/**
 * Executes a fixed action history against a real {@link RTree} and an
 * independent {@link RefModel}, verifying agreement after every step.
 *
 * <p>
 * Every <em>distinct tree version</em> produced by a mutating action is
 * retained in {@code versions} together with its reference multiset; earlier
 * snapshots are revisited at random each step to prove persistence (a later
 * mutation must never affect an old version). Query-only actions do not create
 * versions.
 */
final class StateMachineRunner {

    /** A retained historical version and its reference state. */
    static final class Version {
        final RTree<String, Geometry> tree;
        final RefModel ref;
        final int step;

        Version(RTree<String, Geometry> tree, RefModel ref, int step) {
            this.tree = tree;
            this.ref = ref;
            this.step = step;
        }
    }

    final List<Version> versions = new ArrayList<Version>();

    private final Random snapshotRandom;
    private final Config config;
    private final RTree<String, Geometry> seedTree;
    private final boolean skipRootContraction;

    /** Tree configuration under test. */
    enum Config {
        GUTTMAN_DEFAULT("guttman m4/min2", 4, 2, false),
        GUTTMAN_SMALL("guttman m3/min1", 3, 1, false),
        GUTTMAN_MIN2("guttman m5/min2", 5, 2, false),
        STAR("rstar m4", 4, 2, true);

        final String label;
        final int maxChildren;
        final int minChildren;
        final boolean star;

        Config(String label, int maxChildren, int minChildren, boolean star) {
            this.label = label;
            this.maxChildren = maxChildren;
            this.minChildren = minChildren;
            this.star = star;
        }

        RTree<String, Geometry> empty() {
            if (star) {
                return RTree.star().maxChildren(maxChildren).minChildren(minChildren).create();
            }
            return RTree.maxChildren(maxChildren).minChildren(minChildren).create();
        }
    }

    StateMachineRunner(Config config, long snapshotSeed) {
        this(config, snapshotSeed, config.empty(), false);
    }

    StateMachineRunner(Config config, long snapshotSeed, RTree<String, Geometry> seedTree,
            boolean skipRootContraction) {
        this.config = config;
        this.snapshotRandom = new Random(snapshotSeed ^ 0x51e27L);
        this.seedTree = seedTree;
        this.skipRootContraction = skipRootContraction;
    }

    /**
     * Runs the full history and returns the final version. Throws an
     * {@link AssertionError} with replayable context on any disagreement.
     */
    private RTree<String, Geometry> currentTree;
    private RefModel currentRef;

    Version run(long seed, List<Action> actions) {
        currentTree = seedTree;
        currentRef = new RefModel();
        versions.clear();
        versions.add(new Version(currentTree, currentRef.copy(), 0));

        for (int step = 1; step <= actions.size(); step++) {
            Action action = actions.get(step - 1);
            String ctx = context(config.label, seed, actions, step);
            boolean mutates = apply(ctx, action);
            if (mutates) {
                TreeInvariants.assertInvariants(ctx, currentTree, currentRef.size());
                versions.add(new Version(currentTree, currentRef.copy(), step));
            }
            revisitSnapshot(seed, step);
        }
        return versions.get(versions.size() - 1);
    }

    /** Applies one action; returns true if it produced a new tree version. */
    private boolean apply(String ctx, Action action) {
        RTree<String, Geometry> tree = currentTree;
        RefModel ref = currentRef;
        boolean mutates = true;
        if (action instanceof Action.Add) {
            Action.Add add = (Action.Add) action;
            currentTree = tree.add(add.value, toGeometry(add.geometry));
            ref.add(new Item(add.id, add.value, add.geometry));
        } else if (action instanceof Action.Delete) {
            Action.Delete delete = (Action.Delete) action;
            currentTree = delete(tree, delete.value, toGeometry(delete.geometry), delete.all);
            if (delete.all) {
                ref.deleteAll(delete.value, delete.geometry);
            } else {
                ref.deleteOne(delete.value, delete.geometry);
            }
        } else if (action instanceof Action.DeleteAllEntries) {
            for (Item item : ref.items()) {
                tree = delete(tree, item.value, toGeometry(item.geometry), true);
            }
            currentTree = tree;
            ref.clear();
        } else if (action instanceof Action.Search) {
            assertSearchMultiset(ctx, tree, ref, ((Action.Search) action).query);
            mutates = false;
        } else if (action instanceof Action.Nearest) {
            assertNearestMultiset(ctx, tree, ref, (Action.Nearest) action);
            mutates = false;
        } else if (action instanceof Action.Size) {
            assertEquals(ctx + " size()", ref.size(), tree.size());
            mutates = false;
        } else if (action instanceof Action.SerializeRoundTrip) {
            currentTree = roundTrip(ctx, tree, ref);
        } else {
            throw new AssertionError(ctx + ": unknown action " + action);
        }
        return mutates;
    }

    private void assertSearchMultiset(String ctx, RTree<String, Geometry> tree, RefModel ref,
            Rect query) {
        List<Entry<String, Geometry>> oneShot = tree.search(prodRect(query))
                .toList().toBlocking().single();
        List<Entry<String, Geometry>> batched = collectInBatches(tree.search(prodRect(query)));
        assertEntryMultiset(ctx + " search one-shot vs reference", query, ref.search(query),
                oneShot);
        assertEntryMultiset(ctx + " search batched vs reference", query, ref.search(query),
                batched);
        assertSameMultiset(ctx + " search one-shot vs batched request", oneShot, batched);
    }

    private void assertNearestMultiset(String ctx, RTree<String, Geometry> tree, RefModel ref,
            Action.Nearest nearest) {
        Rectangle query = prodRect(nearest.query);
        List<Entry<String, Geometry>> oneShot = tree.nearest(query, nearest.maxDistance,
                nearest.maxCount).toList().toBlocking().single();
        List<Entry<String, Geometry>> batched = collectInBatches(
                tree.nearest(query, nearest.maxDistance, nearest.maxCount));
        RefModel.NearestExpectation expected = ref.nearestExpectation(nearest.query,
                nearest.maxDistance, nearest.maxCount);
        assertNearestFeasible(ctx + " nearest one-shot", expected, oneShot, query);
        assertNearestFeasible(ctx + " nearest batched", expected, batched, query);
        assertSameMultiset(ctx + " nearest one-shot vs batched request", oneShot, batched);
    }

    /**
     * Verifies a nearest result against the feasible answer set. Every entry
     * strictly closer than the cutoff distance must be present; the remaining
     * slots may be filled by any entries exactly at the cutoff distance, in any
     * order, so equal-distance ties impose no ordering. Every returned entry
     * must also report its distance correctly.
     */
    private void assertNearestFeasible(String ctx, RefModel.NearestExpectation expected,
            List<Entry<String, Geometry>> actual, Rectangle query) {
        assertEquals(ctx + ": result count", expected.expectedCount, actual.size());
        List<ItemDistance> mandatory = new ArrayList<ItemDistance>(expected.mandatory);
        List<ItemDistance> optional = new ArrayList<ItemDistance>(expected.atCutoff);
        int optionalUsed = 0;
        for (Entry<String, Geometry> entry : actual) {
            double actualDistance = entry.geometry().distance(query);
            ItemDistance mandatoryMatch = removeMatching(mandatory, entry, actualDistance);
            if (mandatoryMatch != null) {
                assertEquals(ctx + " reported distance for " + entry.value(),
                        mandatoryMatch.distance, actualDistance, 1.0e-9);
                continue;
            }
            ItemDistance optionalMatch = removeMatching(optional, entry, actualDistance);
            if (optionalMatch == null) {
                throw new AssertionError(ctx + ": entry " + entry.value() + " geometry="
                        + entry.geometry() + " distance=" + actualDistance
                        + " is not among the feasible nearest entries; mandatory="
                        + describe(expected.mandatory) + " cutoff="
                        + describe(expected.atCutoff));
            }
            optionalUsed++;
            assertEquals(ctx + " cutoff distance for " + entry.value(),
                    optionalMatch.distance, actualDistance, 1.0e-9);
        }
        if (!mandatory.isEmpty()) {
            throw new AssertionError(ctx + ": missing nearer entries " + describe(mandatory));
        }
        if (optionalUsed != expected.optionalSlots) {
            throw new AssertionError(ctx + ": expected " + expected.optionalSlots
                    + " entries at cutoff distance but got " + optionalUsed);
        }
    }

    private static String describe(List<ItemDistance> pairs) {
        List<String> parts = new ArrayList<String>();
        for (ItemDistance pair : pairs) {
            parts.add(pair.item + "@d=" + pair.distance);
        }
        return parts.toString();
    }

    private ItemDistance removeMatching(List<ItemDistance> remaining,
            Entry<String, Geometry> entry, double actualDistance) {
        for (int i = 0; i < remaining.size(); i++) {
            ItemDistance candidate = remaining.get(i);
            if (candidate.item.value.equals(entry.value())
                    && candidate.item.geometry.equals(fromGeometry(entry.geometry()))
                    && Math.abs(candidate.distance - actualDistance) <= 1.0e-9) {
                return remaining.remove(i);
            }
        }
        return null;
    }


    private void assertEntryMultiset(String ctx, Rect query, List<Item> expected,
            List<Entry<String, Geometry>> actual) {
        if (expected.size() != actual.size()) {
            throw new AssertionError(ctx + " query=" + query + ": expected count "
                    + expected.size() + " but was " + actual.size() + " actual=" + actual);
        }
        List<Item> remaining = new ArrayList<Item>(expected);
        for (Entry<String, Geometry> entry : actual) {
            Item match = null;
            for (Item item : remaining) {
                if (item.value.equals(entry.value())
                        && item.geometry.equals(fromGeometry(entry.geometry()))) {
                    match = item;
                    break;
                }
            }
            if (match == null) {
                throw new AssertionError(ctx + ": unexpected entry value=" + entry.value()
                        + " geometry=" + entry.geometry() + " remaining=" + remaining);
            }
            remaining.remove(match);
        }
    }

    private void assertSameMultiset(String ctx, List<Entry<String, Geometry>> a,
            List<Entry<String, Geometry>> b) {
        if (a.size() != b.size()) {
            throw new AssertionError(ctx + ": counts differ " + a.size() + " vs " + b.size());
        }
        List<Entry<String, Geometry>> remaining = new ArrayList<Entry<String, Geometry>>(b);
        for (Entry<String, Geometry> entry : a) {
            Entry<String, Geometry> match = null;
            for (Entry<String, Geometry> candidate : remaining) {
                if (sameEntry(entry, candidate)) {
                    match = candidate;
                    break;
                }
            }
            if (match == null) {
                throw new AssertionError(ctx + ": entry " + entry.value() + " "
                        + entry.geometry() + " missing from batched result; a=" + a + " b=" + b);
            }
            remaining.remove(match);
        }
    }

    private boolean sameEntry(Entry<String, Geometry> a, Entry<String, Geometry> b) {
        return a.value().equals(b.value())
                && fromGeometry(a.geometry()).equals(fromGeometry(b.geometry()));
    }

    private void revisitSnapshot(long seed, int currentStep) {
        int candidates = versions.size() - 1;
        if (candidates <= 0 || snapshotRandom.nextInt(100) >= 40) {
            return;
        }
        Version snapshot = versions.get(snapshotRandom.nextInt(candidates));
        String ctx = "[seed=" + seed + " config=" + config.label + " snapshot@step="
                + snapshot.step + " revisited@" + currentStep + "]";
        TreeInvariants.assertInvariants(ctx, snapshot.tree, snapshot.ref.size());
        assertSearchMultiset(ctx, snapshot.tree, snapshot.ref, new Rect(0, 0, 2, 2));
        assertSearchMultiset(ctx, snapshot.tree, snapshot.ref, new Rect(1, 1, 4, 4));
        assertSearchMultiset(ctx, snapshot.tree, snapshot.ref, new Rect(2, 3, 3, 3));
    }

    private RTree<String, Geometry> roundTrip(String ctx, RTree<String, Geometry> tree,
            RefModel ref) {
        Serializer<String, Geometry> serializer = Serializers.flatBuffers().utf8();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            serializer.write(tree, out);
            byte[] bytes = out.toByteArray();
            RTree<String, Geometry> read = serializer.read(new ByteArrayInputStream(bytes),
                    bytes.length, InternalStructure.DEFAULT);
            assertEntryMultiset(ctx + " round-trip entries", null, ref.items(),
                    read.entries().toList().toBlocking().single());
            assertEquals(ctx + " round-trip size", tree.size(), read.size());
            TreeInvariants.assertInvariants(ctx + " round-trip", read, ref.size());
            return read;
        } catch (IOException e) {
            throw new AssertionError(ctx + ": serialization round-trip failed", e);
        }
    }

    /**
     * Collects an observable by issuing requests in several small batches and
     * merging them; the merged sequence must equal a single {@code toList()}
     * request. Requests are backpressure driven and deterministic (no timing,
     * no threads).
     */
    static List<Entry<String, Geometry>> collectInBatches(
            Observable<Entry<String, Geometry>> observable) {
        final List<Entry<String, Geometry>> collected = new ArrayList<Entry<String, Geometry>>();
        observable.subscribe(new rx.Subscriber<Entry<String, Geometry>>() {
            @Override
            public void onStart() {
                request(1);
            }

            @Override
            public void onCompleted() {
            }

            @Override
            public void onError(Throwable e) {
                throw new AssertionError("batched request failed", e);
            }

            @Override
            public void onNext(Entry<String, Geometry> entry) {
                collected.add(entry);
                // alternate batch sizes: 1, then 3, then 2 ...
                long n = (collected.size() % 3 == 0) ? 2L : (collected.size() % 3 == 1 ? 1L : 3L);
                request(n);
            }
        });
        return collected;
    }

    private RTree<String, Geometry> delete(RTree<String, Geometry> tree, String value,
            Geometry geometry, boolean all) {
        if (skipRootContraction) {
            return com.github.davidmoten.rtree.RTreeTestAccess
                    .deleteWithoutRootContraction(tree, value, geometry, all);
        }
        return tree.delete(value, geometry, all);
    }

    static Geometry toGeometry(Rect rect) {
        if (rect.isPoint()) {
            return Geometries.point(rect.x1, rect.y1);
        }
        return Geometries.rectangle(rect.x1, rect.y1, rect.x2, rect.y2);
    }

    static Rectangle prodRect(Rect rect) {
        return Geometries.rectangle(rect.x1, rect.y1, rect.x2, rect.y2);
    }

    private static Rect fromGeometry(Geometry geometry) {
        Rectangle r = geometry.mbr();
        return new Rect(r.x1(), r.y1(), r.x2(), r.y2());
    }

    private static void assertEquals(String message, int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError(message + " expected:<" + expected + "> but was:<"
                    + actual + ">");
        }
    }

    private static void assertEquals(String message, double expected, double actual,
            double tolerance) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(message + " expected:<" + expected + "> but was:<"
                    + actual + ">");
        }
    }

    static String context(String configLabel, long seed, List<Action> actions, int step) {
        StringBuilder builder = new StringBuilder();
        builder.append("[seed=").append(seed).append(" config=").append(configLabel)
                .append(" step=").append(step).append("/")
                .append(actions.size()).append("]\n");
        int from = Math.max(0, step - 8);
        for (int i = from; i < step; i++) {
            builder.append("  ").append(i + 1).append(": ").append(actions.get(i)).append('\n');
        }
        return builder.toString();
    }
}
