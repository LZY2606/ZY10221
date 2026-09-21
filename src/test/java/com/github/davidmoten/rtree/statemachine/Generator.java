package com.github.davidmoten.rtree.statemachine;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.github.davidmoten.rtree.statemachine.RefGeometry.Rect;
import com.github.davidmoten.rtree.statemachine.RefModel.Item;

/**
 * Fixed-seed action generator.
 *
 * <p>
 * The generator maintains the same multiset knowledge as the runner so it can
 * bias deletions towards present pairs (while still generating misses) and can
 * reuse small value and coordinate pools. Coordinates come from a tiny grid so
 * geometries frequently coincide, overlap or merely touch at an edge. Values
 * and geometries are chosen independently, which produces duplicate values with
 * different geometries and identical geometries with different values.
 */
final class Generator {

    /** Half-extent of the coordinate grid: coordinates run 0..GRID. */
    static final int GRID = 4;

    private static final String[] VALUES = { "a", "b", "c", "a", "b" };

    private final Random random;
    private final List<Item> present;
    private long nextId;

    Generator(long seed, List<Item> initialPresent) {
        this.random = new Random(seed);
        this.present = new ArrayList<Item>(initialPresent);
    }

    /** Regenerates an identical action sequence from a seed (for replay). */
    static List<Action> generate(long seed, int count) {
        Generator generator = new Generator(seed, new ArrayList<Item>());
        List<Action> actions = new ArrayList<Action>(count);
        for (int i = 0; i < count; i++) {
            actions.add(generator.next());
        }
        return actions;
    }

    /** Produces the next action and updates generator-side multiset knowledge. */
    Action next() {
        int kind = random.nextInt(100);
        if (kind < 42) {
            return add();
        } else if (kind < 70) {
            return delete(false);
        } else if (kind < 78) {
            return delete(true);
        } else if (kind < 82) {
            return deleteAllEntries();
        } else if (kind < 90) {
            return search();
        } else if (kind < 97) {
            return nearest();
        } else if (kind < 99) {
            return Action.Size.INSTANCE;
        } else {
            return Action.SerializeRoundTrip.INSTANCE;
        }
    }

    private Action.Add add() {
        String value = VALUES[random.nextInt(VALUES.length)];
        Rect geometry = randomGeometry();
        long id = nextId++;
        present.add(new Item(id, value, geometry));
        return new Action.Add(id, value, geometry);
    }

    private Action delete(boolean all) {
        String value;
        Rect geometry;
        boolean expectedPresent = false;
        if (!present.isEmpty() && random.nextInt(100) < 80) {
            Item item = present.get(random.nextInt(present.size()));
            value = item.value;
            geometry = item.geometry;
            expectedPresent = true;
        } else {
            value = VALUES[random.nextInt(VALUES.length)];
            geometry = randomGeometry();
        }
        if (all) {
            for (int i = present.size() - 1; i >= 0; i--) {
                if (present.get(i).matches(value, geometry)) {
                    present.remove(i);
                }
            }
        } else {
            for (int i = 0; i < present.size(); i++) {
                if (present.get(i).matches(value, geometry)) {
                    present.remove(i);
                    break;
                }
            }
        }
        return new Action.Delete(value, geometry, all, expectedPresent);
    }

    private Action deleteAllEntries() {
        present.clear();
        return Action.DeleteAllEntries.INSTANCE;
    }

    private Action.Search search() {
        return new Action.Search(randomQuery());
    }

    private Action.Nearest nearest() {
        Rect query = randomQuery();
        // small radii on the small grid, including zero-ish and tight values
        double[] radii = { 0.0, 0.0001, 1.0, 2.0, 10.0 };
        double maxDistance = radii[random.nextInt(radii.length)];
        int maxCount = 1 + random.nextInt(4);
        return new Action.Nearest(query, maxDistance, maxCount);
    }

    private Rect randomGeometry() {
        if (random.nextInt(3) == 0) {
            // point
            int x = random.nextInt(GRID + 1);
            int y = random.nextInt(GRID + 1);
            return Rect.point(x, y);
        }
        // rectangle, frequently zero-width/zero-height so shapes coincide or
        // merely touch; coordinates are inclusive on the small grid
        int x1 = random.nextInt(GRID + 1);
        int y1 = random.nextInt(GRID + 1);
        int x2 = Math.min(x1 + random.nextInt(2), GRID);
        int y2 = Math.min(y1 + random.nextInt(2), GRID);
        return new Rect(x1, y1, x2, y2);
    }

    private Rect randomQuery() {
        int x1 = random.nextInt(GRID + 1);
        int y1 = random.nextInt(GRID + 1);
        int x2 = Math.min(x1 + random.nextInt(2), GRID);
        int y2 = Math.min(y1 + random.nextInt(2), GRID);
        return new Rect(x1, y1, x2, y2);
    }
}
