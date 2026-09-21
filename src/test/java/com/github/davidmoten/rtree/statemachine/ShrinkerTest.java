package com.github.davidmoten.rtree.statemachine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.github.davidmoten.rtree.statemachine.RefGeometry.Rect;

public class ShrinkerTest {

    private static Rect rect(int x, int y) {
        return Rect.point(x, y);
    }

    @Test
    public void reducesToShortestFailingPrefix() {
        // failure only once at least 3 actions ran
        List<Action> actions = actions(add(0, "a"), add(1, "b"), add(2, "c"), add(3, "d"));
        List<Action> shrunk = Shrinker.shrink(actions, candidate -> {
            if (candidate.size() >= 3) {
                throw new AssertionError("boom");
            }
        });
        assertEquals(3, shrunk.size());
        assertEquals(actions.get(0), shrunk.get(0));
        assertEquals(actions.get(2), shrunk.get(2));
    }

    @Test
    public void removesIrrelevantActionsWhilePreservingDuplicateIdentity() {
        // The defect requires BOTH duplicate adds of 'a' to be present (two
        // equal entries). A shrinker that merged duplicates would hide it.
        Action addA1 = add(10, "a");
        Action addA2 = add(11, "a");
        Action addB = add(12, "b");
        Action deleteB = new Action.Delete("b", rect(2, 2), false, true);
        List<Action> actions = actions(addA1, addA2, addB, deleteB);

        List<Action> shrunk = Shrinker.shrink(actions, candidate -> {
            int adds = 0;
            for (Action action : candidate) {
                if (action instanceof Action.Add && ((Action.Add) action).value.equals("a")) {
                    adds++;
                }
            }
            if (adds < 2) {
                return; // defect hidden if duplicate occurrences are merged
            }
            throw new AssertionError("duplicate-sensitive defect");
        });

        int aAdds = 0;
        for (Action action : shrunk) {
            if (action instanceof Action.Add && ((Action.Add) action).value.equals("a")) {
                aAdds++;
            }
        }
        assertEquals("both duplicate 'a' occurrences must survive shrinking", 2, aAdds);
        assertTrue("irrelevant b actions should be removable", shrunk.size() <= 3);
    }

    private static Action.Add add(long id, String value) {
        return new Action.Add(id, value, rect((int) id, (int) id));
    }

    private static List<Action> actions(Action... actions) {
        List<Action> list = new ArrayList<Action>();
        for (Action action : actions) {
            list.add(action);
        }
        return list;
    }
}
