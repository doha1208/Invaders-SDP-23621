package item;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import item.ItemAPI.*;

public final class ItemDefinitionsCatalogTest {
    public static void main(String[] args) {
        ItemDefinitions definitions = new ItemDefinitions();
        List<ItemInfo> items = definitions.all();

        require(items.size() == 5, "catalog size");
        check(items.get(0), "life", "Life", ActivationMode.ON_PICKUP,
            EffectKind.LIFE, DurationKind.INSTANT, null, null, null,
            EnumSet.of(GrantTiming.NOW));
        check(items.get(1), "shield", "Shield", ActivationMode.MANUAL,
            EffectKind.SHIELD, DurationKind.TIMED, 10_000L, 1, null,
            EnumSet.of(GrantTiming.NOW));
        check(items.get(2), "rapid_fire", "Rapid Fire", ActivationMode.ON_PICKUP,
            EffectKind.RAPID_FIRE, DurationKind.UNTIL_LEVEL_END, null, null, 1.5,
            EnumSet.of(GrantTiming.NOW, GrantTiming.NEXT_LEVEL));
        check(items.get(3), "bullet_speed", "Bullet Speed", ActivationMode.ON_PICKUP,
            EffectKind.BULLET_SPEED, DurationKind.UNTIL_LEVEL_END, null, null, 1.10,
            EnumSet.of(GrantTiming.NOW, GrantTiming.NEXT_LEVEL));
        check(items.get(4), "freeze", "Freeze", ActivationMode.MANUAL,
            EffectKind.FREEZE, DurationKind.TIMED, 5_000L, null, null,
            EnumSet.of(GrantTiming.NOW));

        for (ItemInfo item : items)
            require(definitions.find(item.itemId) == item, "find must return catalog instance: " + item.itemId);
        require(definitions.find("unknown") == null, "unknown ID must return null");

        expectUnsupported(() -> items.add(items.get(0)), "catalog must be immutable");
        expectUnsupported(
            () -> items.get(0).supportedGrantTimings.add(GrantTiming.NEXT_LEVEL),
            "grant timings must be immutable"
        );

        System.out.println("item definition catalog checks passed");
    }

    private static void check(ItemInfo item, String id, String name,
                              ActivationMode mode, EffectKind effect,
                              DurationKind duration, Long milliseconds,
                              Integer charges, Double magnitude,
                              Set<GrantTiming> timings) {
        require(id.equals(item.itemId), "itemId: " + id);
        require(name.equals(item.displayName), "displayName: " + id);
        require(mode == item.activationMode, "activationMode: " + id);
        require(effect == item.effectKind, "effectKind: " + id);
        require(duration == item.durationKind, "durationKind: " + id);
        require(Objects.equals(milliseconds, item.durationMillis), "durationMillis: " + id);
        require(Objects.equals(charges, item.charges), "charges: " + id);
        require(Objects.equals(magnitude, item.magnitude), "magnitude: " + id);
        require(timings.equals(item.supportedGrantTimings), "supportedGrantTimings: " + id);
    }

    private static void expectUnsupported(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
