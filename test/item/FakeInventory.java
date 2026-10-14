package item;

import java.util.Arrays;
import java.util.List;
import item.ItemAPI.*;

/** Independent slot storage; never calls production inventory operations. */
public final class FakeInventory extends ItemInventory {
    private final ItemInfo[] slots;
    private final List<String> calls;
    FakeInventory(int capacity, List<String> calls) {
        super(capacity);
        slots = new ItemInfo[capacity];
        this.calls = calls;
    }
    @Override public int capacity() { return slots.length; }
    @Override public int firstEmptySlot() {
        for (int i = 0; i < slots.length; i++) if (slots[i] == null) return i;
        return -1;
    }
    @Override public ItemInfo at(int slot) { return slots[slot]; }
    @Override public void store(int slot, ItemInfo item) {
        ItemAPI.required(item, "item");
        if (item.activationMode != ActivationMode.MANUAL) throw new IllegalArgumentException("manual item required");
        if (slots[slot] != null) throw new IllegalStateException("occupied slot");
        slots[slot] = item;
        calls.add("inventory.store:" + slot);
    }
    @Override public void consume(int slot) {
        if (slots[slot] == null) throw new IllegalStateException("empty slot");
        slots[slot] = null;
        calls.add("inventory.consume:" + slot);
    }
    @Override public List<ItemInfo> snapshot() { return ItemAPI.frozen(Arrays.asList(slots), true); }
}
