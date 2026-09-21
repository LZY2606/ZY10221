package com.github.davidmoten.rtree.statemachine;

import java.util.List;

import org.junit.Test;

import com.github.davidmoten.rtree.Factory;
import com.github.davidmoten.rtree.geometry.Geometry;
import com.github.davidmoten.rtree.statemachine.StateMachineRunner.Config;

/**
 * Proves that the state-machine suite actually has teeth: two deliberately
 * mutated trees, each skipping one specific piece of required maintenance,
 * must be rejected. These tests <em>expect</em> the mutated run to fail the
 * same assertions the production implementation satisfies.
 */
public class MutationDetectionTest {

    private static final long SEED = 5L;
    private static final long ROOT_CONTRACTION_SEED = 2L;
    private static final int LENGTH = 300;
    private static final int ROOT_CONTRACTION_LENGTH = 400;

    /**
     * Skipping MBR recomputation after delete must be detected by the
     * "node geometry equals tight MBR of children" invariant.
     */
    @Test
    public void skipsStaleMbrDetectionOnlyWhenMutationDisabled() {
        // sanity baseline: with the mutation disarmed the custom factory
        // behaves exactly like production and the run must pass
        MutatedTrees.disarm();
        RTreeStateMachineTestSupport.runWithFactory(Config.GUTTMAN_DEFAULT, SEED,
                new MutatedTrees.StaleMbrFactory(), LENGTH);
    }

    @Test
    public void staleMbrAfterDeleteIsDetected() {
        List<Action> actions = Generator.generate(SEED, LENGTH);
        Factory<String, Geometry> factory = new MutatedTrees.StaleMbrFactory();
        boolean failed = runExpectingFailure(Config.GUTTMAN_DEFAULT, SEED, factory, actions);
        if (!failed) {
            throw new AssertionError("Expected the stale-MBR mutation to be detected by the "
                    + "tight-MBR invariant but the state machine run passed");
        }
    }

    /**
     * Skipping root single-child contraction must be detected: a non-empty
     * tree whose entries fit in one leaf must not retain a multi-level,
     * single-child root.
     */
    @Test
    public void skippedRootContractionIsDetected() {
        List<Action> actions = Generator.generate(ROOT_CONTRACTION_SEED,
                ROOT_CONTRACTION_LENGTH);
        boolean failed = false;
        try {
            // minChildren=1 leaves single-child non-leaf roots behind unless
            // the root is contracted, so this configuration exercises the
            // contraction path
            RTreeStateMachineTestSupport.runWithoutRootContraction(Config.GUTTMAN_SMALL,
                    ROOT_CONTRACTION_SEED, actions);
        } catch (AssertionError expected) {
            String message = String.valueOf(expected.getMessage());
            if (message.contains("redundant") || message.contains("root")) {
                failed = true;
            }
        }
        if (!failed) {
            throw new AssertionError("Expected the skipped-root-contraction mutation to be "
                    + "detected by the single-child-root invariant but the run passed");
        }
    }

    private boolean runExpectingFailure(Config config, long seed, Factory<String, Geometry> factory,
            List<Action> actions) {
        MutatedTrees.armStaleMbrOnDelete();
        try {
            RTreeStateMachineTestSupport.runWithFactory(config, seed, factory, actions);
            return false;
        } catch (AssertionError failure) {
            String message = String.valueOf(failure.getMessage());
            if (message.contains("MBR")) {
                return true;
            }
            // an MBR defect may surface indirectly as a multiset mismatch if
            // pruning wrongly hides entries; either failure demonstrates
            // detection, but the tight-MBR message is preferred
            return failure.getCause() != null
                    && String.valueOf(failure.getCause().getMessage()).contains("MBR");
        } finally {
            MutatedTrees.disarm();
        }
    }
}
