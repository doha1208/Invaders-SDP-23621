package item;

import java.util.EnumSet;
import java.util.List;
import item.ItemAPI.*;

/** Standalone checks: run item.ItemEffectLifecycleTest without external test libraries. */
public final class ItemEffectLifecycleTest {
    private static int checks;

    public static void main(String[] args) {
        neutralState();
        timedExpiryBoundary();
        expiryOrderAndStageLifetime();
        independentTimers();
        largeAndInvalidDelta();
        configuredModifiersAndReset();
        freshApplicationAndDuplicateRejection();
        System.out.println("PASS: 7 scenarios, " + checks + " checks");
    }

    private static void neutralState() {
        ItemEffectSystem effects = new ItemEffectSystem();
        assertModifiers(effects.modifiers(), 1.0, 1.0, false);
        check(effects.advance(0).isEmpty(), "empty zero tick");
        check(effects.advance(Long.MAX_VALUE).isEmpty(), "empty large tick");
        check(effects.clear().isEmpty(), "empty clear");
    }

    private static void timedExpiryBoundary() {
        ItemEffectSystem effects = new ItemEffectSystem();
        ItemInfo freeze = timed("freeze", EffectKind.FREEZE, 5000, null);
        long id = apply(effects, freeze);
        EffectView before = effects.snapshot().get(0);
        check(effects.advance(0).isEmpty(), "zero tick does not expire freeze");
        check(effects.snapshot().get(0).remainingMillis == 5000, "zero tick preserves timer");
        check(effects.advance(4999).isEmpty(), "freeze remains before boundary");
        check(effects.snapshot().get(0).remainingMillis == 1, "one millisecond remains");
        check(before.remainingMillis == 5000, "previous snapshot remains unchanged");
        assertModifiers(effects.modifiers(), 1.0, 1.0, true);

        List<ItemEffectSystem.Ended> ended = effects.advance(1);
        check(ended.size() == 1, "one expiry at exact boundary");
        assertEnded(ended.get(0), id, "freeze", EffectEndReason.EXPIRED);
        check(effects.snapshot().isEmpty(), "expired freeze removed");
        assertModifiers(effects.modifiers(), 1.0, 1.0, false);
        check(effects.advance(1).isEmpty(), "expiry reported only once");
        check(effects.clear().isEmpty(), "clear does not end expired freeze again");
        try {
            ended.clear();
            throw new AssertionError("expiry result must be immutable");
        } catch (UnsupportedOperationException expected) {
            checks++;
        }
    }

    private static void expiryOrderAndStageLifetime() {
        ItemEffectSystem effects = new ItemEffectSystem();
        long shieldId = apply(effects, timed("shield", EffectKind.SHIELD, 10000, 1));
        long rapidId = apply(effects, boost("rapid", EffectKind.RAPID_FIRE, 1.75));
        long freezeId = apply(effects, timed("freeze", EffectKind.FREEZE, 5000, null));
        long speedId = apply(effects, boost("speed", EffectKind.BULLET_SPEED, 1.08));
        List<ItemEffectSystem.Ended> ended = effects.advance(20000);
        check(ended.size() == 2, "large tick expires both timed effects");
        assertEnded(ended.get(0), shieldId, "shield", EffectEndReason.EXPIRED);
        assertEnded(ended.get(1), freezeId, "freeze", EffectEndReason.EXPIRED);
        List<EffectView> remaining = effects.snapshot();
        check(remaining.size() == 2, "stage effects survive large tick");
        check(remaining.get(0).effectId == rapidId, "first stage effect preserved");
        check(remaining.get(1).effectId == speedId, "second stage effect preserved");
        check(remaining.get(0).remainingMillis == null, "stage effect has no timed deadline");
        assertModifiers(effects.modifiers(), 1.75, 1.08, false);
    }

    private static void independentTimers() {
        ItemEffectSystem effects = new ItemEffectSystem();
        apply(effects, timed("shield", EffectKind.SHIELD, 1234, 2));
        long freezeId = apply(effects, timed("freeze", EffectKind.FREEZE, 321, null));
        List<ItemEffectSystem.Ended> ended = effects.advance(321);
        check(ended.size() == 1, "only shorter effect expires");
        assertEnded(ended.get(0), freezeId, "freeze", EffectEndReason.EXPIRED);
        EffectView shield = effects.snapshot().get(0);
        check(shield.remainingMillis == 913, "configured shield time is decremented");
        check(shield.remainingCharges == 2, "time update does not spend shield charges");
        assertModifiers(effects.modifiers(), 1.0, 1.0, false);
    }

