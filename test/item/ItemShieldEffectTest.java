package item;

import java.util.EnumSet;
import java.util.List;
import item.ItemAPI.*;

/** Standalone shield checks, including interaction with expiration and level cleanup. */
public final class ItemShieldEffectTest {
    private static int checks;

    public static void main(String[] args) {
        noShieldDoesNotBlock();
        singleChargeIsConsumedOnce();
        configuredChargesAndImmutableViews();
        expirationPreventsBlocking();
        levelCleanupPreventsBlocking();
        blockingDoesNotChangeOtherEffects();
        exhaustedShieldCanBeReapplied();
        System.out.println("PASS: 7 shield scenarios, " + checks + " checks");
    }

    private static void noShieldDoesNotBlock() {
        ItemEffectSystem effects = new ItemEffectSystem();
        check(effects.tryBlockHit() == null, "no shield cannot block");
        check(effects.tryBlockHit() == null, "repeated miss remains harmless");
        check(effects.snapshot().isEmpty(), "miss creates no effect");
        check(apply(effects, shield(50, 1)) == 1, "miss does not allocate effect IDs");
    }

    private static void singleChargeIsConsumedOnce() {
        ItemEffectSystem effects = new ItemEffectSystem();
        long id = apply(effects, shield(10000, 1));
        effects.advance(2500);
        ItemEffectSystem.Hit hit = effects.tryBlockHit();
        check(hit != null && hit.exhausted, "one-charge shield is exhausted on first hit");
        check(hit.before.effectId == id && hit.before.item.itemId.equals("shield"), "hit identifies shield");
        check(hit.before.remainingCharges == 1, "hit includes charge count before consumption");
        check(hit.before.remainingMillis == 7500, "hit includes time before consumption");
        check(effects.snapshot().isEmpty(), "spent shield removed immediately");
        check(effects.tryBlockHit() == null, "same shield cannot block a second hit");
        check(effects.advance(10000).isEmpty(), "spent shield does not expire again");
        check(effects.clear().isEmpty(), "spent shield is not ended twice");
    }

    private static void configuredChargesAndImmutableViews() {
        ItemEffectSystem effects = new ItemEffectSystem();
        long id = apply(effects, shield(1234, 3));
        EffectView original = effects.snapshot().get(0);
        ItemEffectSystem.Hit first = effects.tryBlockHit();
        check(first != null && !first.exhausted, "configured three-charge shield survives first hit");
        check(first.before.remainingCharges == 3, "first hit captures three charges");
        EffectView afterFirst = effects.snapshot().get(0);
        check(afterFirst.remainingCharges == 2, "one hit spends exactly one charge");
        check(afterFirst.remainingMillis == 1234, "blocking does not reset or advance time");
        ItemEffectSystem.Hit second = effects.tryBlockHit();
        check(second != null && !second.exhausted && second.before.remainingCharges == 2,
                "second hit leaves one charge");
        ItemEffectSystem.Hit third = effects.tryBlockHit();
        check(third != null && third.exhausted && third.before.remainingCharges == 1,
                "last configured charge exhausts shield");
        check(third.before.effectId == id, "all hits belong to the same effect");
        check(first.before.remainingCharges == 3 && original.remainingCharges == 3,
                "previous views are not mutated by later hits");
        check(afterFirst.remainingCharges == 2, "intermediate snapshot remains immutable");
        check(effects.tryBlockHit() == null, "no extra hit beyond configured capacity");
    }

    private static void expirationPreventsBlocking() {
        ItemEffectSystem effects = new ItemEffectSystem();
        long id = apply(effects, shield(10, 2));
        check(effects.advance(9).isEmpty(), "shield remains before expiry boundary");
        ItemEffectSystem.Hit hit = effects.tryBlockHit();
        check(hit != null && !hit.exhausted && hit.before.remainingMillis == 1,
                "valid shield blocks immediately before expiry");
        List<ItemEffectSystem.Ended> ended = effects.advance(1);
        check(ended.size() == 1 && ended.get(0).effectId == id
                && ended.get(0).reason == EffectEndReason.EXPIRED, "remaining charge expires at boundary");
        check(effects.tryBlockHit() == null, "expired shield cannot block despite unused charge");
        check(effects.clear().isEmpty(), "expired shield is not ended by clear again");
    }

