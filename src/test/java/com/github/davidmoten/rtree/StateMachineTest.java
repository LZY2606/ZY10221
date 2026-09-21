package com.github.davidmoten.rtree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.junit.Test;

import com.github.davidmoten.rtree.fbs.SerializerFlatBuffers;
import com.github.davidmoten.rtree.geometry.Geometries;
import com.github.davidmoten.rtree.geometry.Rectangle;

import rx.functions.Func1;
import rx.observers.TestSubscriber;

/**
 * Fixed-seed state machine tests for the immutable R-tree.
 *
 * <p>
 * Each run maintains an {@link RTree} and a trivial reference multiset in
 * lock-step. After every action the current tree is checked against the
 * reference (size, rectangle-search multisets and nearest results) and
 * randomly selected earlier snapshots are rechecked to prove that persistence
 * (path copying with subtree reuse) never mutates a previously published
 * version. Internal invariants (node child counts, exact MBR coverage, root
 * shape and empty-tree shape) are asserted directly by walking the nodes.
 *
 * <p>
 * Failure output contains the seed and the shortest replayable action prefix
 * (see {@link #shrink}). No network, wall clock or filesystem ordering is
 * used.
 */
public class StateMachineTest {

    private static final int SEQUENCE_LENGTH = 400;
    private static final int GRID = 6;
    private static final int SNAPSHOT_CHECK_CHANCE = 4;
    private static final int MAX_SNAPSHOTS_HELD = 12;

    /** Deterministic generator so runs are byte-for-byte reproducible. */
    static final class Rng {
        private final Random random;

        Rng(long seed) {
            this.random = new Random(seed);
        }

        int nextInt(int bound) {
            return random.nextInt(bound);
        }

        boolean chance(int oneIn) {
            return random.nextInt(oneIn) == 0;
        }
    }

    /**
     * An entry is identified by a unique sequential id, so two adds of the same
     * value and geometry are distinct entries (the shrinker must preserve that
     * identity rather than collapsing them into one abstract occurrence).
     */
    static final class Item {
        final long id;
        final int value;
        final Rectangle geometry;

        Item(long id, int value, Rectangle geometry) {
            this.id = id;
            this.value = value;
            this.geometry = geometry;
        }

        Entry<Integer, Rectangle> entry() {
            return Entries.entry(value, geometry);
        }

        @Override
        public String toString() {
            return id + "=(v=" + value + ",g=" + rect(geometry) + ")";
        }
    }

    /** Tree configuration exercised by a run. */
    static final class Spec {
        final int maxChildren;
        final int minChildren;

        Spec(int maxChildren, int minChildren) {
            this.maxChildren = maxChildren;
            this.minChildren = minChildren;
        }

        RTree<Integer, Rectangle> empty() {
            return RTree.maxChildren(maxChildren).minChildren(minChildren).create();
        }

        @Override
        public String toString() {
            return "Spec[max=" + maxChildren + ",min=" + minChildren + "]";
        }
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    abstract static class Action {
        abstract RTree<Integer, Rectangle> apply(RTree<Integer, Rectangle> tree, Model model);
    }

    static final class Add extends Action {
        final Item item;

        Add(Item item) {
            this.item = item;
        }

        @Override
        RTree<Integer, Rectangle> apply(RTree<Integer, Rectangle> tree, Model model) {
            model.add(item);
            return tree.add(item.entry());
        }

        @Override
        public String toString() {
            return "add " + item;
        }
    }

    static final class Delete extends Action {
        final Item item;
        final boolean all;

        Delete(Item item, boolean all) {
            this.item = item;
            this.all = all;
        }

        @Override
        RTree<Integer, Rectangle> apply(RTree<Integer, Rectangle> tree, Model model) {
            if (all) {
                model.removeAll(item);
            } else {
                model.removeOne(item);
            }
            return tree.delete(item.value, item.geometry, all);
        }

        @Override
        public String toString() {
            return "delete" + (all ? "-all " : " ") + item;
        }
    }

    static final class DeleteAll extends Action {
        @Override
        RTree<Integer, Rectangle> apply(RTree<Integer, Rectangle> tree, Model model) {
            Iterable<Entry<Integer, Rectangle>> entries = model.lastAddedEntries();
            model.clear();
            return tree.delete(entries, true);
        }

        @Override
        public String toString() {
            return "delete-all";
        }
    }

    static final class Search extends Action {
        final Rectangle query;

        Search(Rectangle query) {
            this.query = query;
        }

