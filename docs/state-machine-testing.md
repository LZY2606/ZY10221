# Immutable R-tree state machine tests

`src/test/java/com/github/davidmoten/rtree/StateMachineTest.java` is a
fixed-seed, model-based test suite for the persistence (immutable versioning)
guarantees of `RTree`. This document records the semantics under test,
complexity implications and compatibility trade-offs.

## Model

Every run starts from an empty tree and applies a deterministic sequence of
actions generated from a 64-bit seed:

- `add(value, geometry)`
- `delete(value, geometry)` and `delete(..., all=true)`
- delete-all (delete every currently present entry in one call)
- `search(rectangle)`
- `nearest(rectangle, maxDistance, maxCount)`
- `size` / `isEmpty`
- FlatBuffers serialization round-trip (`SerializerFlatBuffers`,
  `InternalStructure.DEFAULT`)

A trivial reference multiset is maintained in lock-step with the tree. Entries
carry a unique sequential id even when their value and geometry are identical,
so duplicate entries are distinguishable and the shrinker cannot collapse
their identities. Values are drawn from a small alphabet and geometries live
on a small integer grid (including degenerate point-like rectangles, identical
geometries and rectangles that only touch along a boundary).

After each action the test asserts:

- `size()` and `isEmpty()` agree with the reference;
- `search(rectangle)` returns the reference multiset (order is irrelevant);
- `nearest` returns at most `maxCount` entries whose distances are strictly
  less than `maxDistance`. The library does not commit to an ordering of
  equal-distance entries (`BoundedPriorityQueue` keeps whichever ties arrive
  first), so a cut that lands inside a distance-tied group is compared on the
  distance multiset and membership only; the tie-free case compares the exact
  entry multiset.
- earlier snapshots are rechecked at random and again at the end of the run:
  an `add`/`delete` on a later version must never alter the entries of a
  previously published tree (path copying with subtree reuse).

## Internal structural invariants

The test walks the node graph of every intermediate tree and asserts:

- an empty tree has no root, depth 0, size 0 and empty `mbr()`;
- a leaf root holds 1..maxChildren entries;
- an internal root has at least two children — when a deletion leaves the root
  with a single child the height must contract (see below);
- non-root leaves hold minChildren..maxChildren entries and non-root internal
  nodes hold 1..maxChildren children. Non-root internal nodes can transiently
  contain one child because the implementation only redistributes an internal
  node when it becomes empty, relying on the root contraction for the final
  shape;
- every node geometry equals the minimum bounding rectangle of exactly its
  children (leaves: entries), so MBRs are tightened as well as grown along a
  copied delete path;
- recursively counted entries equal `size()`.

## Height contraction on delete (behaviour fix)

Before these tests, `RTree.delete` could leave the root as an internal node
with a single child after redistribution of underflowed nodes (observable at
depth 4 with eight entries remaining for `maxChildren=4, minChildren=1`).
`RTree.delete` now collapses the resulting single-child internal chain at the
root after orphaned entries have been re-added:

- complexity: the collapse walks at most the height of the tree, i.e.
  O(log n) node hops, which is already the delete complexity bound;
- compatibility: query results, `size()`, insertion and serialization are
  unaffected. `calculateDepth()` may now be smaller after deletions (the
  documented intent of height contraction); the tree is still immutable and
  `delete` returns the same instance when nothing matched.

## Backpressure

For search and for the full entry scan after a round-trip, the test subscribes
with zero initial demand and drains with batched `request(n)` calls
(1, 2, 4, ... plus a remainder, never `Long.MAX_VALUE`). The merged batched
multiset must equal the one-shot request. This exercises the bounded-request
producer in `OnSubscribeSearch` rather than the no-backpressure fast path.

## Non-finite geometry coordinates

The reference model does not define behaviour for NaN or infinite
coordinates. The existing geometry contract is pinned separately:

- `Geometries.rectangle` rejects NaN via `x2 >= x1` / `y2 >= y1`
  (`IllegalArgumentException`);
- infinite coordinates are not rejected by the current contract and the tests
  do not impose any new rule on them.

## Failures, replay and shrinking

On failure the test prints:

- the tree configuration (max/min children) and seed;
- the failing step index;
- the shortest failing action prefix produced by the delta-debugging
  shrinker (actions are removed greedily while the prefix still fails; item
  ids embedded in the actions preserve duplicate-entry identity);
- the full replayable action listing.

Replay a single run with:

```
mvn test -Dtest=StateMachineTest#fixedSeedsStateMachine \
  -Drtree.test.seed=<seed> -Drtree.test.spec=<0..3>
```

The tests use no network, no wall-clock timing and no filesystem traversal;
serialization round-trips go through in-memory byte arrays.

## Mutation evidence

Two deliberate mutations were applied to production code and both were caught
by these tests (mutations were reverted afterwards):

1. rebuilding leaf nodes after delete without recomputing the MBR failed with
   `node MBR not tightened to children: node mbr=... children mbr=...`;
2. skipping the root single-child contraction failed with
   `internal root must have >=2 children (height must shrink after
   deletion), got 1`.