    private static void levelCleanupPreventsBlocking() {
        ItemEffectSystem effects = new ItemEffectSystem();
        long id = apply(effects, shield(100, 2));
        List<ItemEffectSystem.Ended> ended = effects.clear();
        check(ended.size() == 1 && ended.get(0).effectId == id
                && ended.get(0).reason == EffectEndReason.LEVEL_ENDED, "level cleanup ends active shield");
        check(effects.tryBlockHit() == null, "shield cannot block after level cleanup");
        check(effects.advance(100).isEmpty(), "cleared shield cannot expire again");
    }

    private static void blockingDoesNotChangeOtherEffects() {
        ItemEffectSystem effects = new ItemEffectSystem();
        long freezeId = apply(effects, item("freeze", EffectKind.FREEZE, DurationKind.TIMED, 500L, null, null));
        apply(effects, item("rapid", EffectKind.RAPID_FIRE, DurationKind.UNTIL_LEVEL_END, null, null, 1.8));
        apply(effects, item("speed", EffectKind.BULLET_SPEED, DurationKind.UNTIL_LEVEL_END, null, null, 1.06));
        check(effects.tryBlockHit() == null, "other effects do not act as shields");
        apply(effects, shield(100, 1));
        check(effects.tryBlockHit().exhausted, "shield alone is consumed");
        List<EffectView> remaining = effects.snapshot();
        check(remaining.size() == 3 && remaining.get(0).effectId == freezeId, "other effects retained in order");
        check(remaining.get(0).remainingMillis == 500, "freeze timer unchanged by blocking");
        Modifiers modifiers = effects.modifiers();
        check(modifiers.enemyMovementBlocked && modifiers.fireRateMultiplier == 1.8
                && modifiers.bulletSpeedMultiplier == 1.06, "other effect modifiers unchanged");
        check(effects.tryBlockHit() == null, "other effects remain unable to block");
    }

    private static void exhaustedShieldCanBeReapplied() {
        ItemEffectSystem effects = new ItemEffectSystem();
        ItemInfo item = shield(75, 1);
        long first = apply(effects, item);
        check(effects.tryBlockHit().exhausted, "first shield spent");
        check(effects.check(item, null) == null, "spent shield no longer rejects same kind");
        long second = apply(effects, item);
        check(second == first + 1, "blocking does not consume or reuse effect IDs");
        ItemEffectSystem.Hit hit = effects.tryBlockHit();
        check(hit != null && hit.exhausted && hit.before.effectId == second,
                "new shield independently blocks a hit");
        check(hit.before.remainingMillis == 75 && hit.before.remainingCharges == 1,
                "new shield starts with fresh configured time and charges");
    }

    private static ItemInfo shield(long duration, int charges) {
        return item("shield", EffectKind.SHIELD, DurationKind.TIMED, duration, charges, null);
    }

    private static ItemInfo item(String id, EffectKind kind, DurationKind duration,
                                 Long millis, Integer charges, Double magnitude) {
        ActivationMode mode = duration == DurationKind.UNTIL_LEVEL_END
                ? ActivationMode.ON_PICKUP : ActivationMode.MANUAL;
        return new ItemInfo(id, id, "test", "item." + id, mode, kind, duration,
                millis, charges, magnitude, EnumSet.of(GrantTiming.NOW));
    }

    private static long apply(ItemEffectSystem effects, ItemInfo item) {
        ItemEffectSystem.Applied applied = effects.apply(item, null);
        check(applied.failure == null && applied.effect != null, "apply " + item.itemId);
        return applied.effect.effectId;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
