package item;

import java.math.BigDecimal;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import item.ItemAPI.*;

/** Owns the immutable catalog and a per-run snapshot of external balance settings. */
class ItemDefinitions {
    private final Map<String, ItemInfo> itemsById;
    private final List<ItemInfo> items;
    private final Properties balance;

    ItemDefinitions() {
        this(loadBalance());
    }

    /** Internal test configuration; production reads the external settings once per run. */
    ItemDefinitions(long shieldDurationMillis, int shieldCharges, double fireRateMultiplier,
                    double bulletSpeedMultiplier, long freezeDurationMillis) {
        this(withEffects(shieldDurationMillis, shieldCharges, fireRateMultiplier,
            bulletSpeedMultiplier, freezeDurationMillis));
    }

    private ItemDefinitions(Properties settings) {
        balance = settings;
        long shieldDurationMillis = integer("shield.durationMillis", Long.MAX_VALUE);
        int shieldCharges = (int) integer("shield.charges", Integer.MAX_VALUE);
        double fireRateMultiplier = decimal("rapidFire.multiplier", false, Double.MAX_VALUE);
        double bulletSpeedMultiplier = decimal("bulletSpeed.multiplier", false, Double.MAX_VALUE);
        long freezeDurationMillis = integer("freeze.durationMillis", Long.MAX_VALUE);
        inventoryCapacity();
        defaultDropRules();
        decimal("drop.fallSpeed", false, Double.MAX_VALUE);
        decimal("drop.width", false, Double.MAX_VALUE);
        decimal("drop.height", false, Double.MAX_VALUE);
        integer("drop.groundLifetimeMillis", Long.MAX_VALUE);
        Map<String, ItemInfo> definitions = new LinkedHashMap<String, ItemInfo>();

        ItemInfo life = new ItemInfo(
            "life",
            "Life",
            "Adds one life when the game allows it.",
            "life",
            ActivationMode.ON_PICKUP,
            EffectKind.LIFE,
            DurationKind.INSTANT,
            null,
            null,
            null,
            EnumSet.of(GrantTiming.NOW)
        );
        definitions.put(life.itemId, life);

        ItemInfo shield = new ItemInfo(
            "shield",
            "Shield",
            "Blocks " + shieldCharges + " incoming hit" + (shieldCharges == 1 ? "" : "s")
                + " for up to " + seconds(shieldDurationMillis) + " seconds.",
            "shield",
            ActivationMode.MANUAL,
            EffectKind.SHIELD,
            DurationKind.TIMED,
            shieldDurationMillis,
            shieldCharges,
            null,
            EnumSet.of(GrantTiming.NOW)
        );
        definitions.put(shield.itemId, shield);

        ItemInfo rapidFire = new ItemInfo(
            "rapid_fire",
            "Rapid Fire",
            "Firing rate: " + number(fireRateMultiplier) + "x until the level ends.",
            "rapid_fire",
            ActivationMode.ON_PICKUP,
            EffectKind.RAPID_FIRE,
            DurationKind.UNTIL_LEVEL_END,
            null,
            null,
            fireRateMultiplier,
            EnumSet.of(GrantTiming.NOW, GrantTiming.NEXT_LEVEL)
        );
        definitions.put(rapidFire.itemId, rapidFire);

        ItemInfo bulletSpeed = new ItemInfo(
            "bullet_speed",
            "Bullet Speed",
            "Projectile speed: " + number(bulletSpeedMultiplier) + "x until the level ends.",
            "bullet_speed",
            ActivationMode.ON_PICKUP,
            EffectKind.BULLET_SPEED,
            DurationKind.UNTIL_LEVEL_END,
            null,
            null,
            bulletSpeedMultiplier,
            EnumSet.of(GrantTiming.NOW, GrantTiming.NEXT_LEVEL)
        );
        definitions.put(bulletSpeed.itemId, bulletSpeed);

        ItemInfo freeze = new ItemInfo(
            "freeze",
            "Freeze",
            "Stops all enemy movement for " + seconds(freezeDurationMillis) + " seconds.",
            "freeze",
            ActivationMode.MANUAL,
            EffectKind.FREEZE,
            DurationKind.TIMED,
            freezeDurationMillis,
            null,
            null,
            EnumSet.of(GrantTiming.NOW)
        );
        definitions.put(freeze.itemId, freeze);

        itemsById = Collections.unmodifiableMap(definitions);
        items = Collections.unmodifiableList(new ArrayList<ItemInfo>(definitions.values()));
        validateDefinitions();
    }

    int inventoryCapacity() {
        return (int) integer("inventory.capacity", Integer.MAX_VALUE);
    }

