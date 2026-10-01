package item;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import item.ItemAPI.*;

/** Test-only collaborators let the wiring checks run before pending component PRs merge. */
public final class ManagerLifecycleFixture {
    public int starts, updates, stops;
    public long lastDelta;
    public PlayerSnapshot lastPlayer;
    public LevelRules lastRules;
    public final ItemManager manager;

    public ManagerLifecycleFixture() {
        ItemDefinitions definitions = new Catalog();
        manager = new ItemManager(definitions, new Drops(definitions), new ItemInventory(2), new Effects());
    }

    private static final class Catalog extends ItemDefinitions {
        private final ItemInfo shield = new ItemInfo("shield", "Shield", "", "shield",
            ActivationMode.MANUAL, EffectKind.SHIELD, DurationKind.TIMED,
            10000L, 1, null, EnumSet.of(GrantTiming.NOW));

        @Override ItemInfo find(String id) { return "shield".equals(id) ? shield : null; }
        @Override List<ItemInfo> all() { return Collections.singletonList(shield); }
        @Override void validate(LevelRules rules) { ItemAPI.required(rules, "rules"); }
    }

    private final class Drops extends ItemDropSystem {
        Drops(ItemDefinitions definitions) { super(definitions, new Random(0)); }
        @Override void beginLevel(LevelRules rules) { starts++; lastRules = rules; }
        @Override Frame advance(long delta, PlayerSnapshot player) {
            updates++; lastDelta = delta; lastPlayer = player;
            return new Frame(Collections.<DropView>emptyList(), Collections.<DropView>emptyList());
        }
        @Override List<DropView> snapshot() { return Collections.emptyList(); }
        @Override void clear() { stops++; }
    }

    private static final class Effects extends ItemEffectSystem {
        @Override List<Ended> advance(long delta) { return Collections.emptyList(); }
        @Override List<EffectView> snapshot() { return Collections.emptyList(); }
        @Override List<Ended> clear() { return Collections.emptyList(); }
    }
}
