# Manager test foundation (part 1)

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

The new suite checks infrastructure wiring, scripting, recording and isolation.
It does not establish correctness of production drop physics/effect timers or
provide the full manager behavior matrix; those scenarios are reserved for part 2.
