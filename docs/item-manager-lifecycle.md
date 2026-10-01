# Item manager lifecycle

Create one manager per game run and keep it across levels. Give the same API to
the shop, input, combat and HUD. Start a new manager for a new game run.

```java
import item.ItemManager;
import item.ItemAPI;
import java.util.Random;

// Game initialization; capacity is chosen by the game.
ItemManager manager = new ItemManager(inventoryCapacity, new Random());
ItemAPI itemAPI = new ItemAPI(manager);

// Game loop (rules, lifePort and playerSnapshot are supplied by the game).
manager.beginLevel(rules, lifePort);
manager.update(deltaGameMillis, playerSnapshot);
manager.endLevel();
```

The example shows the calling contract, not a complete runnable game. Definitions,
drops and effects still have stubs on main at the time of this change. This PR
does not connect the game screen or implement those collaborators.

## Migrating callers

- Replace `new ItemAPI(capacity, random)` with a manager plus `new ItemAPI(manager)`.
- Move `itemAPI.beginLevel(...)`, `itemAPI.update(...)`, and `itemAPI.endLevel()`
  calls to that manager. These methods are removed from `ItemAPI`.
- Continue using `ItemAPI` for grants, inventory use, enemy defeat notifications,
  shield hit handling, modifiers, views and event draining.
- Use one game-state thread; do not call back into the manager from `LifePort`.
- Supply elapsed game time in milliseconds and the current player snapshot.
  Start each level before updating it; finish it before starting another.
- End-of-level cleanup preserves inventory, grant receipts, unapplied reservations
  and undrained events. Assign a single event dispatcher to drain and distribute
  events to sound and visuals.

## Verify the boundary

From the repository root with JDK 17 or later (macOS/Linux):

```sh
mkdir -p /tmp/item-manager-tests
find src test -name '*.java' > /tmp/item-manager-tests/sources.txt
javac --release 17 -encoding UTF-8 -Xlint:all -d /tmp/item-manager-tests @/tmp/item-manager-tests/sources.txt
java -Djava.awt.headless=true -cp /tmp/item-manager-tests integration.ItemManagerLifecycleTest
```

The test calls the public interface from outside the `item` package and checks
shared inventory/receipts/events, lifecycle transitions, and a fresh game state.
Test-only collaborators stand in for unfinished definitions/drops/effects; the
inventory and manager are real. This does not validate full gameplay or the
pending collaborator implementations.
