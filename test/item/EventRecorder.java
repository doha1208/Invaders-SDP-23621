package item;

import java.util.ArrayList;
import java.util.List;
import item.ItemAPI.*;

/** Observes real manager events; never manufactures or injects ItemEvents. */
public final class EventRecorder {
    private final ItemAPI api;
    private final List<ItemEvent> recorded = new ArrayList<ItemEvent>();
    EventRecorder(ItemAPI api) { this.api = api; }
    public List<ItemEvent> capture() {
        List<ItemEvent> batch = api.drainEvents();
        recorded.addAll(batch);
        return batch;
    }
    public List<ItemEvent> snapshot() { return ItemAPI.frozen(recorded, false); }
    public List<EventType> types() {
        List<EventType> result = new ArrayList<EventType>();
        for (ItemEvent event : recorded) result.add(event.type);
        return ItemAPI.frozen(result, false);
    }
    public void clear() { recorded.clear(); }
}
