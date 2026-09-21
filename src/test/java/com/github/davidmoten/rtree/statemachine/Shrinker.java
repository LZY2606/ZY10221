package com.github.davidmoten.rtree.statemachine;

import java.util.ArrayList;
import java.util.List;

/**
 * Reduces a failing action history to a short still-failing prefix/history.
 *
 * <p>
 * The shrinker works only by truncation and action removal; it never edits an
 * action's identity. In particular it does not merge duplicate {@code Add} or
 * {@code Delete} actions and never changes an entry's id/value/geometry, so an
 * occurrence that triggered a duplicate-sensitive defect is preserved.
 */
final class Shrinker {

    /** Predicate over an action history; throws (or returns false) on failure. */
    interface Replay {
        void run(List<Action> actions) throws AssertionError;
    }

    private Shrinker() {
        // no instances
    }

    /**
     * Returns a minimal failing history derived from {@code actions} for the
     * given seed and configuration.
     *
     * @param actions
     *            original failing history
     * @param replay
     *            replay harness, expected to throw {@link AssertionError} on
     *            the defect
     * @return the shortest failing history found
     */
    static List<Action> shrink(List<Action> actions, Replay replay) {
        List<Action> current = new ArrayList<Action>(actions);

        // 1. shortest failing prefix
        int lo = 1;
        int hi = current.size();
        int shortest = hi;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            List<Action> prefix = new ArrayList<Action>(current.subList(0, mid));
            if (fails(prefix, replay)) {
                shortest = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        current = new ArrayList<Action>(current.subList(0, shortest));

        // 2. remove individual actions while the history still fails; each
        // remaining action keeps its identity, so duplicate occurrences are
        // preserved unless removing one is provably irrelevant
        boolean reduced = true;
        while (reduced) {
            reduced = false;
            for (int i = 0; i < current.size(); i++) {
                List<Action> candidate = new ArrayList<Action>(current);
                candidate.remove(i);
                if (!candidate.isEmpty() && fails(candidate, replay)) {
                    current = candidate;
                    reduced = true;
                    break;
                }
            }
        }
        return current;
    }

    private static boolean fails(List<Action> actions, Replay replay) {
        try {
            replay.run(actions);
            return false;
        } catch (AssertionError e) {
            return true;
        }
    }
}
