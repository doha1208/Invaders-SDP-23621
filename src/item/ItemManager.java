package item;

import java.util.List;

/** 아이템 API의 구현 전 골격입니다. 반환값은 아직 임시값입니다. */
public final class ItemManager {
    private ItemCatalog catalog;
    private Inventory inventory;
    private EffectController effects;
    private boolean levelEnded;

    public ItemManager(ItemCatalog catalog, Inventory inventory,
            EffectController effects) {
        // TODO: 전달받은 객체를 필드에 연결한다. 인벤토리는 플레이어가 소유한다.
    }

    public ItemInfo getItemInfo(String itemId) {
        // TODO: catalog에서 아이템 정의를 조회한다. 없는 ID이면 null을 반환한다.
        return null;
    }

    public ItemCreateResult createItem(String itemId, double x, double y) {
        // TODO: ID와 좌표를 확인하고 Item을 생성한다. 화면 등록은 게임이 담당한다.
        return null;
    }

    public List<InventorySlot> getInventory() {
        // TODO: 인벤토리의 슬롯 정보를 읽기 전용 복사본으로 반환한다.
        return null;
    }

    public List<ActiveEffectInfo> getActiveEffects() {
        // TODO: 현재 지속 중인 액티브/패시브 효과의 조회 정보를 반환한다.
        return null;
    }

    public ItemResult acquireItem(String itemId) {
        // TODO: 종료 상태와 ID를 확인하고, 액티브는 저장/패시브는 효과 적용을 요청한다.
        return null;
    }

    public ItemResult useItem(int slotIndex) {
        // TODO: 종료 상태와 슬롯을 확인하고, 효과 적용에 성공한 경우에만 소비한다.
        return null;
    }

    public void updateEffects(long elapsedGameMillis) {
        // TODO: 진행 시간을 검증하고 effects에 전달한다. 일시정지 중에는 호출하지 않는다.
    }

    public void endLevel() {
        // TODO: 이중 종료를 막고 효과를 해제한다. 인벤토리 초기화는 별도 정책으로 처리한다.
    }
}