        @Override
        RTree<Integer, Rectangle> apply(RTree<Integer, Rectangle> tree, Model model) {
            List<Entry<Integer, Rectangle>> expected = model.search(query);
            List<Entry<Integer, Rectangle>> actual = tree.search(query).toList().toBlocking()
                    .single();
            assertMultiset(expected, actual, "search " + rect(query));
            assertBatchedRequestEqualsOneShot(tree, query);
            return tree;
        }


        @Override
        public String toString() {
            return "search " + rect(query);
        }
    }

    static final class Nearest extends Action {
        final Rectangle query;
        final double maxDistance;
        final int maxCount;

        Nearest(Rectangle query, double maxDistance, int maxCount) {
            this.query = query;
            this.maxDistance = maxDistance;
            this.maxCount = maxCount;
        }

        @Override
        RTree<Integer, Rectangle> apply(RTree<Integer, Rectangle> tree, Model model) {
            List<Entry<Integer, Rectangle>> actual = tree.nearest(query, maxDistance, maxCount)
                    .toList().toBlocking().single();
            model.assertNearest(actual, query, maxDistance, maxCount);
            return tree;
        }


        @Override
        public String toString() {
            return "nearest " + rect(query) + " maxDist=" + maxDistance + " k=" + maxCount;
        }
    }

    static final class Size extends Action {
        @Override
        RTree<Integer, Rectangle> apply(RTree<Integer, Rectangle> tree, Model model) {
            assertEquals("size mismatch", model.size(), tree.size());
            assertEquals("isEmpty mismatch", model.size() == 0, tree.isEmpty());
            return tree;
        }


        @Override
        public String toString() {
            return "size";
        }
    }

    static final class RoundTrip extends Action {
        @Override
        RTree<Integer, Rectangle> apply(RTree<Integer, Rectangle> tree, Model model) {
            RTree<Integer, Rectangle> result = tree;
            for (InternalStructure structure : InternalStructure.values()) {
                RTree<Integer, Rectangle> restored = roundTrip(tree, structure);
                List<Entry<Integer, Rectangle>> expected = model.allEntries();
                List<Entry<Integer, Rectangle>> actual = restored.entries().toList()
                        .toBlocking().single();
                assertMultiset(expected, actual,
                        "entries after serialization round-trip (" + structure + ")");
                assertEquals("size after round-trip (" + structure + ")", model.size(),
                        restored.size());
                assertInvariants(restored, "after round-trip (" + structure + ")");
                assertBatchedEntriesEqualsOneShot(restored);
            }
            return result;
        }


        @Override
        public String toString() {
            return "round-trip";
        }
    }

    // ------------------------------------------------------------------
    // Reference model: multiset of items keyed by unique id
    // ------------------------------------------------------------------

    static final class Model {
        // id -> number of live copies (defensively a multiset; ids are unique
        // so this is 0/1, but keeping a multiset documents the intent)
        private final Map<Long, Integer> counts = new HashMap<Long, Integer>();
        private final Map<Long, Item> byId = new HashMap<Long, Item>();
        private int size = 0;

        void add(Item item) {
            byId.put(item.id, item);
            counts.merge(item.id, 1, Integer::sum);
            size++;
        }

        void removeOne(Item item) {
            // RTree.delete matches on (value, geometry), not on item
            // identity: any live copy with the same key satisfies the delete.
            for (Long id : counts.keySet()) {
                Item candidate = byId.get(id);
                if (candidate.value == item.value
                        && sameRectangle(candidate.geometry, item.geometry)) {
                    int c = counts.get(id);
                    if (c - 1 == 0) {
                        counts.remove(id);
                    } else {
                        counts.put(id, c - 1);
                    }
                    size--;
                    return;
                }
            }
        }

        void removeAll(Item item) {
            // delete(..., all=true) removes every entry matching on (value,
            // geometry), identities notwithstanding.
            for (Long id : new ArrayList<Long>(counts.keySet())) {
                Item candidate = byId.get(id);
                if (candidate.value == item.value
                        && sameRectangle(candidate.geometry, item.geometry)) {
                    size -= counts.remove(id);
                }
            }
        }

        void clear() {
            counts.clear();
            size = 0;
        }

        int size() {
            return size;
        }

        boolean isLive(Item item) {
            Integer c = counts.get(item.id);
            return c != null && c > 0;
        }

        Iterable<Entry<Integer, Rectangle>> lastAddedEntries() {
            // The action clears the tree; the entries iterable only needs to
            // cover whatever currently exists. RTree.delete(Iterable, true)
            // tolerates absent entries.
            List<Entry<Integer, Rectangle>> list = new ArrayList<Entry<Integer, Rectangle>>();
            for (Long id : counts.keySet()) {
                Item item = byId.get(id);
                for (int i = 0; i < counts.get(id); i++) {
                    list.add(item.entry());
                }
            }
            return list;
        }

