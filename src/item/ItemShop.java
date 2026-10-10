package item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import item.ItemAPI.GrantRequest;
import item.ItemAPI.GrantSource;
import item.ItemAPI.GrantTiming;
import item.ItemAPI.ItemInfo;

/**
 * Immutable sale catalog supplied by the item team to the shop team.
 * Prices and descriptions are available without creating or starting a run.
 * This class does not draw a screen, debit coins, save purchases or own stock.
 *
 * The shop owner uses entries() to render products and Entry.grantRequest()
 * with the shared run's ItemAPI.checkGrant()/tryGrant() to deliver an item.
 * Payment, persistent ownership and delivery into a new run belong to that
 * caller. A repeated request ID must never cause a second payment: inspect
 * checkGrant() and its ALREADY_PROCESSED result before charging.
 *
 * The game owner applies ItemSystem.fireRateBonus(baseFireRate),
 * movementSpeedBonus(baseSpeed) and scoreFor(basePoints) to its own values.
 * Those helpers expose item effects without changing the game or ship code.
 */
public final class ItemShop {
    private static ItemShop instance;

    /** Product definition and its configured price in coins. */
    public static final class Entry {
        public final ItemInfo item;
        public final int priceCoins;

        private Entry(ItemInfo item, int priceCoins) {
            this.item = ItemAPI.required(item, "item");
            if (priceCoins <= 0) throw new IllegalArgumentException("priceCoins");
            this.priceCoins = priceCoins;
        }

        /** Builds an item-delivery request; this does not charge or grant anything. */
        public GrantRequest grantRequest(String requestId) {
            return new GrantRequest(requestId, item.itemId, GrantSource.SHOP, GrantTiming.NOW);
        }
    }

    private final List<Entry> entries;

    /** Loads a catalog from the current item settings. */
    public ItemShop() { this(ItemBalance.load()); }

    ItemShop(ItemBalance balance) {
        ItemAPI.required(balance, "balance");
        ItemDefinitions definitions = new ItemDefinitions(balance);
        List<Entry> products = new ArrayList<Entry>();
        products.add(new Entry(definitions.find("boost"), balance.shopBoostPriceCoins));
        products.add(new Entry(definitions.find("score_boost"), balance.shopScoreBoostPriceCoins));
        entries = Collections.unmodifiableList(products);
    }

    /** Shared catalog, loaded on first request. No purchases are stored in it. */
    public static ItemShop getInstance() {
        if (instance == null) instance = new ItemShop();
        return instance;
    }

    public List<Entry> entries() { return entries; }

    /** Returns the listed product, or null for a valid ID that is not on sale. */
    public Entry find(String itemId) {
        ItemAPI.text(itemId, "itemId");
        for (Entry entry : entries) if (entry.item.itemId.equals(itemId)) return entry;
        return null;
    }
}
