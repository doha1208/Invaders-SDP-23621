package integration;

import java.util.Arrays;
import java.util.List;
import item.ManagerScenarioFixture;
import item.ItemAPI.*;

/** Foundation smoke checks only; full manager behavior scenarios belong to the next PR. */
public final class ItemManagerIntegrationTest {
    private static int checks;
    public static void main(String[] args) {
        fixtureLifecycle();
        inventoryAndDropSetup();
        scriptedEffects();
        eventRecording();
        System.out.println("PASS: manager test foundation, 4 scenarios, " + checks + " checks");
    }
    private static void fixtureLifecycle() {
        ManagerScenarioFixture previous;
        try (ManagerScenarioFixture f = new ManagerScenarioFixture()) {
            previous = f;
            check(f.api.getView().slots.size() == 2, "default capacity");
            f.item("shield", EffectKind.SHIELD);
            check(f.api.getItemInfos().size() == 1, "fake catalog connected");
            f.startLevel("one"); f.clearCalls(); f.tick(16);
            check(f.calls().equals(Arrays.asList("effects.advance", "drops.advance")), "shared call trace");
            check(f.effects.lastDelta == 16 && f.drops.lastDelta == 16, "delta observation");
            check(f.drops.lastPlayer.canUseItems, "player observation");
        }
        check(previous.api.useSlot(0) == UseResult.LEVEL_NOT_ACTIVE, "automatic cleanup");
        try (ManagerScenarioFixture fresh = new ManagerScenarioFixture()) {
            check(fresh.api.getItemInfos().isEmpty() && fresh.events.snapshot().isEmpty(), "fresh fixture isolation");
            check(fresh.calls().isEmpty() && fresh.inventory.at(0) == null, "fresh collaborator state");
        }
    }
    private static void inventoryAndDropSetup() {
        try (ManagerScenarioFixture f = new ManagerScenarioFixture(1)) {
            ItemInfo item = f.item("shield", EffectKind.SHIELD);
            f.fillInventory(item);
            check(f.inventory.firstEmptySlot() == -1, "fill helper");
            List<ItemInfo> before = f.inventory.snapshot();
            f.inventory.consume(0);
            check(before.get(0) == item && f.inventory.at(0) == null, "independent inventory snapshot");
            f.startLevel("one");
            DropView drop = f.floorItem(item);
            f.drops.contact(drop.dropId); f.tick(0);
            check(f.inventory.at(0) == item && f.drops.snapshot().isEmpty(), "scripted contact reaches real manager");
            DropView expired = f.floorItem(item);
            f.drops.expire(expired.dropId); f.tick(1);
            check(f.drops.snapshot().isEmpty(), "scripted expiry");
            f.drops.spawnNext(item);
            f.api.onEnemyDefeated(DropSource.SPECIAL_ENEMY, 20, 20);
            check(f.drops.snapshot().size() == 1, "scripted spawn");
            f.endLevel(); f.startLevel("two");
            check(f.drops.snapshot().isEmpty(), "drop setup reset between levels");
        }
    }
    private static void scriptedEffects() {
        try (ManagerScenarioFixture f = new ManagerScenarioFixture()) {
            ItemInfo shield = f.item("shield", EffectKind.SHIELD);
            f.startLevel("one"); f.tick(0);
            f.inventory.store(0, shield);
            check(f.api.useSlot(0) == UseResult.USED, "successful fake application");
            long id = f.effects.snapshot().get(0).effectId;
            f.effects.blockNextHit(id, true);
            check(f.api.tryBlockHit() && f.effects.snapshot().isEmpty(), "scripted hit");
            f.inventory.store(0, shield); f.api.useSlot(0);
            f.effects.endOnNextAdvance(f.effects.snapshot().get(0).effectId); f.tick(1);
            check(f.effects.snapshot().isEmpty(), "scripted effect ending");
            f.inventory.store(0, shield);
            f.effects.rejectApplications(GrantFailure.EFFECT_REJECTED);
            check(f.api.useSlot(0) == UseResult.EFFECT_REJECTED, "configurable failure");
            f.effects.rejectApplications(null);
            f.effects.setModifiers(new Modifiers(1.5, 1.1, true));
            check(f.api.getModifiers().enemyMovementBlocked, "configurable modifiers");
            f.item("life", EffectKind.LIFE);
            f.api.tryGrant(new GrantRequest("life-1", "life", GrantSource.REWARD, GrantTiming.NOW));
            check(f.life.additions == 1 && f.life.lives == 4, "test life port");
        }
    }
    private static void eventRecording() {
        try (ManagerScenarioFixture f = new ManagerScenarioFixture()) {
            f.item("shield", EffectKind.SHIELD);
            f.api.tryGrant(new GrantRequest("one", "shield", GrantSource.SHOP, GrantTiming.NOW));
            check(f.events.capture().size() == 1, "captures real event batch");
            check(f.events.capture().isEmpty(), "second drain is empty");
            List<ItemEvent> snapshot = f.events.snapshot();
            check(f.events.types().equals(Arrays.asList(EventType.ITEM_GRANTED)), "event type helper");
            expectUnsupported(() -> snapshot.clear());
            f.events.clear();
            check(snapshot.size() == 1 && f.events.snapshot().isEmpty(), "recorder reset preserves snapshot");
        }
    }
    private static void expectUnsupported(Runnable action) {
        try { action.run(); } catch (UnsupportedOperationException expected) { checks++; return; }
        throw new AssertionError("snapshot must be immutable");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
