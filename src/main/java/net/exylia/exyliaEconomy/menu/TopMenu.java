package net.exylia.exyliaEconomy.menu;

import net.exylia.exyliaEconomy.ExyliaEconomy;
import net.exylia.lib.text.Values;
import net.exylia.exyliaEconomy.manager.StoredEconomy;
import net.exylia.lib.economy.CurrencyInfo;
import net.exylia.lib.economy.Economy;
import net.exylia.lib.ui.UiEntry;
import org.bukkit.entity.Player;

import java.util.List;

/** Who has the most of one currency, as a board rather than ten chat lines. */
public final class TopMenu {

    /** The menu's file, as {@link EconomyMenus} registers it. */
    public static final String ID = "menus/user/top";

    /** How deep the board goes. Past this nobody is reading anyway. */
    private static final int DEPTH = 90;

    private TopMenu() {
        throw new AssertionError("No instances.");
    }

    public static void open(Player viewer, String currency) {
        CurrencyInfo info = Economy.info(currency);
        // Cached for a minute, so paging back and forth is free; the first
        // board after a start waits for its read instead of opening empty.
        StoredEconomy.topLater(currency, DEPTH).thenAccept(entries ->
                ExyliaEconomy.getInstance().getTasks().runAtEntity(viewer, () -> open(viewer, currency, info, entries)));
    }

    private static void open(Player viewer, String currency, CurrencyInfo info, List<StoredEconomy.TopEntry> entries) {
        if (!viewer.isOnline()) return;
        double leader = entries.isEmpty() ? 0 : entries.get(0).amount().doubleValue();
        StoredEconomy.TopEntry me = entries.stream()
                .filter(entry -> entry.player().equals(viewer.getUniqueId())).findFirst().orElse(null);
        List<UiEntry> rows = entries.stream().map(entry -> {
            UiEntry.Builder row = UiEntry.of(entry);
            row.with("top_percent", leader <= 0 ? 0 : (int) Math.ceil(entry.amount().doubleValue() / leader * 100));
            row.with("top_me", entry == me);
            row.with("top_position", entry.position());
            row.with("top_player", entry.name());
            row.withFormatted("top_amount", info.format(entry.amount()));
            row.withFormatted("currency", info.namePlural());
            return row.build();
        }).toList();

        ExyliaEconomy.getInstance().getMenus().open(viewer, ID, Values.of()
                .put("owner_name", viewer.getName())
                .put("owner_id", viewer.getUniqueId().toString())
                .put("currency_id", currency)
                .put("currency_ranked", true)
                .put("top_me_place", me == null ? "—" : "#" + me.position())
                .put("top_me_amount", me == null ? "—" : info.format(me.amount()))
                .put("currency", info.namePlural())
                .put("currency_icon", info.icon())
                .put("top_size", entries.size())
                .put("top_total", info.format(StoredEconomy.total(currency)))
                .map(), rows);
    }
}
