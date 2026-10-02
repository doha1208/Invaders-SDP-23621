package integration;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import item.ManagerScenarioFixture;
import item.ItemAPI.*;

/** Real manager orchestration with the scripted collaborators from the foundation PR. */
public final class ItemManagerIntegrationTest {
    private static int checks;
    public static void main(String[] args) {
        fixtureLifecycle();
        inventoryAndDropSetup();
        scriptedEffects();
        eventRecording();
        fullInventoryKeepsDrop();
        failedEffectKeepsSlot();
        repeatedRequestGrantsOnce();
        nextLevelReservationAppliesOnce();
        levelEndClearsWorldAndKeepsInventory();
        acquisitionUseAndEndEventOrder();
        System.out.println("PASS: manager integration, 4 foundation + 6 behavior scenarios, " + checks + " checks");
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
    private static void fullInventoryKeepsDrop() {
        try (ManagerScenarioFixture f = new ManagerScenarioFixture(1)) {
            ItemInfo stored = f.item("shield", EffectKind.SHIELD);
            ItemInfo onFloor = f.item("freeze", EffectKind.FREEZE);
            f.fillInventory(stored);
            f.startLevel("full");
            DropView drop = f.floorItem(onFloor);
            f.clearCalls();
            // Repeated frames must neither collect nor remove the blocked pickup.
            for (int frame = 0; frame < 2; frame++) {
                f.drops.contact(drop.dropId); f.tick(16);
                check(f.api.getView().slots.get(0) == stored, "full inventory retains original item");
                check(f.api.getView().drops.size() == 1
                    && f.api.getView().drops.get(0).dropId == drop.dropId, "failed pickup stays on floor");
                check(countCall(f, "drops.pickup:" + drop.dropId) == 0, "no removal on failed acquisition");
                check(countCall(f, "inventory.store:0") == 0, "no overwrite of full inventory");
                captureTypes(f);
            }
            // Free capacity through the real manager, then retry the same drop.
            check(f.api.useSlot(0) == UseResult.USED, "stored shield can be used");
            captureTypes(f, EventType.ITEM_USED, EventType.EFFECT_STARTED);
            f.drops.contact(drop.dropId); f.tick(0);
            check(f.api.getView().slots.get(0) == onFloor, "same drop is acquired after space becomes available");
            check(f.api.getView().drops.isEmpty(), "successful pickup removes drop");
            check(countCall(f, "drops.pickup:" + drop.dropId) == 1, "exactly one pickup removal");
            captureTypes(f, EventType.ITEM_COLLECTED);
        }
    }

    private static void failedEffectKeepsSlot() {
        for (GrantFailure failure : Arrays.asList(GrantFailure.EFFECT_REJECTED, GrantFailure.EFFECT_ALREADY_ACTIVE)) {
            try (ManagerScenarioFixture f = new ManagerScenarioFixture(1)) {
                ItemInfo shield = f.item("shield", EffectKind.SHIELD);
                f.fillInventory(shield); f.startLevel("failure"); f.tick(0);
                f.effects.rejectApplications(failure); f.clearCalls();
                UseResult expected = failure == GrantFailure.EFFECT_REJECTED
                    ? UseResult.EFFECT_REJECTED : UseResult.EFFECT_ALREADY_ACTIVE;
                check(f.api.useSlot(0) == expected, "effect failure is reported to caller");
                check(f.api.getView().slots.get(0) == shield, "failed effect keeps exact item in slot");
                check(f.api.getView().effects.isEmpty(), "failed application creates no effect");
                check(f.calls().equals(Arrays.asList("effects.apply:shield")), "failure never consumes slot");
                captureTypes(f);
                f.effects.rejectApplications(null); f.clearCalls();
                check(f.api.useSlot(0) == UseResult.USED, "item remains usable after rejection is lifted");
                check(f.calls().equals(Arrays.asList("effects.apply:shield", "inventory.consume:0")),
                    "successful effect is applied before slot consumption");
                check(f.api.getView().slots.get(0) == null && f.api.getView().effects.size() == 1,
                    "successful retry consumes slot and starts effect");
                captureTypes(f, EventType.ITEM_USED, EventType.EFFECT_STARTED);
            }
        }
    }

    private static void repeatedRequestGrantsOnce() {
        try (ManagerScenarioFixture f = new ManagerScenarioFixture(2)) {
            f.item("shield", EffectKind.SHIELD);
            GrantRequest first = new GrantRequest("purchase-42", "shield", GrantSource.SHOP, GrantTiming.NOW);
            GrantResult receipt = f.api.tryGrant(first);
            // A fresh request object with identical content must also reuse the receipt.
            GrantRequest retry = new GrantRequest("purchase-42", "shield", GrantSource.SHOP, GrantTiming.NOW);
            check(f.api.tryGrant(retry) == receipt, "same request ID returns original receipt");
            check(receipt.status == GrantStatus.STORED && receipt.slotIndex == 0, "first request stores once");
            check(f.api.getView().slots.get(0) != null && f.api.getView().slots.get(1) == null,
                "duplicate request does not occupy second slot");
            check(countCall(f, "inventory.store:0") == 1 && countCall(f, "inventory.store:1") == 0,
                "only one inventory write");
            List<ItemEvent> granted = captureTypes(f, EventType.ITEM_GRANTED);
            check(granted.get(0).request.requestId.equals(first.requestId), "grant event identifies request");
            check(f.api.checkGrant(retry).status == GrantCheckStatus.ALREADY_PROCESSED, "preflight sees receipt");
            captureTypes(f);
            f.startLevel("one"); f.tick(0); f.api.useSlot(0);
            captureTypes(f, EventType.ITEM_USED, EventType.EFFECT_STARTED);
            check(f.api.tryGrant(retry) == receipt, "receipt survives item consumption");
            check(f.api.getView().slots.get(0) == null, "retry does not refill consumed slot");
            captureTypes(f);
            check(Collections.frequency(f.events.types(), EventType.ITEM_GRANTED) == 1, "one grant event total");
        }
    }

    private static void nextLevelReservationAppliesOnce() {
        for (EffectKind kind : Arrays.asList(EffectKind.RAPID_FIRE, EffectKind.BULLET_SPEED)) {
            try (ManagerScenarioFixture f = new ManagerScenarioFixture(1)) {
                ItemInfo boost = f.item("boost", kind);
                f.fillInventory(f.item("shield", EffectKind.SHIELD));
                f.startLevel("previous"); f.endLevel();
                GrantRequest request = new GrantRequest("reserve", boost.itemId, GrantSource.SHOP, GrantTiming.NEXT_LEVEL);
                f.clearCalls();
                GrantResult receipt = f.api.tryGrant(request);
                check(receipt.status == GrantStatus.QUEUED_FOR_NEXT_LEVEL, "boost queued between stages");
                check(f.api.tryGrant(request) == receipt, "duplicate reservation reuses receipt");
                check(f.api.getView().pendingGrants.size() == 1 && f.effects.snapshot().isEmpty(),
                    "reservation has no immediate effect");
                check(countCall(f, "effects.apply:boost") == 0, "no early application");
                captureTypes(f, EventType.ITEM_GRANTED);
                f.startLevel("next");
                check(f.api.getView().pendingGrants.isEmpty(), "applied reservation removed");
                check(f.api.getView().effects.size() == 1, "exactly one reserved effect started");
                check(f.api.getView().slots.get(0).itemId.equals("shield"), "reservation leaves full inventory intact");
                long effectId = f.api.getView().effects.get(0).effectId;
                List<ItemEvent> applied = captureTypes(f, EventType.PENDING_GRANT_APPLIED, EventType.EFFECT_STARTED);
                check(receipt.pendingGrantId.equals(applied.get(0).pendingGrantId), "reservation event links original ID");
                check(applied.get(0).effectId == effectId && applied.get(1).effectId == effectId,
                    "reservation and start events identify same effect");
                check(applied.get(0).levelId.equals("next"), "reserved effect belongs to next stage");
                f.tick(16);
                check(f.api.tryGrant(request) == receipt, "retry after activation returns original receipt");
                captureTypes(f);
                f.endLevel(); captureTypes(f, EventType.EFFECT_ENDED);
                f.startLevel("third");
                check(f.api.getView().effects.isEmpty() && f.api.getView().pendingGrants.isEmpty(),
                    "consumed reservation is not replayed in later stage");
                check(f.api.tryGrant(request) == receipt, "receipt survives another stage");
                check(countCall(f, "effects.apply:boost") == 1, "reservation applies only once across stages");
                captureTypes(f);
                check(Collections.frequency(f.events.types(), EventType.PENDING_GRANT_APPLIED) == 1
                    && Collections.frequency(f.events.types(), EventType.EFFECT_STARTED) == 1,
                    "reservation activation events occur once");
            }
        }
    }

    private static void levelEndClearsWorldAndKeepsInventory() {
        try (ManagerScenarioFixture f = new ManagerScenarioFixture(2)) {
            ItemInfo shield = f.item("shield", EffectKind.SHIELD);
            ItemInfo freeze = f.item("freeze", EffectKind.FREEZE);
            f.item("boost", EffectKind.RAPID_FIRE);
            f.fillInventory(shield); f.startLevel("cleanup"); f.tick(0);
            check(f.api.useSlot(0) == UseResult.USED, "setup active shield");
            check(f.api.tryGrant(new GrantRequest("boost-1", "boost", GrantSource.REWARD, GrantTiming.NOW)).isSuccess(),
                "setup active boost");
            f.floorItem(shield); f.floorItem(freeze);
            List<EffectView> active = f.api.getView().effects;
            check(active.size() == 2 && f.api.getView().drops.size() == 2, "setup contains effects and drops");
            List<ItemInfo> slots = f.api.getView().slots;
            f.events.capture(); f.events.clear(); f.clearCalls();
            f.endLevel();
            // Inspect fakes too: inactive manager views alone would conceal uncleared state.
            check(f.effects.snapshot().isEmpty() && f.drops.snapshot().isEmpty(), "underlying world state cleared");
            check(f.api.getView().effects.isEmpty() && f.api.getView().drops.isEmpty(), "inactive view has no world objects");
            check(f.api.getView().slots.equals(slots) && f.inventory.at(1) == shield, "slot layout and item survive cleanup");
            check(f.calls().equals(Arrays.asList("effects.clear", "drops.clear")), "cleanup delegates once");
            List<ItemEvent> ended = captureTypes(f, EventType.EFFECT_ENDED, EventType.EFFECT_ENDED);
            for (int i = 0; i < ended.size(); i++) {
                check(ended.get(i).effectId == active.get(i).effectId, "each effect has one ending");
                check(ended.get(i).endReason == EffectEndReason.LEVEL_ENDED
                    && ended.get(i).levelId.equals("cleanup"), "end reason and prior level preserved");
            }
            f.endLevel(); captureTypes(f);
            check(f.calls().size() == 2, "repeated end does not repeat cleanup");
            f.startLevel("fresh"); f.tick(0);
            check(f.api.getView().slots.equals(slots), "inventory also survives next stage start");
            check(f.effects.snapshot().isEmpty() && f.drops.snapshot().isEmpty(), "new stage does not restore cleared objects");
            captureTypes(f);
        }
    }

    private static void acquisitionUseAndEndEventOrder() {
        try (ManagerScenarioFixture f = new ManagerScenarioFixture()) {
            ItemInfo shield = f.item("shield", EffectKind.SHIELD);
            f.startLevel("events");
            DropView drop = f.floorItem(shield);
            f.drops.contact(drop.dropId); f.tick(0);
            List<ItemEvent> collected = captureTypes(f, EventType.ITEM_COLLECTED);
            check(collected.get(0).dropId == drop.dropId && collected.get(0).slotIndex == 0,
                "collected event links drop and inventory slot");
            check(f.api.useSlot(0) == UseResult.USED, "acquired item is used");
            List<ItemEvent> used = captureTypes(f, EventType.ITEM_USED, EventType.EFFECT_STARTED);
            long effectId = f.api.getView().effects.get(0).effectId;
            check(used.get(0).slotIndex == 0 && used.get(0).effectId == effectId
                && used.get(1).effectId == effectId, "use and start identify created effect");
            f.effects.endOnNextAdvance(effectId); f.tick(10000);
            List<ItemEvent> ended = captureTypes(f, EventType.EFFECT_ENDED);
            check(ended.get(0).effectId == effectId && ended.get(0).endReason == EffectEndReason.EXPIRED,
                "ending identifies used effect and expiry reason");
            check(f.events.types().equals(Arrays.asList(EventType.ITEM_COLLECTED, EventType.ITEM_USED,
                EventType.EFFECT_STARTED, EventType.EFFECT_ENDED)), "complete event order and exact occurrence counts");
            long previousId = 0;
            for (ItemEvent event : f.events.snapshot()) {
                check(event.eventId > previousId, "event IDs are unique and increasing");
                check(event.itemId.equals(shield.itemId) && event.levelId.equals("events"), "event item and stage context");
                previousId = event.eventId;
            }
            check(f.api.getView().slots.get(0) == null && f.api.getView().effects.isEmpty(), "used item and expired effect removed");
            f.tick(10000); captureTypes(f);
            f.endLevel(); captureTypes(f);
            check(f.events.snapshot().size() == 4, "later update and cleanup add no duplicate endings");
        }
    }

    private static int countCall(ManagerScenarioFixture f, String call) {
        return Collections.frequency(f.calls(), call);
    }
    private static List<ItemEvent> captureTypes(ManagerScenarioFixture f, EventType... expected) {
        List<ItemEvent> batch = f.events.capture();
        check(batch.size() == expected.length, "event batch size: expected " + Arrays.toString(expected));
        for (int i = 0; i < expected.length; i++)
            check(batch.get(i).type == expected[i], "event " + i + " expected " + expected[i]);
        check(f.events.capture().isEmpty(), "events drain only once");
        return batch;
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
