package item;

import java.awt.Color;
import java.awt.Graphics;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import entity.Entity;
import item.ItemAPI.*;

/**
 * Bridge between the game screen and the item system. The goal is to leave only one-line calls on the game side.
 * All translation (building LevelRules/LifePort/PlayerSnapshot, frame timing, bonus math) happens here.
 * Shop, HUD and effects teams use the ItemAPI from api().
 *
 * One per run. GameScreen is recreated every level, so this class keeps the run-wide instance.
 * Level 1 always starts a new run, so forLevel(1) creates a new instance.
 * Call only from the thread that owns game state.
 */
public final class ItemSystem {
    /** Drops exist below the HUD separator line (same height as GameScreen's separator). */
    private static final double PLAY_AREA_TOP = 40;

    private static ItemSystem current;

    /** Balance values of this run (res/item-balance.properties). Read once when the run starts. */
    private final ItemBalance balance;
    private final ItemManager manager;
    private final ItemAPI api;
    private GameHooks hooks;
    private int levelNumber;
    private long lastUpdateNanos = -1;

    /** Lets the game screen hand over access to lives/score. The item system does not own lives or score. */
    public interface GameHooks {
        int getLives();
        void addLife();
        void addScore(int points);
    }

    private ItemSystem(Random random) {
        balance = ItemBalance.load();
        manager = new ItemManager(balance.inventoryCapacity, balance, random);
        api = new ItemAPI(manager);
    }

    /** Run instance for the level number. Starts a new run at level 1 or when there is no run. */
    public static ItemSystem forLevel(int level) {
        if (level <= 1 || current == null) current = new ItemSystem(new Random());
        return current;
    }

    /** The current run. May be null outside a run (menus, etc.). */
    public static ItemSystem current() { return current; }

    /** General entry point for the shop, HUD and effects teams. */
    public ItemAPI api() { return api; }

    /**
     * Starts a level. The drop area comes from the screen size and the player ship position (floor = bottom of the ship).
     * Ends the previous level first if it was not ended.
     */
    public void beginLevel(int level, int screenWidth, Entity ship, GameHooks gameHooks) {
        hooks = ItemAPI.required(gameHooks, "hooks");
        ItemAPI.required(ship, "ship");
        endLevel();
        levelNumber = level;
        double floorY = ship.getPositionY() + ship.getHeight();
        LevelRules rules = new LevelRules("level-" + level, 0, screenWidth, PLAY_AREA_TOP, floorY,
            balance.fallSpeed, balance.dropWidth, balance.dropHeight, balance.groundLifetimeMillis,
            dropRules());
        manager.beginLevel(rules, lifePort);
        lastUpdateNanos = -1;
    }

    /** Called every frame. Measures elapsed time itself. No pickup/use when shipAvailable=false. */
    public void update(Entity ship, boolean shipAvailable) {
        if (!isLevelActive()) return;
        long now = System.nanoTime(), delta = 0;
        if (lastUpdateNanos < 0) lastUpdateNanos = now;
        else {
            delta = (now - lastUpdateNanos) / 1_000_000L;
            lastUpdateNanos += delta * 1_000_000L; // Carries the sub-millisecond remainder to the next frame.
        }
        Bounds bounds = new Bounds(ship.getPositionX(), ship.getPositionY(),
            Math.max(1, ship.getWidth()), Math.max(1, ship.getHeight()));
        manager.update(delta, new PlayerSnapshot(bounds, shipAvailable, shipAvailable));
    }

    /** Called when an enemy is defeated. Rolls a drop at the enemy's center. */
    public void onEnemyDefeated(Entity enemy, boolean special) {
        if (!isLevelActive()) return;
        api.onEnemyDefeated(special ? DropSource.SPECIAL_ENEMY : DropSource.REGULAR_ENEMY,
            enemy.getPositionX() + enemy.getWidth() / 2.0,
            enemy.getPositionY() + enemy.getHeight() / 2.0);
    }

    /** Called when the player is hit, before taking a life. true means the shield blocked it, so no damage. */
    public boolean tryBlockHit() { return api.tryBlockHit(); }

    /** Uses the item in an inventory slot. */
    public UseResult useSlot(int slot) { return api.useSlot(slot); }

    /** Item fire rate bonus: extra shots per second, added on top of the base fire rate. */
    public double fireRateBonus() { return api.getModifiers().fireRateBonus; }

    /** Item bullet speed bonus: extra pixels per frame, added on top of the base bullet speed. */
    public double bulletSpeedBonus() { return api.getModifiers().bulletSpeedBonus; }

    /** true means enemies must not move (freeze). */
    public boolean enemiesFrozen() { return api.getModifiers().enemyMovementBlocked; }

    /** Draws the drops on the field. Placeholder shapes until proper sprites are decided. */
    public void drawDrops(Graphics graphics) {
        for (DropView drop : api.getView().drops) {
            int x = (int) drop.bounds.x, y = (int) drop.bounds.y;
            int w = (int) drop.bounds.width, h = (int) drop.bounds.height;
            graphics.setColor(colorOf(drop.item.effectKind));
            graphics.fillRect(x, y, w, h);
            graphics.setColor(Color.BLACK);
            graphics.drawString(drop.item.displayName.substring(0, 1), x + w / 4, y + h - 3);
        }
    }

    /** Ends the level. Does nothing if no level is active. */
    public void endLevel() {
        if (isLevelActive()) manager.endLevel();
    }

    /** Events for sound/effects. Cleared once drained. */
    public List<ItemEvent> drainEvents() { return api.drainEvents(); }

    public int getLevelNumber() { return levelNumber; }

    private boolean isLevelActive() { return hooks != null && manager.isLevelActive(); }

    /** Life item: +1 life below the cap, a score bonus at the cap. The item is consumed either way. */
    private final LifePort lifePort = new LifePort() {
        public boolean canAddLife() { return true; }
        public boolean tryAddLife() {
            if (hooks.getLives() < balance.maxLives) hooks.addLife();
            else hooks.addScore(balance.lifeCapBonusScore);
            return true;
        }
    };

    private Map<DropSource, DropRule> dropRules() {
        Map<String, Double> weights = balance.dropWeights;
        Map<String, Double> decay = new java.util.LinkedHashMap<String, Double>();
        decay.put("rapid_fire", balance.rapidFireDropDecay);
        decay.put("bullet_speed", balance.bulletSpeedDropDecay);
        Map<DropSource, DropRule> rules = new EnumMap<DropSource, DropRule>(DropSource.class);
        rules.put(DropSource.REGULAR_ENEMY, new DropRule(balance.regularDropProbability, weights, decay));
        rules.put(DropSource.SPECIAL_ENEMY, new DropRule(balance.specialDropProbability, weights, decay));
        return rules;
    }

    private static Color colorOf(EffectKind kind) {
        switch (kind) {
            case LIFE: return Color.RED;
            case SHIELD: return Color.CYAN;
            case RAPID_FIRE: return Color.ORANGE;
            case BULLET_SPEED: return Color.YELLOW;
            default: return Color.WHITE;
        }
    }
}