    LevelRules createLevelRules(String id, double left, double right, double top, double floorY) {
        LevelRules rules = new LevelRules(id, left, right, top, floorY,
            decimal("drop.fallSpeed", false, Double.MAX_VALUE),
            decimal("drop.width", false, Double.MAX_VALUE),
            decimal("drop.height", false, Double.MAX_VALUE),
            integer("drop.groundLifetimeMillis", Long.MAX_VALUE), defaultDropRules());
        validate(rules);
        return rules;
    }

    private Map<DropSource, DropRule> defaultDropRules() {
        Map<String, Double> weights = new LinkedHashMap<String, Double>();
        weights.put("life", decimal("drop.weight.life", true, Double.MAX_VALUE));
        weights.put("shield", decimal("drop.weight.shield", true, Double.MAX_VALUE));
        weights.put("rapid_fire", decimal("drop.weight.rapidFire", true, Double.MAX_VALUE));
        weights.put("bullet_speed", decimal("drop.weight.bulletSpeed", true, Double.MAX_VALUE));
        weights.put("freeze", decimal("drop.weight.freeze", true, Double.MAX_VALUE));
        double regular = decimal("drop.regular.probability", true, 1);
        double special = decimal("drop.special.probability", true, 1);
        double total = 0;
        for (double weight : weights.values()) total += weight;
        if (!Double.isFinite(total) || ((regular > 0 || special > 0) && total <= 0))
            throw settingError("drop.weight.*", "finite positive total required when drops are enabled");
        Map<DropSource, DropRule> rules = new EnumMap<DropSource, DropRule>(DropSource.class);
        rules.put(DropSource.REGULAR_ENEMY, new DropRule(regular, weights));
        rules.put(DropSource.SPECIAL_ENEMY, new DropRule(special, weights));
        return rules;
    }

    private String value(String key) {
        String value = balance.getProperty(key);
        if (value == null || value.trim().isEmpty()) throw settingError(key, "missing value");
        return value.trim();
    }

    private long integer(String key, long maximum) {
        try {
            long result = Long.parseLong(value(key));
            if (result <= 0 || result > maximum) throw settingError(key, "positive integer required, maximum " + maximum);
            return result;
        } catch (NumberFormatException error) {
            throw settingError(key, "invalid integer");
        }
    }

    private double decimal(String key, boolean allowZero, double maximum) {
        try {
            double result = Double.parseDouble(value(key));
            if (!Double.isFinite(result) || result < 0 || (!allowZero && result == 0) || result > maximum)
                throw settingError(key, "finite number required in " + (allowZero ? "[0, " : "(0, ") + maximum + "]");
            return result;
        } catch (NumberFormatException error) {
            throw settingError(key, "invalid number");
        }
    }

    private IllegalArgumentException settingError(String key, String reason) {
        return new IllegalArgumentException("item-balance.properties [" + key + "]: " + reason);
    }

