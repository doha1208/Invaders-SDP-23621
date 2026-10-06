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
 * 게임 화면이 쓰는 아이템 시스템 연결 창구. 게임 쪽에는 한 줄짜리 호출만 남기는 것이 목적이다.
 * LevelRules/LifePort/PlayerSnapshot 생성, 프레임 시간 계산, 배율 계산 같은 번역은 전부 여기서 한다.
 * 상점·HUD·이펙트 팀은 api()의 ItemAPI를 사용한다.
 *
 * 한 판(run)에 하나. GameScreen은 레벨마다 새로 만들어지므로 판 단위 인스턴스를 이 클래스가 보관한다.
 * 레벨 1은 항상 새 판의 시작이므로 forLevel(1)이 새 인스턴스를 만든다.
 * 같은 게임 상태 소유 스레드에서만 호출한다.
 */
public final class ItemSystem {
    /** HUD 구분선 아래부터 드랍이 존재한다 (GameScreen의 구분선 높이와 같다). */
    private static final double PLAY_AREA_TOP = 40;

    private static ItemSystem current;

    /** 이 판의 밸런스 수치 (res/item-balance.properties). 판 시작 시 한 번 읽는다. */
    private final ItemBalance balance;
    private final ItemManager manager;
    private final ItemAPI api;
    private GameHooks hooks;
    private int levelNumber;
    private long lastUpdateNanos = -1;

    /** 게임 화면이 목숨/점수 접근을 넘기는 통로. 아이템 시스템은 목숨/점수를 소유하지 않는다. */
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

    /** 레벨 번호에 맞는 판 인스턴스. 레벨 1이거나 판이 없으면 새 판을 시작한다. */
    public static ItemSystem forLevel(int level) {
        if (level <= 1 || current == null) current = new ItemSystem(new Random());
        return current;
    }

    /** 진행 중인 판. 판 밖(메뉴 등)에서는 null일 수 있다. */
    public static ItemSystem current() { return current; }

    /** 상점·HUD·이펙트 팀용 일반 기능 창구. */
    public ItemAPI api() { return api; }

    /**
     * 레벨 시작. 화면 크기와 플레이어 기체 위치로 드랍 영역을 정한다(바닥 = 기체 아래쪽).
     * 이전 레벨을 닫지 않았으면 먼저 닫는다.
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

    /** 매 프레임 호출. 경과 시간은 직접 잰다. shipAvailable=false면 획득/사용하지 않는다. */
    public void update(Entity ship, boolean shipAvailable) {
        if (!isLevelActive()) return;
        long now = System.nanoTime(), delta = 0;
        if (lastUpdateNanos < 0) lastUpdateNanos = now;
        else {
            delta = (now - lastUpdateNanos) / 1_000_000L;
            lastUpdateNanos += delta * 1_000_000L; // 1ms 미만 나머지는 다음 프레임으로 넘긴다.
        }
        Bounds bounds = new Bounds(ship.getPositionX(), ship.getPositionY(),
            Math.max(1, ship.getWidth()), Math.max(1, ship.getHeight()));
        manager.update(delta, new PlayerSnapshot(bounds, shipAvailable, shipAvailable));
    }

    /** 적 처치 시 호출. 적의 중심에서 드랍을 추첨한다. */
    public void onEnemyDefeated(Entity enemy, boolean special) {
        if (!isLevelActive()) return;
        api.onEnemyDefeated(special ? DropSource.SPECIAL_ENEMY : DropSource.REGULAR_ENEMY,
            enemy.getPositionX() + enemy.getWidth() / 2.0,
            enemy.getPositionY() + enemy.getHeight() / 2.0);
    }

    /** 플레이어 피격 시 목숨을 깎기 전에 호출. true면 방패가 막았으므로 피해를 주지 않는다. */
    public boolean tryBlockHit() { return api.tryBlockHit(); }

    /** 보관함 칸의 아이템 사용. */
    public UseResult useSlot(int slot) { return api.useSlot(slot); }

    /** 기본 발사 간격(ms)에 연사 효과를 반영한 값. */
    public int fireInterval(int baseMillis) {
        return Math.max(1, (int) Math.round(baseMillis / api.getModifiers().fireRateMultiplier));
    }

    /** 기본 탄속(프레임당 픽셀, 부호 유지)에 탄속 효과를 반영한 값. int라 반올림된다. */
    public int bulletSpeed(int baseSpeed) {
        return (int) Math.round(baseSpeed * api.getModifiers().bulletSpeedMultiplier);
    }

    /** true면 적이 움직이지 않아야 한다 (프리즈). */
    public boolean enemiesFrozen() { return api.getModifiers().enemyMovementBlocked; }

    /** 바닥 드랍을 그린다. 정식 스프라이트가 정해지기 전의 기본 모양이다. */
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

    /** 레벨 종료. 레벨 중이 아니면 아무것도 하지 않는다. */
    public void endLevel() {
        if (isLevelActive()) manager.endLevel();
    }

    /** 사운드/이펙트용 사건. 가져가면 비워진다. */
    public List<ItemEvent> drainEvents() { return api.drainEvents(); }

    public int getLevelNumber() { return levelNumber; }

    private boolean isLevelActive() { return hooks != null && manager.isLevelActive(); }

    /** 목숨 아이템: 상한 미만이면 목숨 +1, 상한이면 점수 보상. 어느 쪽이든 아이템은 소비된다. */
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
        Map<DropSource, DropRule> rules = new EnumMap<DropSource, DropRule>(DropSource.class);
        rules.put(DropSource.REGULAR_ENEMY, new DropRule(balance.regularDropProbability, weights));
        rules.put(DropSource.SPECIAL_ENEMY, new DropRule(balance.specialDropProbability, weights));
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
