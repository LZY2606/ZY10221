package com.github.davidmoten.rtree.statemachine;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.github.davidmoten.rtree.statemachine.StateMachineRunner.Config;

/**
 * Fixed-seed, property-based state machine tests for the immutable R-tree.
 *
 * <p>
 * For every (configuration, seed) pair a deterministic action history is
 * generated; each step is checked against an independent reference multiset
 * (size, intersection-search multiset, nearest entry/distance multiset) and
 * against the internal node invariants, while random earlier snapshots are
 * revisited to prove persistence. Failures report the seed and the replayable
 * action history, shrunk to a minimal still-failing prefix by {@link Shrinker}
 * (shrinking never merges duplicate entries, preserving occurrence identity).
 */
@RunWith(Parameterized.class)
public class RTreeStateMachineTest {

    /** Actions per history; small grids force structural churn quickly. */
    static final int HISTORY_LENGTH = 350;

    private final Config config;
    private final long seed;

    public RTreeStateMachineTest(Config config, Long seed) {
        this.config = config;
        this.seed = seed.longValue();
    }

    @Parameters(name = "{0} seed={1}")
    public static List<Object[]> parameters() {
        List<Object[]> out = new ArrayList<Object[]>();
        long[] seeds = { 1L, 2L, 7L, 13L, 42L, 99L, 123456789L };
        for (Config config : Config.values()) {
            for (long seed : seeds) {
                out.add(new Object[] { config, Long.valueOf(seed) });
            }
        }
        return out;
    }

    @Test
    public void stateMachineAgreesWithReferenceMultiset() {
        List<Action> actions = Generator.generate(seed, HISTORY_LENGTH);
        run(config, seed, actions);
    }

    static void run(Config config, long seed, List<Action> actions) {
        try {
            new StateMachineRunner(config, seed).run(seed, actions);
        } catch (AssertionError failure) {
            List<Action> minimal = Shrinker.shrink(actions,
                    candidate -> new StateMachineRunner(config, seed).run(seed, candidate));
            throw new AssertionError(buildReplayMessage(config, seed, minimal, failure), failure);
        }
    }

    static String buildReplayMessage(Config config, long seed, List<Action> actions,
            AssertionError failure) {
        StringBuilder builder = new StringBuilder();
        builder.append("State machine failed for config=").append(config.label)
                .append(" seed=").append(seed).append(" minimalActions=").append(actions.size())
                .append('\n');
        builder.append("Replay: Generator.generate(").append(seed).append("L, ")
                .append(actions.size()).append(") with config ").append(config.name())
                .append('\n');
        builder.append("Minimal failing action prefix:\n");
        for (int i = 0; i < actions.size(); i++) {
            builder.append("  ").append(i + 1).append(". ").append(actions.get(i)).append('\n');
        }
        builder.append("Cause: ").append(failure.getMessage());
        return builder.toString();
    }
}
