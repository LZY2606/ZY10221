package com.github.davidmoten.rtree.statemachine;

import java.util.List;

import org.junit.Test;

import com.github.davidmoten.rtree.statemachine.StateMachineRunner.Config;

/**
 * Longer, higher-seed-count soak run of the state machine. Kept separate from
 * the parameterized {@link RTreeStateMachineTest} so the ordinary suite stays
 * fast while still giving the delete/split/contraction logic broad coverage.
 */
public class StateMachineSoakTest {

    @Test
    public void longHistoriesAcrossAllConfigurations() {
        long[] seeds = { 101L, 202L, 303L, 404L, 505L, 777L, 31415L, 271828L };
        for (Config config : Config.values()) {
            for (long seed : seeds) {
                List<Action> actions = Generator.generate(seed, 900);
                RTreeStateMachineTest.run(config, seed, actions);
            }
        }
    }
}
