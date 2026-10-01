package integration;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import item.ItemAPI;
import item.ItemManager;
import item.ManagerLifecycleFixture;
import item.ItemAPI.*;

/** Runs from another package, like the real game loop and feature callers. */
public final class ItemManagerLifecycleTest {
    private static int checks;

    public static void main(String[] args) {
        construction();
        publicBoundary();
        sharedStateAndLifecycle();
        System.out.println("PASS: 3 scenarios, " + checks + " checks");
    }

    private static void construction() {
        ItemManager manager = new ItemManager(2, new Random(0));
        check(new ItemAPI(manager).getView().slots.size() == 2, "public construction retains capacity");
        expect(IllegalArgumentException.class, () -> new ItemAPI(null));
        expect(IllegalArgumentException.class, () -> new ItemManager(0, new Random(0)));
        expect(IllegalArgumentException.class, () -> new ItemManager(2, null));
    }

    private static void publicBoundary() {
        Set<String> operations = new HashSet<String>();
        for (Method method : ItemManager.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) operations.add(method.getName());
        }
        check(operations.equals(new HashSet<String>(Arrays.asList("beginLevel", "update", "endLevel"))),
            "manager exposes only game-loop operations");
        for (Method method : ItemAPI.class.getMethods()) {
            check(!operations.contains(method.getName()), "API does not expose game-loop operations");
        }
    }

    private static void sharedStateAndLifecycle() {
        ManagerLifecycleFixture fixture = new ManagerLifecycleFixture();
        ItemManager manager = fixture.manager;
        ItemAPI shop = new ItemAPI(manager);
        ItemAPI hud = new ItemAPI(manager);
        GrantRequest request = new GrantRequest("purchase-1", "shield", GrantSource.SHOP, GrantTiming.NOW);
        GrantResult granted = shop.tryGrant(request);
        check(granted.status == GrantStatus.STORED, "shop stores shield before level start");
        check(hud.getView().slots.get(0).itemId.equals("shield"), "HUD sees same inventory");
        check(hud.tryGrant(request) == granted, "API instances share request receipts");
        check(hud.getView().slots.get(1) == null, "retry does not grant twice");
        check(hud.drainEvents().size() == 1 && shop.drainEvents().isEmpty(), "shared event queue drains once");

        PlayerSnapshot player = new PlayerSnapshot(new Bounds(10, 10, 5, 5), true, true);
        expect(IllegalStateException.class, () -> manager.update(0, player));
        LevelRules rules = new LevelRules("level-1", 0, 100, 0, 100, 10, 5, 5, 1000,
            new EnumMap<DropSource, DropRule>(DropSource.class));
        LifePort port = new LifePort() {
            public boolean canAddLife() { return true; }
            public boolean tryAddLife() { return true; }
        };
        manager.beginLevel(rules, port);
        check(fixture.starts == 1 && fixture.lastRules == rules, "loop installs rules");
        expect(IllegalStateException.class, () -> manager.beginLevel(rules, port));
        check(hud.useSlot(1) == UseResult.PLAYER_UNAVAILABLE, "player unavailable before first frame");
        manager.update(16, player);
        check(fixture.updates == 1 && fixture.lastDelta == 16 && fixture.lastPlayer == player,
            "loop forwards elapsed time and player");
        check(hud.useSlot(1) == UseResult.EMPTY_SLOT, "API sees player supplied by loop");
        expect(IllegalArgumentException.class, () -> manager.update(-1, player));
        check(fixture.updates == 1, "invalid frame leaves collaborators untouched");

        manager.endLevel();
        manager.endLevel();
        check(fixture.stops == 1, "level cleanup is idempotent");
        check(hud.useSlot(0) == UseResult.LEVEL_NOT_ACTIVE, "API sees level ended");
        check(hud.getView().slots.get(0) != null, "inventory survives level end");
        manager.beginLevel(rules, port);
        check(fixture.starts == 2, "same manager starts another level");
        check(hud.useSlot(1) == UseResult.PLAYER_UNAVAILABLE, "new level clears previous player");
        check(shop.tryGrant(request) == granted, "receipt survives level transition");
        check(hud.getView().slots.get(0) != null, "inventory survives next level start");
        manager.endLevel();
        check(new ItemAPI(new ItemManager(2, new Random(0))).getView().slots.get(0) == null,
            "new game has independent empty inventory");
    }

    private static void expect(Class<? extends RuntimeException> type, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            check(type.isInstance(failure), "expected " + type.getSimpleName());
            return;
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