        List<Entry<Integer, Rectangle>> allEntries() {
            List<Entry<Integer, Rectangle>> list = new ArrayList<Entry<Integer, Rectangle>>();
            for (Long id : new ArrayList<Long>(counts.keySet())) {
                Item item = byId.get(id);
                for (int i = 0; i < counts.get(id); i++) {
                    list.add(item.entry());
                }
            }
            return list;
        }

        List<Entry<Integer, Rectangle>> search(Rectangle q) {
            List<Entry<Integer, Rectangle>> list = new ArrayList<Entry<Integer, Rectangle>>();
            for (Long id : counts.keySet()) {
                Item item = byId.get(id);
                if (item.geometry.intersects(q)) {
                    for (int i = 0; i < counts.get(id); i++) {
                        list.add(item.entry());
                    }
                }
            }
            return list;
        }

        void assertNearest(List<Entry<Integer, Rectangle>> actual, Rectangle q,
                double maxDistance, int maxCount) {
            // Reference candidates with strict d < maxDistance (multiset).
            TreeMap<Double, List<Entry<Integer, Rectangle>>> byDistance = new TreeMap<Double, List<Entry<Integer, Rectangle>>>();
            for (Entry<Integer, Rectangle> e : allEntries()) {
                double d = e.geometry().distance(q);
                if (d < maxDistance) {
                    byDistance.computeIfAbsent(d, k -> new ArrayList<Entry<Integer, Rectangle>>())
                            .add(e);
                }
            }
            List<Entry<Integer, Rectangle>> candidatePool = new ArrayList<Entry<Integer, Rectangle>>();
            for (List<Entry<Integer, Rectangle>> group : byDistance.values()) {
                candidatePool.addAll(group);
            }

            // BoundedPriorityQueue keeps min(maxCount, #candidates) entries
            // with the smallest distances. At a distance tie crossing the
            // capacity boundary the kept members are arrival-order dependent,
            // which the API does not promise, so ties are compared only via the
            // distance multiset.
            int expectedCount = Math.min(maxCount, candidatePool.size());
            assertTrue("nearest returned too many entries: " + actual.size(),
                    actual.size() <= maxCount);
            assertEquals("nearest count", expectedCount, actual.size());
            if (expectedCount == 0) {
                return;
            }

            List<Double> sortedCandidateDistances = new ArrayList<Double>();
            for (Entry<Integer, Rectangle> e : candidatePool) {
                sortedCandidateDistances.add(e.geometry().distance(q));
            }
            Collections.sort(sortedCandidateDistances);

            List<Double> emittedDistances = new ArrayList<Double>();
            List<Entry<Integer, Rectangle>> remaining = new ArrayList<Entry<Integer, Rectangle>>(
                    candidatePool);
            for (Entry<Integer, Rectangle> e : actual) {
                double d = e.geometry().distance(q);
                assertTrue("nearest emitted out-of-range entry d=" + d, d < maxDistance);
                emittedDistances.add(d);
                assertTrue("nearest emitted entry not in candidate multiset: " + e,
                        remaining.remove(e));
            }

            // Threshold of the k-th smallest candidate distance. Every entry
            // strictly closer than the threshold must be present; entries at
            // the threshold may be an arbitrary subset up to maxCount.
            double threshold = sortedCandidateDistances.get(expectedCount - 1);
            List<Double> requiredDistances = new ArrayList<Double>();
            for (int i = 0; i < expectedCount; i++) {
                double d = sortedCandidateDistances.get(i);
                if (d < threshold) {
                    requiredDistances.add(d);
                }
            }
            List<Double> emittedSorted = new ArrayList<Double>(emittedDistances);
            Collections.sort(emittedSorted);
            assertTrue("nearest missing strictly closer entries, emitted distances="
                    + emittedSorted + " required prefix=" + requiredDistances,
                    emittedSorted.subList(0, requiredDistances.size()).equals(requiredDistances));
            assertTrue("nearest emitted entry beyond threshold",
                    emittedSorted.get(emittedSorted.size() - 1) <= threshold);

            // Tie-free case: exact entry multiset is determined.
            int tiedAtThreshold = byDistance.get(threshold).size();
            int strictlyBelow = sortedCandidateDistances.size()
                    - (int) sortedCandidateDistances.stream().filter(d -> d == threshold).count();
            if (strictlyBelow + tiedAtThreshold <= maxCount) {
                List<Entry<Integer, Rectangle>> expected = new ArrayList<Entry<Integer, Rectangle>>(
                        candidatePool);
                Collections.sort(expected,
                        (a, b) -> Double.compare(a.geometry().distance(q),
                                b.geometry().distance(q)));
                assertMultiset(expected.subList(0, expectedCount), actual, "nearest (tie-free)");
            }
        }
    }

