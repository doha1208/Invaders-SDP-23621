package item;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import item.ItemAPI.*;

public final class ItemDefinitionsValidationTest {
    public static void main(String[] args) {
        ItemDefinitions definitions = new ItemDefinitions();

        definitions.validate(rules(
            rule(0.15, weights("life", 1.0, "shield", 2.0)),
            rule(1.0, weights("freeze", 1.0))
        ));

        definitions.validate(rules(
            rule(0.0, new LinkedHashMap<String, Double>()),
            rule(1.0, weights("rapid_fire", 0.0, "bullet_speed", 1.0))
        ));

        expectInvalid(() -> definitions.validate(null), "rules is required");
        expectInvalid(() -> definitions.validate(rulesWithOnly(
            DropSource.REGULAR_ENEMY,
            rule(0.15, weights("life", 1.0))
        )), "missing drop rule");
        expectInvalid(() -> definitions.validate(rules(
            rule(0.15, weights("unknown", 1.0)),
            rule(1.0, weights("freeze", 1.0))
        )), "unknown item ID");
        expectInvalid(() -> definitions.validate(rules(
            rule(0.15, weights("life", 0.0, "shield", 0.0)),
            rule(1.0, weights("freeze", 1.0))
        )), "positive total weight");

        System.out.println("item definition validation checks passed");
    }

    private static LevelRules rules(DropRule regular, DropRule special) {
        Map<DropSource, DropRule> rules = new EnumMap<DropSource, DropRule>(DropSource.class);
        rules.put(DropSource.REGULAR_ENEMY, regular);
        rules.put(DropSource.SPECIAL_ENEMY, special);
        return levelRules(rules);
    }

    private static LevelRules rulesWithOnly(DropSource source, DropRule rule) {
        Map<DropSource, DropRule> rules = new EnumMap<DropSource, DropRule>(DropSource.class);
        rules.put(source, rule);
        return levelRules(rules);
    }

    private static LevelRules levelRules(Map<DropSource, DropRule> dropRules) {
        return new LevelRules("validation-test", 0.0, 100.0, 0.0, 100.0,
            20.0, 8.0, 8.0, 5_000L, dropRules);
    }

    private static DropRule rule(double probability, Map<String, Double> weights) {
        return new DropRule(probability, weights);
    }

    private static Map<String, Double> weights(String firstId, double firstWeight) {
        Map<String, Double> weights = new LinkedHashMap<String, Double>();
        weights.put(firstId, firstWeight);
        return weights;
    }

    private static Map<String, Double> weights(String firstId, double firstWeight,
                                                String secondId, double secondWeight) {
        Map<String, Double> weights = weights(firstId, firstWeight);
        weights.put(secondId, secondWeight);
        return weights;
    }

    private static void expectInvalid(Runnable action, String messageFragment) {
        try {
            action.run();
            throw new AssertionError("expected IllegalArgumentException: " + messageFragment);
        } catch (IllegalArgumentException expected) {
            require(expected.getMessage() != null && expected.getMessage().contains(messageFragment),
                "exception message must contain: " + messageFragment);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
