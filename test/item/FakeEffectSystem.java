package item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import item.ItemAPI.*;

/** Scripted collaborator: expiration, hits and modifiers are selected by tests, not simulated. */
public final class FakeEffectSystem extends ItemEffectSystem {
    private final Map<Long, EffectView> active = new LinkedHashMap<Long, EffectView>();
    private final List<Long> ending = new ArrayList<Long>();
    private final List<String> calls;
    private long nextId = 1;
    private GrantFailure rejection;
    private Modifiers configuredModifiers = Modifiers.neutral();
    private Long nextHit;
    private boolean exhaustHit;
    public long lastDelta;
    FakeEffectSystem(List<String> calls) { this.calls = calls; }
    public void rejectApplications(GrantFailure failure) {
        if (failure != null && failure != GrantFailure.EFFECT_REJECTED && failure != GrantFailure.EFFECT_ALREADY_ACTIVE)
            throw new IllegalArgumentException("invalid effect rejection");
        rejection = failure;
    }
    public void endOnNextAdvance(long id) { requireEffect(id); if (!ending.contains(id)) ending.add(id); }
    public void blockNextHit(long id, boolean exhausted) {
        EffectView effect = requireEffect(id);
        if (effect.item.effectKind != EffectKind.SHIELD || exhausted != (effect.remainingCharges == 1))
            throw new IllegalArgumentException("invalid shield hit");
        nextHit = id; exhaustHit = exhausted;
    }
    public void setModifiers(Modifiers value) { configuredModifiers = ItemAPI.required(value, "modifiers"); }
    @Override GrantFailure check(ItemInfo item, LifePort port) {
        if (rejection != null) return rejection;
        if (item.effectKind == EffectKind.LIFE) return port.canAddLife() ? null : GrantFailure.EFFECT_REJECTED;
        for (EffectView effect : active.values())
            if (effect.item.effectKind == item.effectKind) return GrantFailure.EFFECT_ALREADY_ACTIVE;
        return null;
    }
    @Override Applied apply(ItemInfo item, LifePort port) {
        calls.add("effects.apply:" + item.itemId);
        GrantFailure failure = check(item, port);
        if (failure != null) return Applied.failed(failure);
        if (item.effectKind == EffectKind.LIFE)
            return port.tryAddLife() ? Applied.ok(null) : Applied.failed(GrantFailure.EFFECT_REJECTED);
        EffectView effect = new EffectView(nextId++, item, item.durationMillis, item.charges);
        active.put(effect.effectId, effect);
        return Applied.ok(effect);
    }
    @Override List<Ended> advance(long delta) {
        if (delta < 0) throw new IllegalArgumentException("negative delta");
        lastDelta = delta; calls.add("effects.advance");
        List<Ended> result = new ArrayList<Ended>();
        for (EffectView effect : snapshot()) if (ending.contains(effect.effectId)) {
            active.remove(effect.effectId);
            result.add(new Ended(effect.item.itemId, effect.effectId, EffectEndReason.EXPIRED));
        }
        ending.clear();
        return ItemAPI.frozen(result, false);
    }
    @Override Hit tryBlockHit() {
        calls.add("effects.hit");
        if (nextHit == null) return null;
        EffectView before = active.get(nextHit);
        nextHit = null;
        if (before == null) return null;
        if (exhaustHit) active.remove(before.effectId);
        else active.put(before.effectId, new EffectView(before.effectId, before.item,
            before.remainingMillis, before.remainingCharges - 1));
        return new Hit(before, exhaustHit);
    }
    @Override public Modifiers modifiers() { return configuredModifiers; }
    @Override public List<EffectView> snapshot() { return ItemAPI.frozen(new ArrayList<EffectView>(active.values()), false); }
    @Override List<Ended> clear() {
        calls.add("effects.clear");
        List<Ended> result = new ArrayList<Ended>();
        for (EffectView effect : active.values())
            result.add(new Ended(effect.item.itemId, effect.effectId, EffectEndReason.LEVEL_ENDED));
        active.clear(); ending.clear(); nextHit = null; configuredModifiers = Modifiers.neutral();
        return ItemAPI.frozen(result, false);
    }
    private EffectView requireEffect(long id) {
        EffectView effect = active.get(id);
        if (effect == null) throw new IllegalArgumentException("unknown effect: " + id);
        return effect;
    }
}