    /** Explicit path wins; otherwise use the working directory or an external classpath resource. */
    private static Properties loadBalance() {
        String configured = System.getProperty("invaders.itemBalance");
        Path path = configured == null ? Path.of("res", "item-balance.properties") : Path.of(configured);
        if (configured == null && !Files.exists(path)) {
            java.net.URL resource = ItemDefinitions.class.getResource("/item-balance.properties");
            if (resource != null && "file".equals(resource.getProtocol())) {
                try { path = Path.of(resource.toURI()); }
                catch (java.net.URISyntaxException error) { throw new IllegalStateException("Invalid balance resource path", error); }
            }
        }
        Properties settings = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            settings.load(reader);
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalStateException("Cannot read item balance: " + path.toAbsolutePath()
                + "; supply an external file with -Dinvaders.itemBalance=<path>", error);
        }
        return settings;
    }

    private static Properties withEffects(long shieldMillis, int charges, double fireRate,
                                           double bulletSpeed, long freezeMillis) {
        Properties settings = loadBalance();
        settings.setProperty("shield.durationMillis", Long.toString(shieldMillis));
        settings.setProperty("shield.charges", Integer.toString(charges));
        settings.setProperty("rapidFire.multiplier", Double.toString(fireRate));
        settings.setProperty("bulletSpeed.multiplier", Double.toString(bulletSpeed));
        settings.setProperty("freeze.durationMillis", Long.toString(freezeMillis));
        return settings;
    }

    /**
     * Looks up an immutable item definition; an unknown valid ID returns null.
     * Life applies instantly; shield and freeze are manual; speed bonuses last for the level.
     * All items support NOW. Only rapid fire and bullet speed support NEXT_LEVEL.
     * Read current balance values from each ItemInfo instead of duplicating them.
     */
    ItemInfo find(String itemId) { return itemsById.get(itemId); }

    /** 등록 순서의 불변 목록. 상점의 판매 목록이 아니다. find와 같은 정의를 사용한다. */
    List<ItemInfo> all() { return items; }

    /**
     * 상태 변경 없이 모든 정의/규칙을 검증한다.
     * 두 DropSource 규칙, 존재하는 ID, 양의 유한 가중치 합(p>0일 때), 설정 영역을 검증한다.
     * 정의의 kind/duration/수치 조합도 확인한다. 동일 kind 중복 효과 정책은 REJECT다.
     * NEXT_LEVEL은 ON_PICKUP + UNTIL_LEVEL_END인 RAPID_FIRE/BULLET_SPEED만 가능하다.
     * LIFE는 INSTANT, SHIELD/FREEZE는 TIMED, 두 배율 효과는 UNTIL_LEVEL_END로 고정한다.
     * SHIELD는 durationMillis/charges, FREEZE는 durationMillis, 두 배율은 magnitude가 필수다.
     * 지원하지 않는 값 조합은 예외. 잘못된 설정을 자동 보정하거나 게임을 시작하지 않는다.
     */
    void validate(LevelRules rules) {
        ItemAPI.required(rules, "rules");
        validateDefinitions();

        for (DropSource source : DropSource.values()) {
            DropRule rule = rules.dropRules.get(source);
            requireValid(rule != null, "missing drop rule: " + source);

            double totalWeight = 0.0;
            for (Map.Entry<String, Double> entry : rule.weights.entrySet()) {
                requireValid(itemsById.containsKey(entry.getKey()),
                    "unknown item ID: " + entry.getKey());
                double weight = entry.getValue();
                ItemAPI.finite(weight, "weight");
                requireValid(weight >= 0.0, "weight must not be negative: " + entry.getKey());
                totalWeight += weight;
                ItemAPI.finite(totalWeight, "total weight");
            }
            if (rule.probability > 0.0)
                requireValid(totalWeight > 0.0, "positive total weight required: " + source);
        }
    }

    private void validateDefinitions() {
        requireValid(items.size() == itemsById.size(), "definition index mismatch");
        EnumSet<EffectKind> kinds = EnumSet.noneOf(EffectKind.class);

        for (ItemInfo item : items) {
            requireValid(itemsById.get(item.itemId) == item, "definition index mismatch: " + item.itemId);
            requireValid(kinds.add(item.effectKind), "duplicate effect kind: " + item.effectKind);
            validateDefinition(item);
        }

        requireValid(kinds.size() == EffectKind.values().length, "missing effect definition");
    }

    /** Shared structural validation for catalog lookup, preflight checks and effect application. */
    static void validateDefinition(ItemInfo item) {
        ItemAPI.required(item, "item");
        requireValid(item.supportedGrantTimings.contains(GrantTiming.NOW),
            "NOW timing required: " + item.itemId);

        switch (item.effectKind) {
            case LIFE:
                requireDefinition(item, item.activationMode == ActivationMode.ON_PICKUP
                    && item.durationKind == DurationKind.INSTANT
                    && item.durationMillis == null && item.charges == null && item.magnitude == null
                    && item.supportedGrantTimings.equals(EnumSet.of(GrantTiming.NOW)));
                break;
            case SHIELD:
                requireDefinition(item, item.activationMode == ActivationMode.MANUAL
                    && item.durationKind == DurationKind.TIMED
                    && item.durationMillis != null && item.durationMillis > 0
                    && item.charges != null && item.charges > 0 && item.magnitude == null
                    && item.supportedGrantTimings.equals(EnumSet.of(GrantTiming.NOW)));
                break;
            case RAPID_FIRE:
            case BULLET_SPEED:
                requireDefinition(item, isLevelMultiplier(item));
                break;
            case FREEZE:
                requireDefinition(item, item.activationMode == ActivationMode.MANUAL
                    && item.durationKind == DurationKind.TIMED
                    && item.durationMillis != null && item.durationMillis > 0
                    && item.charges == null && item.magnitude == null
                    && item.supportedGrantTimings.equals(EnumSet.of(GrantTiming.NOW)));
                break;
            default:
                throw new IllegalArgumentException("unsupported effect kind: " + item.effectKind);
        }
    }

    private static boolean isLevelMultiplier(ItemInfo item) {
        return item.activationMode == ActivationMode.ON_PICKUP
            && item.durationKind == DurationKind.UNTIL_LEVEL_END
            && item.durationMillis == null && item.charges == null
            && item.magnitude != null && Double.isFinite(item.magnitude) && item.magnitude > 0
            && item.supportedGrantTimings.equals(
                EnumSet.of(GrantTiming.NOW, GrantTiming.NEXT_LEVEL));
    }

    private static String seconds(long millis) {
        return BigDecimal.valueOf(millis).movePointLeft(3).stripTrailingZeros().toPlainString();
    }

    private static String number(double value) {
        ItemAPI.positive(value, "multiplier");
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static void requireDefinition(ItemInfo item, boolean condition) {
        requireValid(condition, "invalid definition: " + item.itemId);
    }

    private static void requireValid(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
