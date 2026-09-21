package com.github.davidmoten.rtree.statemachine;

import java.util.List;
import java.util.Optional;

import com.github.davidmoten.rtree.Context;
import com.github.davidmoten.rtree.Factory;
import com.github.davidmoten.rtree.Node;
import com.github.davidmoten.rtree.RTree;
import com.github.davidmoten.rtree.geometry.Geometry;
import com.github.davidmoten.rtree.statemachine.StateMachineRunner.Config;

/**
 * Entry points used by the mutation tests to run the same deterministic action
 * history against deliberately faulty tree constructions.
 */
final class RTreeStateMachineTestSupport {

    private RTreeStateMachineTestSupport() {
        // no instances
    }

    /** Runs a history against a tree built with the given (possibly mutated) factory. */
    static void runWithFactory(Config config, long seed, Factory<String, Geometry> factory,
            int length) {
        runWithFactory(config, seed, factory, Generator.generate(seed, length));
    }

    static void runWithFactory(Config config, long seed, Factory<String, Geometry> factory,
            List<Action> actions) {
        Context<String, Geometry> context = MutatedTrees.context(config.minChildren,
                config.maxChildren, config.star, factory);
        RTree<String, Geometry> empty = com.github.davidmoten.rtree.RTreeTestAccess
                .create(0, Optional.<Node<String, Geometry>>empty(), context);
        run(config, seed, actions, empty, false);
    }

    /** Runs a history while never contracting a single-child non-leaf root. */
    static void runWithoutRootContraction(Config config, long seed, List<Action> actions) {
        run(config, seed, actions, config.empty(), true);
    }

    static void run(Config config, long seed, List<Action> actions,
            RTree<String, Geometry> empty, boolean skipRootContraction) {
        try {
            new StateMachineRunner(config, seed, empty, skipRootContraction).run(seed, actions);
        } catch (AssertionError failure) {
            throw new AssertionError(
                    RTreeStateMachineTest.buildReplayMessage(config, seed, actions, failure),
                    failure);
        }
    }
}