    // ------------------------------------------------------------------
    // Action generator
    // ------------------------------------------------------------------

    /**
     * Generates a fixed sequence of actions from a seed. Geometries live on a
     * small integer grid so duplicates and boundary-touching rectangles are
     * common (rectangles at adjacent cells share an edge and thus intersect).
     */
    static List<Action> generate(long seed, Spec spec, int length) {
        Rng rng = new Rng(seed);
        List<Action> actions = new ArrayList<Action>();
        List<Item> everAdded = new ArrayList<Item>();
        long nextId = 1;
        for (int step = 0; step < length; step++) {
            int kind = rng.nextInt(100);
            if (kind < 42) {
                Item item = randomItem(rng, nextId++);
                everAdded.add(item);
                actions.add(new Add(item));
            } else if (kind < 68) {
                if (everAdded.isEmpty()) {
                    Item item = randomItem(rng, nextId++);
                    everAdded.add(item);
                    actions.add(new Add(item));
                } else {
                    Item item = everAdded.get(rng.nextInt(everAdded.size()));
                    actions.add(new Delete(item, rng.nextInt(4) == 0));
                }
            } else if (kind < 73) {
                actions.add(new DeleteAll());
            } else if (kind < 88) {
                actions.add(new Search(randomQuery(rng)));
            } else if (kind < 95) {
                actions.add(new Nearest(randomQuery(rng), rng.nextInt(3) + 0.5,
                        1 + rng.nextInt(4)));
            } else if (kind < 98) {
                actions.add(new Size());
            } else {
                actions.add(new RoundTrip());
            }
        }
        return actions;
    }

    private static Item randomItem(Rng rng, long id) {
        int x = rng.nextInt(GRID);
        int y = rng.nextInt(GRID);
        Rectangle g;
        if (rng.nextInt(3) == 0) {
            // degenerate (point-like) rectangle; repeated ids often share it
            g = Geometries.rectangle(x, y, x, y);
        } else {
            int w = rng.nextInt(2);
            int h = rng.nextInt(2);
            g = Geometries.rectangle(x, y, x + w, y + h);
        }
        // values drawn from a small alphabet so duplicates are common even
        // though item identity (id) is unique
        int value = rng.nextInt(4);
        return new Item(id, value, g);
    }

    private static Rectangle randomQuery(Rng rng) {
        int x1 = rng.nextInt(GRID);
        int y1 = rng.nextInt(GRID);
        int x2 = x1 + rng.nextInt(3);
        int y2 = y1 + rng.nextInt(3);
        return Geometries.rectangle(x1, y1, x2, y2);
    }

    // ------------------------------------------------------------------
    // Runner with snapshot persistence checks and shrinking
    // ------------------------------------------------------------------

    static final class Snapshot {
        final int step;
        final RTree<Integer, Rectangle> tree;
        final List<Entry<Integer, Rectangle>> entries;
        final int size;
        final int depth;
        final String mbr;

        Snapshot(int step, RTree<Integer, Rectangle> tree) {
            this.step = step;
            this.tree = tree;
            this.entries = tree.entries().toList().toBlocking().single();
            this.size = tree.size();
            this.depth = tree.calculateDepth();
            this.mbr = tree.mbr().map(StateMachineTest::rect).orElse("<absent>");
        }
    }

    static final class Failure extends RuntimeException {
        final long seed;
        final List<Action> prefix;
        final int step;
        final Spec spec;

        Failure(Spec spec, long seed, List<Action> prefix, int step, String context,
                Throwable cause) {
            super(context, cause);
            this.spec = spec;
            this.seed = seed;
            this.prefix = prefix;
            this.step = step;
        }

        String replayReport() {
            StringBuilder sb = new StringBuilder();
            sb.append("State machine failure\n");
            sb.append("  spec: ").append(spec).append('\n');
            sb.append("  seed: ").append(seed).append('\n');
            sb.append("  failing step index: ").append(step).append('\n');
            sb.append("  minimal prefix length: ").append(prefix.size()).append('\n');
            sb.append("  message: ").append(getMessage()).append('\n');
            sb.append("  replay: run StateMachineTest with system property -Drtree.test.seed=")
                    .append(seed).append(" (spec index encoded in -Drtree.test.spec)\n");
            sb.append("  action prefix:\n");
            for (int i = 0; i < prefix.size(); i++) {
                sb.append("    ").append(i).append(": ").append(prefix.get(i)).append('\n');
            }
            return sb.toString();
        }
    }

