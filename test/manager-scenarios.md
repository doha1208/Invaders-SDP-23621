# Manager test foundation and behavior scenarios

Prerequisite: the lifecycle/API ownership change on `team-cs/item-manager-review`.
This branch is based on that change; until it merges, compare against that branch
to review only the test foundation. Merge the prerequisite before targeting main
with only these changes. No production classes are changed by this part.

Run with JDK 17 or later: `bash test/run-manager-tests.sh` (macOS/Linux/Git Bash).
The script uses a fresh temporary build directory, removes it on exit, and runs
both the existing lifecycle suite and the new foundation suite. CI also runs it.
In native PowerShell:

```powershell
javac --release 17 -encoding UTF-8 -Xlint:all -d bin/manager-tests (Get-ChildItem -Recurse src,test -Filter *.java).FullName
java -Djava.awt.headless=true -cp bin/manager-tests integration.ItemManagerLifecycleTest
java -Djava.awt.headless=true -cp bin/manager-tests integration.ItemManagerIntegrationTest
```

`ManagerScenarioFixture` assembles a real manager/API with independent fake
inventory, effects, drops and a fake catalog. All collaborator methods used by
the manager are overridden, so production STUB methods are never called. Existing
package-private method contracts and the injection constructor are the seam;
this test-only change introduces no new production interfaces or reflection.
The fixtures still compile against those contracts: an incompatible signature
change should fail compilation rather than silently bypass the manager.

Create a new fixture in try-with-resources for each scenario. `close()` ends the
level and captures remaining events; a new fixture resets IDs, receipts, state
and scripts. `endLevel()` simulates a level transition within the same game.

```java
try (ManagerScenarioFixture f = new ManagerScenarioFixture(2)) {
    ItemInfo shield = f.item("shield", EffectKind.SHIELD);
    f.startLevel("level-1");
    DropView drop = f.floorItem(shield);
    f.drops.contact(drop.dropId);
    f.tick(16);
    List<ItemEvent> batch = f.events.capture();
    // Assert manager results through f.api.getView(), batch, or f.calls().
}
```

Use `register(ItemInfo)` for custom definitions, `fillInventory` for full slots,
`drops.spawnNext/contact/expire` for scripted drop outcomes, and
`effects.rejectApplications/endOnNextAdvance/blockNextHit/setModifiers` for
effect outcomes. Rejection persists until set to null; contacts/expiry/hits are
one-frame/one-call scripts. Queue spawn after starting the level. Expiration is
explicit, not based on elapsed time. `floorItem` creates a world item at a fixed
test position, without simulating falling or grounding.

`events.capture()` drains real manager events; `snapshot()` reads accumulated
events and `clear()` clears only the recorder. `calls()` records collaborator
mutations in order; `clearCalls()` removes setup calls before assertions.
Fake modifiers are configured explicitly and are not computed from effects.

## Part 2: manager behavior

Branch `team-cs/item-manager-scenarios` builds on part 1 at
`team-cs/item-manager-test-base`. Compare against part 1 to review only the new
scenarios; merge the prerequisite first before making a main-based PR diff.
The same command above runs the 4 foundation and 6 behavior scenarios plus the
existing lifecycle suite. Part 2 changes only this document and
`integration/ItemManagerIntegrationTest.java`; it reuses all existing fakes.

| Scenario | Assertions |
| --- | --- |
| Full inventory | Repeated contact keeps the original slot and floor drop, emits no acquisition, and allows collection once space is freed. |
| Failed effect | Both rejection reasons keep the slot, create no effect/events, and a successful retry applies before consuming. |
| Repeated request ID | A new request object with the same content returns the first receipt, writes one slot, and emits one grant event, even after the item is consumed. |
| Next-level reservation | Both rapid fire and bullet speed queue between levels, apply once without consuming a slot, and do not reactivate on retries or a third level. |
| Level end | Two effects and two drops are removed from collaborator state, inventory layout persists, and each ending keeps its level/reason without duplicate cleanup. |
| Event order | Actual order is ITEM_COLLECTED, ITEM_USED, EFFECT_STARTED, EFFECT_ENDED; validate counts, IDs, payload links and no later duplicate endings. |

The requested conceptual ACQUIRED/USED names correspond to ITEM_COLLECTED and
ITEM_USED in ItemAPI. EFFECT_STARTED is also part of the real event contract and
is included in the full sequence instead of being discarded.

These tests execute the real ItemManager/ItemAPI coordination. Fakes script
collaborator outcomes, so this does not validate production drop physics,
effect timers, or full in-game integration. Expiration is explicitly queued by
the fixture; the test verifies how the manager handles that result.
