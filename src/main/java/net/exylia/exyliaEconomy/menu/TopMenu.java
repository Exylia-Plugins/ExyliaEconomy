package net.exylia.exyliaEconomy.menu;

import net.exylia.exyliaEconomy.ExyliaEconomy;
import net.exylia.exyliaEconomy.common.Values;
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
        // Cached for a minute, so paging back and forth is free.
        List<StoredEconomy.TopEntry> entries = StoredEconomy.top(currency, DEPTH);
        List<UiEntry> rows = entries.stream().map(entry -> {
            UiEntry.Builder row = UiEntry.of(entry);
            row.with("top_position", entry.position());
            row.with("top_player", entry.name());
            row.withFormatted("top_amount", info.format(entry.amount()));
            row.withFormatted("currency", info.namePlural());
            return row.build();
        }).toList();

        ExyliaEconomy.getInstance().getMenus().open(viewer, ID, Values.of()
                .put("currency_id", currency)
                .put("currency", info.namePlural())
                .put("top_size", entries.size())
                .map(), rows);
    }
}
