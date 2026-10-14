package item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import item.ItemAPI.*;

/** One fixture per scenario. Existing package-private constructor is the injection boundary. */
public final class ManagerScenarioFixture implements AutoCloseable {
    private final List<String> calls = new ArrayList<String>();
    private final Catalog catalog = new Catalog();
    public final FakeInventory inventory;
    public final FakeEffectSystem effects = new FakeEffectSystem(calls);
    public final FakeDropSystem drops = new FakeDropSystem(catalog, calls);
    public final ItemManager manager;
    public final ItemAPI api;
    public final EventRecorder events;
    public final TestLifePort life = new TestLifePort();
    public ManagerScenarioFixture() { this(2); }
    public ManagerScenarioFixture(int capacity) {
        inventory = new FakeInventory(capacity, calls);
        manager = new ItemManager(catalog, drops, inventory, effects);
        api = new ItemAPI(manager);
        events = new EventRecorder(api);
    }
    public ItemInfo item(String id, EffectKind kind) {
        boolean manual = kind == EffectKind.SHIELD || kind == EffectKind.FREEZE;
        boolean instant = kind == EffectKind.LIFE;
        ItemInfo item = new ItemInfo(id, id, "test item", id,
            manual ? ActivationMode.MANUAL : ActivationMode.ON_PICKUP, kind,
            manual ? DurationKind.TIMED : instant ? DurationKind.INSTANT : DurationKind.UNTIL_LEVEL_END,
            manual ? Long.valueOf(kind == EffectKind.SHIELD ? 10000 : 5000) : null,
            kind == EffectKind.SHIELD ? Integer.valueOf(1) : null,
            manual || instant ? null : Double.valueOf(1.5),
            manual || instant ? EnumSet.of(GrantTiming.NOW) : EnumSet.allOf(GrantTiming.class));
        register(item);
        return item;
    }
    public void register(ItemInfo item) {
        if (catalog.items.containsKey(item.itemId)) throw new IllegalArgumentException("duplicate test item ID");
        catalog.items.put(item.itemId, item);
    }
    public void fillInventory(ItemInfo item) {
        while (inventory.firstEmptySlot() >= 0) inventory.store(inventory.firstEmptySlot(), item);
    }
    public DropView floorItem(ItemInfo item) { return drops.add(item, new Bounds(10, 10, 5, 5)); }
    public void startLevel(String id) {
        Map<DropSource, DropRule> rules = new EnumMap<DropSource, DropRule>(DropSource.class);
        for (DropSource source : DropSource.values()) rules.put(source, new DropRule(0, Collections.<String, Double>emptyMap()));
        manager.beginLevel(new LevelRules(id, 0, 100, 0, 100, 10, 5, 5, 1000, rules), life);
    }
    public void tick(long delta) { tick(delta, true, true); }
    public void tick(long delta, boolean pickup, boolean use) {
        manager.update(delta, new PlayerSnapshot(new Bounds(10, 10, 5, 5), pickup, use));
    }
    public void endLevel() { manager.endLevel(); }
    public List<String> calls() { return ItemAPI.frozen(calls, false); }
    public void clearCalls() { calls.clear(); }
    @Override public void close() { endLevel(); events.capture(); }
    public static final class TestLifePort implements LifePort {
        public int lives = 3, maximum = 5, additions;
        public boolean canAddLife() { return lives < maximum; }
        public boolean tryAddLife() { if (!canAddLife()) return false; lives++; additions++; return true; }
    }
    private static final class Catalog extends ItemDefinitions {
        final Map<String, ItemInfo> items = new LinkedHashMap<String, ItemInfo>();
        @Override ItemInfo find(String id) { return items.get(id); }
        @Override List<ItemInfo> all() { return ItemAPI.frozen(new ArrayList<ItemInfo>(items.values()), false); }
        @Override void validate(LevelRules rules) { ItemAPI.required(rules, "rules"); }
    }
}