    private static void largeAndInvalidDelta() {
        ItemEffectSystem effects = new ItemEffectSystem();
        long id = apply(effects, timed("long-freeze", EffectKind.FREEZE, Long.MAX_VALUE, null));
        for (long delta : new long[] {-1, Long.MIN_VALUE}) {
            try {
                effects.advance(delta);
                throw new AssertionError("negative delta must fail");
            } catch (IllegalArgumentException expected) {
                checks++;
            }
            check(effects.snapshot().get(0).remainingMillis == Long.MAX_VALUE,
                    "invalid tick leaves timer unchanged");
        }
        check(effects.advance(1).isEmpty(), "very long effect remains active");
        check(effects.snapshot().get(0).remainingMillis == Long.MAX_VALUE - 1, "long timer decremented");
        List<ItemEffectSystem.Ended> ended = effects.advance(Long.MAX_VALUE);
        check(ended.size() == 1, "maximum tick expires without wrapping timer");
        assertEnded(ended.get(0), id, "long-freeze", EffectEndReason.EXPIRED);
        check(effects.snapshot().isEmpty(), "no invalid remaining-time view");
    }

    private static void configuredModifiersAndReset() {
        ItemEffectSystem effects = new ItemEffectSystem();
        apply(effects, timed("shield", EffectKind.SHIELD, 100, 1));
        assertModifiers(effects.modifiers(), 1.0, 1.0, false);
        apply(effects, boost("rapid", EffectKind.RAPID_FIRE, 2.25));
        assertModifiers(effects.modifiers(), 2.25, 1.0, false);
        long speedId = apply(effects, boost("speed", EffectKind.BULLET_SPEED, 1.07));
        apply(effects, timed("freeze", EffectKind.FREEZE, 80, null));
        Modifiers before = effects.modifiers();
        for (int i = 0; i < 5; i++) assertModifiers(effects.modifiers(), 2.25, 1.07, true);
        check(effects.snapshot().get(3).remainingMillis == 80, "modifier queries do not advance time");
        check(effects.advance(80).size() == 1, "freeze ends without ending boosts");
        assertModifiers(effects.modifiers(), 2.25, 1.07, false);
        assertModifiers(before, 2.25, 1.07, true);
        List<ItemEffectSystem.Ended> cleared = effects.clear();
        check(cleared.size() == 3, "clear removes shield and both stage effects");
        assertEnded(cleared.get(2), speedId, "speed", EffectEndReason.LEVEL_ENDED);
        assertModifiers(effects.modifiers(), 1.0, 1.0, false);
        check(effects.advance(100).isEmpty(), "cleared effects never expire again");
    }

    private static void freshApplicationAndDuplicateRejection() {
        ItemEffectSystem effects = new ItemEffectSystem();
        ItemInfo freeze = timed("freeze", EffectKind.FREEZE, 40, null);
        long firstId = apply(effects, freeze);
        effects.advance(15);
        ItemEffectSystem.Applied duplicate = effects.apply(freeze, null);
        check(duplicate.failure == GrantFailure.EFFECT_ALREADY_ACTIVE, "duplicate remains rejected");
        check(effects.snapshot().get(0).remainingMillis == 25, "duplicate does not reset timer");
        check(effects.advance(25).size() == 1, "first activation expires");
        check(effects.check(freeze, null) == null, "expired kind can be activated again");
        long secondId = apply(effects, freeze);
        check(secondId == firstId + 1, "rejected activation does not consume an ID");
        check(effects.snapshot().get(0).remainingMillis == 40, "new activation gets its full duration");
        assertModifiers(effects.modifiers(), 1.0, 1.0, true);
    }

    private static long apply(ItemEffectSystem effects, ItemInfo item) {
        ItemEffectSystem.Applied applied = effects.apply(item, null);
        check(applied.failure == null && applied.effect != null, "apply " + item.itemId);
        return applied.effect.effectId;
    }

    private static ItemInfo timed(String id, EffectKind kind, long duration, Integer charges) {
        return new ItemInfo(id, id, "test", "item." + id, ActivationMode.MANUAL,
                kind, DurationKind.TIMED, duration, charges, null, EnumSet.of(GrantTiming.NOW));
    }

    private static ItemInfo boost(String id, EffectKind kind, double magnitude) {
        return new ItemInfo(id, id, "test", "item." + id, ActivationMode.ON_PICKUP,
                kind, DurationKind.UNTIL_LEVEL_END, null, null, magnitude, EnumSet.of(GrantTiming.NOW));
    }

    private static void assertEnded(ItemEffectSystem.Ended ended, long id, String itemId,
                                    EffectEndReason reason) {
        check(ended.effectId == id && ended.itemId.equals(itemId) && ended.reason == reason,
                "correct end information for " + itemId);
    }

    private static void assertModifiers(Modifiers actual, double fireRate, double speed, boolean blocked) {
        check(actual.fireRateMultiplier == fireRate, "fire-rate multiplier");
        check(actual.bulletSpeedMultiplier == speed, "bullet-speed multiplier");
        check(actual.enemyMovementBlocked == blocked, "enemy movement blocked");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
