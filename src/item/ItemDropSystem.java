package item;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import item.ItemAPI.*;

/**
 * 아이템 개체 생성/낙하/착지/접촉/소멸의 원본 상태를 소유한다.
 * 내부에 private static class DroppedItem을 만들어 id/ItemInfo/double 위치/착지/TTL을 저장한다.
 * 별도 파일, Entity 상속, 인벤토리/효과 변경, 게임 객체 참조를 추가하지 않는다.
 * 이벤트 큐는 여기 두지 않는다. 생성/만료/접촉 결과를 매니저가 받아 사건으로 기록한다.
 */
class ItemDropSystem {
    private final ItemDefinitions definitions;
    private final Random random;
    private final List<DroppedItem> items = new ArrayList<DroppedItem>();
    private long nextDropId = 1;
    private LevelRules rules;
    ItemDropSystem(ItemDefinitions definitions, Random random) {
        this.definitions = ItemAPI.required(definitions, "definitions");
        this.random = ItemAPI.required(random, "random");
    }

    /** 검증된 새 규칙을 설치하고 개체 목록 초기화. dropId 증가 카운터는 재사용하지 않는다. */
    void beginLevel(LevelRules rules) {
        this.rules = ItemAPI.required(rules, "rules");
        items.clear();
    }

    /**
     * source의 확률/가중치로 종류 하나를 추첨하고 실제 목록에 등록한 뒤 불변 View 반환.
     * 정상 미드랍만 null. p==0/1은 확률용 난수를 소비하지 않는다. 단일 후보도 선택 난수 생략.
     * 가중치 순서는 LinkedHashMap 삽입 순서. 중심을 cx/cy에 맞추고 플레이 영역으로 보정한다.
     * 일반 확률 0.15/특수 1.0은 데모 정책이며 실제 수치는 LevelRules에서 받는다.
     * 같은 적의 중복 처치 통보 방지는 외부 게임이 담당한다.
     */
    DropView spawn(DropSource source, double cx, double cy) {
        requireLevel();
        ItemAPI.required(source, "source");
        ItemAPI.finite(cx, "x"); ItemAPI.finite(cy, "y");
        DropRule rule = rules.dropRules.get(source);
        if (rule == null) throw new IllegalStateException("missing drop rule: " + source);
        if (rule.probability == 0 || (rule.probability < 1 && random.nextDouble() >= rule.probability))
            return null;

        double total = 0;
        int candidates = 0;
        String onlyId = null;
        for (Map.Entry<String, Double> entry : rule.weights.entrySet()) {
            if (entry.getValue() > 0) {
                total += entry.getValue();
                candidates++;
                onlyId = entry.getKey();
            }
        }
        if (candidates == 0 || !Double.isFinite(total))
            throw new IllegalStateException("invalid drop weights");
        String selected = onlyId;
        if (candidates > 1) {
            double choice = random.nextDouble() * total;
            for (Map.Entry<String, Double> entry : rule.weights.entrySet()) {
                if (entry.getValue() <= 0) continue;
                choice -= entry.getValue();
                if (choice < 0) { selected = entry.getKey(); break; }
            }
        }
        ItemInfo item = definitions.find(selected);
        if (item == null) throw new IllegalStateException("unknown drop item: " + selected);
        double x = Math.max(rules.left, Math.min(cx - rules.pickupWidth / 2, rules.right - rules.pickupWidth));
        double y = Math.max(rules.top, Math.min(cy - rules.pickupHeight / 2, rules.floorY - rules.pickupHeight));
        DroppedItem dropped = new DroppedItem(nextDropId++, item, x, y,
            y >= rules.floorY - rules.pickupHeight, rules.groundLifetimeMillis);
        items.add(dropped);
        return view(dropped);
    }