    static void run(Spec spec, long seed, int length) {
        List<Action> actions = generate(seed, spec, length);
        List<Action> failingPrefix = execute(spec, seed, actions);
        if (failingPrefix != null) {
            String originalMessage = lastFailureMessage;
            Throwable originalCause = lastFailureCause;
            List<Action> shrunk = shrink(spec, seed, failingPrefix);
            int failingStep = shrunk.size() - 1;
            // Prefer the failure reproduced by the minimal prefix, but retain
            // the original cause as fallback context.
            String message = lastFailureMessage != null ? lastFailureMessage : originalMessage;
            Throwable cause = lastFailureCause != null ? lastFailureCause : originalCause;
            Failure failure = new Failure(spec, seed, shrunk, failingStep, message, cause);
            fail(failure.replayReport());
        }
    }

    private static String lastFailureMessage;
    private static Throwable lastFailureCause;

    /**
     * Executes the sequence and returns the shortest prefix that fails, or
     * {@code null} on success.
     */
    private static List<Action> execute(Spec spec, long seed, List<Action> actions) {
        lastFailureMessage = null;
        lastFailureCause = null;
        RTree<Integer, Rectangle> tree = spec.empty();
        Model model = new Model();
        List<Snapshot> snapshots = new ArrayList<Snapshot>();
        snapshots.add(new Snapshot(-1, tree));
        Rng checkRng = new Rng(seed ^ 0x5DEECE66DL);
        for (int i = 0; i < actions.size(); i++) {
            Action action = actions.get(i);
            try {
                tree = action.apply(tree, model);
            } catch (AssertionError e) {
                lastFailureMessage = "assertion at action " + i + " (" + action + "): "
                        + String.valueOf(e.getMessage());
                lastFailureCause = e;
                return new ArrayList<Action>(actions.subList(0, i + 1));
            } catch (RuntimeException e) {
                lastFailureMessage = "exception " + e.getClass().getName() + " at action " + i
                        + " (" + action + "): " + e.getMessage();
                lastFailureCause = e;
                return new ArrayList<Action>(actions.subList(0, i + 1));
            }
            assertEquals("model/tree size divergence after " + action, model.size(), tree.size());
            assertInvariants(tree, "after action " + i + " (" + action + ")");
            snapshots.add(new Snapshot(i, tree));
            if (snapshots.size() > MAX_SNAPSHOTS_HELD) {
                snapshots.remove(0);
            }
            if (checkRng.chance(SNAPSHOT_CHECK_CHANCE)) {
                Snapshot snapshot = snapshots.get(checkRng.nextInt(snapshots.size()));
                assertSnapshotUnchanged(snapshot);
            }
        }
        // final check of every retained snapshot
        for (Snapshot snapshot : snapshots) {
            assertSnapshotUnchanged(snapshot);
        }
        return null;
    }

