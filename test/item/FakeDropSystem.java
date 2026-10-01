package item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import item.ItemAPI.*;

/** Explicit frame inputs; no random drop, movement or collision algorithm is duplicated. */
public final class FakeDropSystem extends ItemDropSystem {
    private final Map<Long, DropView> items = new LinkedHashMap<Long, DropView>();
    private final Set<Long> contacts = new LinkedHashSet<Long>(), expired = new LinkedHashSet<Long>();
    private final List<String> calls;
    private long nextId = 1;
    private ItemInfo nextSpawn;
    private LevelRules rules;
    public long lastDelta;
    public PlayerSnapshot lastPlayer;
    FakeDropSystem(ItemDefinitions definitions, List<String> calls) { super(definitions, new Random(0)); this.calls = calls; }
    public DropView add(ItemInfo item, Bounds bounds) {
        if (rules == null) throw new IllegalStateException("start a level first");
        DropView drop = new DropView(nextId++, item, bounds, false);
        items.put(drop.dropId, drop);
        return drop;
    }
    public void contact(long id) { requireDrop(id); contacts.add(id); }
    public void expire(long id) { requireDrop(id); expired.add(id); }
    public void spawnNext(ItemInfo item) { nextSpawn = ItemAPI.required(item, "item"); }
    @Override void beginLevel(LevelRules rules) { clear(); this.rules = rules; calls.add("drops.begin"); }
    @Override DropView spawn(DropSource source, double x, double y) {
        calls.add("drops.spawn:" + source);
        if (nextSpawn == null) return null;
        ItemInfo item = nextSpawn; nextSpawn = null;
        return add(item, new Bounds(x - rules.pickupWidth / 2, y - rules.pickupHeight / 2,
            rules.pickupWidth, rules.pickupHeight));
    }
    @Override Frame advance(long delta, PlayerSnapshot player) {
        lastDelta = delta; lastPlayer = player; calls.add("drops.advance");
        List<DropView> gone = new ArrayList<DropView>(), touching = new ArrayList<DropView>();
        for (DropView drop : snapshot()) {
            if (expired.contains(drop.dropId)) { items.remove(drop.dropId); gone.add(drop); }
            else if (player.canPickup && contacts.contains(drop.dropId)) touching.add(drop);
        }
        contacts.clear(); expired.clear();
        return new Frame(gone, touching);
    }
    @Override void completePickup(long id) {
        requireDrop(id); items.remove(id); calls.add("drops.pickup:" + id);
    }
    @Override public List<DropView> snapshot() { return ItemAPI.frozen(new ArrayList<DropView>(items.values()), false); }
    @Override void clear() {
        items.clear(); contacts.clear(); expired.clear(); nextSpawn = null; rules = null; calls.add("drops.clear");
    }
    private void requireDrop(long id) {
        if (!items.containsKey(id)) throw new IllegalArgumentException("unknown drop: " + id);
    }
}
