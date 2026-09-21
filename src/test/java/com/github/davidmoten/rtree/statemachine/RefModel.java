package com.github.davidmoten.rtree.statemachine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.github.davidmoten.rtree.statemachine.RefGeometry.Rect;

/**
 * Simple, obviously-correct reference model for the immutable R-tree state
 * machine tests: a multiset of entries kept in insertion order.
 *
 * <p>
 * Each {@link Item} is an individual occurrence: equal values and equal
 * geometries on different items are still distinct entries, mirroring the
 * R-tree which stores entries by occurrence (deleting a duplicate removes one
 * occurrence unless delete-all is requested).
 */
final class RefModel {

    /** A single stored occurrence. */
    static final class Item {
        final long id;
        final String value;
        final Rect geometry;

        Item(long id, String value, Rect geometry) {
            this.id = id;
            this.value = value;
            this.geometry = geometry;
        }

        boolean matches(String otherValue, Rect otherGeometry) {
            return value.equals(otherValue) && geometry.equals(otherGeometry);
        }

        @Override
        public String toString() {
            return "#" + id + "=" + value + ":" + geometry;
        }
    }

    private final List<Item> items;

    RefModel() {
        this.items = new ArrayList<Item>();
    }

    private RefModel(List<Item> items) {
        this.items = items;
    }

    RefModel copy() {
        return new RefModel(new ArrayList<Item>(items));
    }

    void add(Item item) {
        items.add(item);
    }

    /** Deletes one occurrence matching value and geometry; returns whether one was removed. */
    boolean deleteOne(String value, Rect geometry) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).matches(value, geometry)) {
                items.remove(i);
                return true;
            }
        }
        return false;
    }

    /** Deletes every occurrence matching value and geometry; returns the count removed. */
    int deleteAll(String value, Rect geometry) {
        int removed = 0;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).matches(value, geometry)) {
                items.remove(i);
                removed++;
            }
        }
        return removed;
    }

    void clear() {
        items.clear();
    }

    int size() {
        return items.size();
    }

    boolean isEmpty() {
        return items.isEmpty();
    }

    List<Item> items() {
        return Collections.unmodifiableList(items);
    }

    /** All items whose geometry intersects the query rectangle (borders touch => match). */
    List<Item> search(Rect query) {
        List<Item> out = new ArrayList<Item>();
        for (Item item : items) {
            if (RefGeometry.intersects(item.geometry, query)) {
                out.add(item);
            }
        }
        return out;
    }

    /** Items strictly closer than {@code maxDistance} to the query rectangle. */
    List<Item> within(Rect query, double maxDistance) {
        List<Item> out = new ArrayList<Item>();
        for (Item item : items) {
            if (RefGeometry.distance(item.geometry, query) < maxDistance) {
                out.add(item);
            }
        }
        return out;
    }

    /**
     * Describes every feasible nearest-k answer without imposing a tie-break
     * ordering. Entries strictly closer than {@code cutoffDistance} are
     * mandatory; entries exactly at the cutoff distance are optional and any
     * {@code optionalSlots} of them (multiset) complete a valid answer. When
     * fewer than {@code maxCount} entries lie within the radius there is no
     * cutoff tie and every such entry is mandatory.
     */
    static final class NearestExpectation {
        final List<ItemDistance> mandatory = new ArrayList<ItemDistance>();
        final List<ItemDistance> atCutoff = new ArrayList<ItemDistance>();
        final int expectedCount;
        int optionalSlots;

        NearestExpectation(int expectedCount) {
            this.expectedCount = expectedCount;
        }
    }

    /**
     * Computes the feasible nearest-k answer set. The implementation promises
     * no ordering for equal distances, so callers verify only that the result
     * contains every strictly-closer entry and a correct-size multiset of
     * entries at the cutoff distance.
     */
    NearestExpectation nearestExpectation(Rect query, double maxDistance, int maxCount) {
        List<ItemDistance> within = new ArrayList<ItemDistance>();
        for (Item item : items) {
            double d = RefGeometry.distance(item.geometry, query);
            if (d < maxDistance) {
                within.add(new ItemDistance(item, d));
            }
        }
        Collections.sort(within, (a, b) -> Double.compare(a.distance, b.distance));
        int expectedCount = Math.min(maxCount, within.size());
        NearestExpectation expectation = new NearestExpectation(expectedCount);
        if (expectedCount == 0) {
            return expectation;
        }
        if (within.size() <= maxCount) {
            expectation.mandatory.addAll(within);
            return expectation;
        }
        double cutoff = within.get(maxCount - 1).distance;
        int slots = 0;
        for (ItemDistance pair : within) {
            if (pair.distance < cutoff) {
                expectation.mandatory.add(pair);
            } else if (pair.distance == cutoff) {
                expectation.atCutoff.add(pair);
                slots++;
            }
        }
        // number of cutoff entries the implementation may keep
        expectation.optionalSlots = expectedCount - expectation.mandatory.size();
        return expectation;
    }

    static final class ItemDistance {
        final Item item;
        final double distance;

        ItemDistance(Item item, double distance) {
            this.item = item;
            this.distance = distance;
        }
    }
}