    /**
     * Delta-debugging style shrinker: repeatedly try removing each action while
     * the prefix still fails. Item ids are part of the actions themselves, so
     * duplicate adds retain their distinct identities through shrinking.
     */
    static List<Action> shrink(Spec spec, long seed, List<Action> failingPrefix) {
        List<Action> current = new ArrayList<Action>(failingPrefix);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < current.size(); i++) {
                List<Action> candidate = new ArrayList<Action>(current);
                candidate.remove(i);
                if (candidate.isEmpty()) {
                    continue;
                }
                if (execute(spec, seed, candidate) != null) {
                    current = candidate;
                    changed = true;
                    break;
                }
            }
        }
        return current;
    }

    // ------------------------------------------------------------------
    // Assertions
    // ------------------------------------------------------------------

    private static void assertSnapshotUnchanged(Snapshot snapshot) {
        List<Entry<Integer, Rectangle>> now = snapshot.tree.entries().toList().toBlocking()
                .single();
        assertMultiset(snapshot.entries, now,
                "snapshot taken at step " + snapshot.step + " was mutated by a later action");
        String context = "snapshot taken at step " + snapshot.step;
        assertEquals(context + " size changed", snapshot.size, snapshot.tree.size());
        assertEquals(context + " depth changed", snapshot.depth,
                snapshot.tree.calculateDepth());
        assertEquals(context + " mbr changed", snapshot.mbr,
                snapshot.tree.mbr().map(StateMachineTest::rect).orElse("<absent>"));
    }

    static void assertMultiset(List<Entry<Integer, Rectangle>> expected,
            List<Entry<Integer, Rectangle>> actual, String context) {
        Map<String, Integer> expectedCounts = multiset(expected);
        Map<String, Integer> actualCounts = multiset(actual);
        if (!expectedCounts.equals(actualCounts)) {
            throw new AssertionError(context + "\n      expected multiset=" + expectedCounts
                    + "\n      actual multiset=  " + actualCounts);
        }
    }

    private static Map<String, Integer> multiset(List<Entry<Integer, Rectangle>> entries) {
        Map<String, Integer> counts = new HashMap<String, Integer>();
        for (Entry<Integer, Rectangle> e : entries) {
            String key = e.value() + "@" + rect(e.geometry());
            counts.merge(key, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Walks every node and checks the internal structural invariants:
     * <ul>
     * <li>an empty tree has no root and depth 0;</li>
     * <li>a leaf root holds 1..maxChildren entries;</li>
     * <li>an internal root has at least 2 children (height shrinks after
     * deletion);</li>
     * <li>non-root internal nodes have 1..maxChildren children and non-root
     * leaves have minChildren..maxChildren entries;</li>
     * <li>every node geometry is exactly the MBR of its children/entries.
     * </ul>
     */
    static void assertInvariants(RTree<Integer, Rectangle> tree, String context) {
        if (tree.root() == null || !tree.root().isPresent()) {
            assertEquals(context + ": empty tree has size 0", 0, tree.size());
            assertEquals(context + ": empty tree depth is 0", 0, tree.calculateDepth());
            assertTrue(context + ": empty tree isEmpty", tree.isEmpty());
            return;
        }
        Node<Integer, Rectangle> root = tree.root().get();
        assertNode(root, true, tree, context);
        int counted = countEntries(root);
        assertEquals(context + ": entry count vs size()", tree.size(), counted);
    }

    private static int assertNode(Node<Integer, Rectangle> node, boolean isRoot,
            RTree<Integer, Rectangle> tree, String context) {
        int max = tree.context().maxChildren();
        int min = tree.context().minChildren();
        Rectangle expectedMbr;
        int counted;
        if (node instanceof Leaf) {
            Leaf<Integer, Rectangle> leaf = (Leaf<Integer, Rectangle>) node;
            int n = leaf.count();
            assertTrue(context + ": leaf never empty", n >= 1);
            assertTrue(context + ": leaf over capacity (" + n + ">" + max + ")", n <= max);
            if (!isRoot) {
                assertTrue(context + ": non-root leaf below minChildren (" + n + "<" + min + ")",
                        n >= min);
            }
            expectedMbr = mbrOfEntries(leaf.entries());
            counted = n;
        } else {
            NonLeaf<Integer, Rectangle> nonLeaf = (NonLeaf<Integer, Rectangle>) node;
            int n = nonLeaf.count();
            assertTrue(context + ": internal node never empty", n >= 1);
            assertTrue(context + ": internal node over capacity (" + n + ">" + max + ")",
                    n <= max);
            if (isRoot) {
                assertTrue(context + ": internal root must have >=2 children (height must "
                        + "shrink after deletion), got " + n, n >= 2);
            }
            Rectangle union = null;
            counted = 0;
            for (int i = 0; i < n; i++) {
                Node<Integer, Rectangle> child = nonLeaf.child(i);
                counted += assertNode(child, false, tree, context);
                union = union == null ? child.geometry().mbr()
                        : union.add(child.geometry().mbr());
            }
            expectedMbr = union;
        }
        Rectangle actualMbr = node.geometry().mbr();
        if (!sameRectangle(expectedMbr, actualMbr)) {
            throw new AssertionError(context + ": node MBR not tightened to children: node mbr="
                    + rect(actualMbr) + " children mbr=" + rect(expectedMbr));
        }
        return counted;
    }

    private static Rectangle mbrOfEntries(List<Entry<Integer, Rectangle>> entries) {
        Rectangle union = null;
        for (Entry<Integer, Rectangle> e : entries) {
            union = union == null ? e.geometry().mbr() : union.add(e.geometry().mbr());
        }
        return union;
    }

    private static int countEntries(Node<Integer, Rectangle> node) {
        if (node instanceof Leaf) {
            return node.count();
        }
        NonLeaf<Integer, Rectangle> nonLeaf = (NonLeaf<Integer, Rectangle>) node;
        int total = 0;
        for (int i = 0; i < nonLeaf.count(); i++) {
            total += countEntries(nonLeaf.child(i));
        }
        return total;
    }

    private static boolean sameRectangle(Rectangle a, Rectangle b) {
        return Double.compare(a.x1(), b.x1()) == 0 && Double.compare(a.y1(), b.y1()) == 0
                && Double.compare(a.x2(), b.x2()) == 0 && Double.compare(a.y2(), b.y2()) == 0;
    }

    private static String rect(Rectangle r) {
        return "[" + r.x1() + "," + r.y1() + "," + r.x2() + "," + r.y2() + "]";
    }

    // ------------------------------------------------------------------
    // Serialization round-trip and backpressure checks
    // ------------------------------------------------------------------

    static RTree<Integer, Rectangle> roundTrip(RTree<Integer, Rectangle> tree,
            InternalStructure structure) {
        Func1<Integer, byte[]> writer = value -> ("v" + value).getBytes();
        Func1<byte[], Integer> reader = bytes -> Integer
                .parseInt(new String(bytes).substring(1));
        Serializer<Integer, Rectangle> serializer = SerializerFlatBuffers.create(writer, reader);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            serializer.write(tree, bos);
            byte[] data = bos.toByteArray();
            return serializer.read(new ByteArrayInputStream(data), data.length, structure);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Requests a search in small batches via request(n) and asserts the merged
     * result equals the one-shot (request(Long.MAX_VALUE)) result.
     */
    static void assertBatchedRequestEqualsOneShot(RTree<Integer, Rectangle> tree,
            Rectangle query) {
        List<Entry<Integer, Rectangle>> oneShot = tree.search(query).toList().toBlocking()
                .single();
        List<Entry<Integer, Rectangle>> batched = requestInBatches(tree.search(query),
                oneShot.size());
        assertMultiset(oneShot, batched, "batched request(n) search vs one-shot");
    }

    static void assertBatchedEntriesEqualsOneShot(RTree<Integer, Rectangle> tree) {
        List<Entry<Integer, Rectangle>> oneShot = tree.entries().toList().toBlocking().single();
        List<Entry<Integer, Rectangle>> batched = requestInBatches(tree.entries(),
                oneShot.size());
        assertMultiset(oneShot, batched, "batched request(n) entries vs one-shot");
    }

    /**
     * Subscribes without any initial request and then drains the stream in
     * exponentially growing request(n) batches (1, 2, 4, ...), exercising the
     * backpressure path rather than the request(Long.MAX_VALUE) fast path.
     */
    private static List<Entry<Integer, Rectangle>> requestInBatches(
            rx.Observable<Entry<Integer, Rectangle>> observable, int expected) {
        TestSubscriber<Entry<Integer, Rectangle>> subscriber = new TestSubscriber<Entry<Integer, Rectangle>>(
                0L);
        observable.subscribe(subscriber);
        List<Entry<Integer, Rectangle>> all = new ArrayList<Entry<Integer, Rectangle>>();
        // Drain in batches (1, 2, 4, ..., remainder) with at least one
        // request so empty streams still observe onCompleted. Never requests
        // Long.MAX_VALUE, which would take the no-backpressure fast path.
        int remainingRequestedBudget = expected + 1;
        int batchSize = 1;
        while (remainingRequestedBudget > 0) {
            int ask = Math.min(batchSize, remainingRequestedBudget);
            subscriber.requestMore(ask);
            remainingRequestedBudget -= ask;
            List<Entry<Integer, Rectangle>> events = subscriber.getOnNextEvents();
            all.addAll(events.subList(all.size(), events.size()));
            subscriber.assertNoErrors();
            if (subscriber.getCompletions() > 0) {
                break;
            }
            batchSize *= 2;
        }
        subscriber.assertCompleted();
        assertEquals("batched drain delivered a different number of entries", expected,
                all.size());
        return all;
    }

    // ------------------------------------------------------------------
    // JUnit entry points
    // ------------------------------------------------------------------

    private static final Spec[] SPECS = { new Spec(4, 1), new Spec(4, 2), new Spec(3, 1),
            new Spec(5, 2) };

    @Test
    public void fixedSeedsStateMachine() {
        long override = Long.getLong("rtree.test.seed", Long.MIN_VALUE);
        int specIndex = Integer.getInteger("rtree.test.spec", -1);
        long[] seeds = { 1L, 2L, 42L, 123456789L, -7L, 987654321L };
        for (int s = 0; s < SPECS.length; s++) {
            Spec spec = SPECS[s];
            if (specIndex >= 0 && specIndex != s) {
                continue;
            }
            for (long seed : seeds) {
                if (override != Long.MIN_VALUE && seed != override) {
                    continue;
                }
                run(spec, seed, SEQUENCE_LENGTH);
            }
        }
    }

    @Test
    public void repeatedAddsOfIdenticalValueAndGeometryAreDistinct() {
        Spec spec = new Spec(3, 1);
        Rectangle g = Geometries.rectangle(1, 1, 1, 1);
        RTree<Integer, Rectangle> tree = spec.empty();
        for (int i = 0; i < 10; i++) {
            tree = tree.add(7, g);
        }
        assertEquals(10, tree.size());
        RTree<Integer, Rectangle> snapshot = tree;
        tree = tree.delete(7, g);
        assertEquals(9, tree.size());
        assertEquals(10, snapshot.size());
        assertEquals(Integer.valueOf(10), snapshot.entries().count().toBlocking().single());
        RTree<Integer, Rectangle> emptied = tree.delete(7, g, true);
        assertEquals(0, emptied.size());
        assertFalse(emptied.root().isPresent());
        assertEquals(0, emptied.calculateDepth());
        // earlier versions remain intact
        assertEquals(9, tree.size());
        assertEquals(10, snapshot.size());
    }

    @Test
    public void boundaryTouchingRectanglesIntersect() {
        // two rectangles sharing only the edge x=2: closed-boundary contract
        Rectangle a = Geometries.rectangle(0, 0, 2, 2);
        Rectangle b = Geometries.rectangle(2, 0, 4, 2);
        assertTrue(a.intersects(b));
        RTree<Integer, Rectangle> tree = new Spec(4, 2).empty().add(1, a).add(2, b);
        assertEquals(2, tree.search(Geometries.rectangle(2, 0, 2, 2)).count().toBlocking()
                .single().intValue());
    }

    /**
     * Pins the existing geometry contract: NaN coordinates are rejected by the
     * rectangle factory; infinite coordinates are accepted (no new reference
     * behaviour is defined for them, so the state machine never generates
     * them).
     */
    @Test
    public void geometryContractForNonFiniteCoordinates() {
        try {
            Geometries.rectangle(Double.NaN, 0, 1, 1);
            fail("NaN rectangle coordinate must be rejected by the geometry contract");
        } catch (IllegalArgumentException expected) {
            // documented contract
        }
        try {
            Geometries.rectangle(0, 0, 1, Double.NaN);
            fail("NaN rectangle coordinate must be rejected by the geometry contract");
        } catch (IllegalArgumentException expected) {
            // documented contract
        }
        // infinity is not rejected by the current contract; only assert that
        // construction does not define a new finite-only rule
        Geometries.rectangle(0, 0, Double.POSITIVE_INFINITY, 1);
        Geometries.rectangle(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, 0, 0);
    }

    @Test
    public void emptyTreeShape() {
        RTree<Integer, Rectangle> tree = new Spec(4, 2).empty();
        assertFalse(tree.root().isPresent());
        assertEquals(0, tree.calculateDepth());
        assertEquals(0, tree.size());
        assertTrue(tree.isEmpty());
        assertFalse(tree.mbr().isPresent());
    }

    @Test
    public void deleteShrinksSingleChildRootChain() {
        Spec spec = new Spec(4, 1);
        RTree<Integer, Rectangle> tree = spec.empty();
        for (int i = 0; i < 32; i++) {
            tree = tree.add(i, Geometries.rectangle(i, i, i + 0.5, i + 0.5));
        }
        int fullDepth = tree.calculateDepth();
        assertTrue(fullDepth >= 3);
        for (int i = 31; i >= 8; i--) {
            tree = tree.delete(i, Geometries.rectangle(i, i, i + 0.5, i + 0.5));
        }
        // height must contract: root is no longer a single-child internal node
        assertTrue("depth should shrink after deletions, was " + tree.calculateDepth()
                + " fullDepth=" + fullDepth, tree.calculateDepth() < fullDepth);
        assertInvariants(tree, "deleteShrinksSingleChildRootChain");
    }

    @Test
    public void failureReportContainsSeedAndReplayInstructions() {
        // sanity check on the failure report format without triggering a real
        // failure
        List<Action> prefix = Arrays.asList((Action) new Add(new Item(1, 0,
                Geometries.rectangle(0, 0, 0, 0))));
        Failure failure = new Failure(new Spec(4, 2), 77L, prefix, 0, "boom", null);
        String report = failure.replayReport();
        assertTrue(report.contains("seed: 77"));
        assertTrue(report.contains("-Drtree.test.seed=77"));
        assertTrue(report.contains("add 1="));
    }
}
