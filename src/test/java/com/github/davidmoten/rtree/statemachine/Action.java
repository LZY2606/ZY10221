package com.github.davidmoten.rtree.statemachine;

import com.github.davidmoten.rtree.statemachine.RefGeometry.Rect;

/**
 * One state-machine action. Actions are immutable and fully describe how to
 * mutate or query the current tree version, so an action list is a complete,
 * deterministic, replayable history for a fixed configuration and seed.
 */
abstract class Action {

    private Action() {
        // sealed family
    }

    @Override
    public abstract String toString();

    /** Adds a fresh entry occurrence. */
    static final class Add extends Action {
        final long id;
        final String value;
        final Rect geometry;

        Add(long id, String value, Rect geometry) {
            this.id = id;
            this.value = value;
            this.geometry = geometry;
        }

        @Override
        public String toString() {
            return "Add(" + id + ",'" + value + "'," + geometry + ")";
        }
    }

    /**
     * Deletes entries matching a value/geometry pair. {@code exists} records
     * whether the generator believed the pair was present (purely diagnostic);
     * deleting an absent entry is a legal no-op and is generated too.
     */
    static final class Delete extends Action {
        final String value;
        final Rect geometry;
        final boolean all;
        final boolean expectedPresent;

        Delete(String value, Rect geometry, boolean all, boolean expectedPresent) {
            this.value = value;
            this.geometry = geometry;
            this.all = all;
            this.expectedPresent = expectedPresent;
        }

        @Override
        public String toString() {
            return "Delete('" + value + "'," + geometry + "," + (all ? "all" : "one") + ")";
        }
    }

    /** Removes every entry, yielding the empty version. */
    static final class DeleteAllEntries extends Action {
        static final DeleteAllEntries INSTANCE = new DeleteAllEntries();

        private DeleteAllEntries() {
        }

        @Override
        public String toString() {
            return "DeleteAll()";
        }
    }

    /** Rectangle-intersection search query. */
    static final class Search extends Action {
        final Rect query;

        Search(Rect query) {
            this.query = query;
        }

        @Override
        public String toString() {
            return "Search(" + query + ")";
        }
    }

    /** Nearest-k query with an explicit radius and limit. */
    static final class Nearest extends Action {
        final Rect query;
        final double maxDistance;
        final int maxCount;

        Nearest(Rect query, double maxDistance, int maxCount) {
            this.query = query;
            this.maxDistance = maxDistance;
            this.maxCount = maxCount;
        }

        @Override
        public String toString() {
            return "Nearest(" + query + ",r<" + maxDistance + ",k=" + maxCount + ")";
        }
    }

    /** Reads {@code size()} and compares it to the reference multiset size. */
    static final class Size extends Action {
        static final Size INSTANCE = new Size();

        private Size() {
        }

        @Override
        public String toString() {
            return "Size()";
        }
    }

    /**
     * Serializes the current version and reads it back (FlatBuffers/UTF-8),
     * then continues from the deserialized version; entries and structure must
     * round-trip.
     */
    static final class SerializeRoundTrip extends Action {
        static final SerializeRoundTrip INSTANCE = new SerializeRoundTrip();

        private SerializeRoundTrip() {
        }

        @Override
        public String toString() {
            return "RoundTrip()";
        }
    }
}