    /**
     * delta>=0 밀리초만큼 개체 이동. y += fallSpeed * delta / 1000.0.
     * 아래쪽이 floorY에 닿으면 멈추며, 프레임 중간 착지라면 착지 이후 시간만 TTL 차감.
     * 1) 만료 개체를 먼저 목록에서 제거해 expired에 넣는다.
     * 2) 나머지는 수직 이전/현재 구간과 플레이어 사각형의 접촉을 검사한다(경계 포함).
     *    canPickup=false이면 contacts는 빈 목록. delta=0이라도 허용된 현재 접촉은 검사.
     * 3) contacts는 중복 없는 dropId 오름차순이며, 이 개체들은 아직 원본 목록에서 제거하지 않는다.
     * 같은 직렬 update 동안 completePickup 이외에 contacts를 변경/제거하지 않는다.
     * 획득 가능 여부/효과 적용은 매니저가 판단한다. 여기서 저장·소비·이벤트 발행은 금지한다.
     */
    Frame advance(long delta, PlayerSnapshot player) {
        requireLevel();
        if (delta < 0) throw new IllegalArgumentException("negative delta");
        ItemAPI.required(player, "player");
        List<DropView> expired = new ArrayList<DropView>();
        List<DropView> contacts = new ArrayList<DropView>();
        Iterator<DroppedItem> iterator = items.iterator();
        double floorTop = rules.floorY - rules.pickupHeight;
        while (iterator.hasNext()) {
            DroppedItem dropped = iterator.next();
            double previousY = dropped.y;
            long groundedMillis = delta;
            if (!dropped.grounded) {
                double distance = floorTop - dropped.y;
                double travel = rules.fallSpeed * (delta / 1000.0);
                if (travel >= distance) {
                    dropped.y = floorTop;
                    dropped.grounded = true;
                    double flightMillis = distance * 1000.0 / rules.fallSpeed;
                    groundedMillis = (long) Math.ceil(Math.max(0, flightMillis));
                    groundedMillis = delta - Math.min(delta, groundedMillis);
                } else {
                    dropped.y += travel;
                    groundedMillis = 0;
                }
            }
            if (dropped.grounded) {
                dropped.remainingMillis -= Math.min(dropped.remainingMillis, groundedMillis);
                if (dropped.remainingMillis == 0) {
                    expired.add(view(dropped));
                    iterator.remove();
                    continue;
                }
            }
            if (player.canPickup && intersects(dropped.x, previousY, dropped.y, player.bounds))
                contacts.add(view(dropped));
        }
        return new Frame(expired, contacts);
    }

    /**
     * 매니저가 획득 성공한 contact 하나를 제거한다. 유효한 단일 갱신 처리 안에서 반드시 완료된다.
     * 없는 ID면 통합 버그이므로 예외. 이미 지급됐는데 조용히 제거 실패하는 코드는 금지한다.
     */
    void completePickup(long dropId) {
        Iterator<DroppedItem> iterator = items.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().id == dropId) { iterator.remove(); return; }
        }
        throw new IllegalStateException("unknown dropId: " + dropId);
    }

    /** dropId 순서의 불변 View 사본. 원본 개체/목록은 노출하지 않는다. */
    List<DropView> snapshot() {
        List<DropView> views = new ArrayList<DropView>();
        for (DroppedItem dropped : items) views.add(view(dropped));
        return ItemAPI.frozen(views, false);
    }

    /** 스테이지 종료 시 개체/현재 규칙 해제. 시간 만료 사건은 만들지 않고 ID는 유지한다. */
    void clear() { items.clear(); rules = null; }

    private void requireLevel() {
        if (rules == null) throw new IllegalStateException("level is inactive");
    }

    private DropView view(DroppedItem dropped) {
        return new DropView(dropped.id, dropped.item,
            new Bounds(dropped.x, dropped.y, rules.pickupWidth, rules.pickupHeight), dropped.grounded);
    }

    private boolean intersects(double x, double previousY, double currentY, Bounds player) {
        return x <= player.x + player.width && x + rules.pickupWidth >= player.x
            && previousY <= player.y + player.height
            && currentY + rules.pickupHeight >= player.y;
    }

    private static final class DroppedItem {
        final long id;
        final ItemInfo item;
        final double x;
        double y;
        boolean grounded;
        long remainingMillis;
        DroppedItem(long id, ItemInfo item, double x, double y, boolean grounded, long remainingMillis) {
            this.id = id; this.item = item; this.x = x; this.y = y;
            this.grounded = grounded; this.remainingMillis = remainingMillis;
        }
    }

    /** 내부 협력용 결과. 값 객체만 완성하며 이동/접촉 계산은 없다. */
    static final class Frame {
        final List<DropView> expired, contacts;
        Frame(List<DropView> expired, List<DropView> contacts) {
            this.expired = ItemAPI.frozen(expired, false);
            this.contacts = ItemAPI.frozen(contacts, false);
        }
    }
}
