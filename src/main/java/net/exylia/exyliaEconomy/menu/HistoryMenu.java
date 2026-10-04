package net.exylia.exyliaEconomy.menu;

import net.exylia.exyliaEconomy.ExyliaEconomy;
import net.exylia.exyliaEconomy.common.Values;
import net.exylia.exyliaEconomy.manager.StoredEconomy;
import net.exylia.exyliaEconomy.model.LedgerEntry;
import net.exylia.exyliaEconomy.service.EconomyActions;
import net.exylia.lib.economy.CurrencyInfo;
import net.exylia.lib.economy.Economy;
import net.exylia.lib.format.Formats;
import net.exylia.lib.player.ExyliaPlayer;
import net.exylia.lib.ui.UiEntry;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Where somebody's money went, one line per movement.
 *
 * <p>Only stored currencies keep a ledger, so a currency this plugin does not
 * store — Vault, an item, XP — opens an empty board rather than a lie.
 */
public final class HistoryMenu {

    /** The menu's file, as {@link EconomyMenus} registers it. */
    public static final String ID = "menus/user/history";

    /** How far back the board goes. */
    private static final int DEPTH = 90;

    private HistoryMenu() {
        throw new AssertionError("No instances.");
    }

    public static void open(Player viewer, String currency, ExyliaPlayer owner) {
        CurrencyInfo info = Economy.info(currency);
        StoredEconomy.history(currency, owner.id(), DEPTH).thenAccept(lines ->
                ExyliaEconomy.getInstance().getTasks().runAtEntity(viewer, () -> {
                    if (!viewer.isOnline()) return;
                    ExyliaEconomy.getInstance().getMenus().open(viewer, ID, Values.of()
                            .put("currency_id", currency)
                            .put("currency", info.namePlural())
                            .put("currency_icon", info.icon())
                            .put("history_owner", owner.name())
                            .put("history_owner_id", owner.id().toString())
                            .put("history_size", lines.size())
                            .map(), rows(lines, info));
                }));
    }

    private static List<UiEntry> rows(List<LedgerEntry> lines, CurrencyInfo info) {
        return lines.stream().map(line -> {
            UiEntry.Builder row = UiEntry.of(line);
            row.withFormatted("entry_reason", EconomyActions.reasonLabel(line.reason()));
            row.with("entry_server", line.server());
            row.withFormatted("entry_when", Formats.relative(line.at()));
            row.withFormatted("entry_delta", (line.isDeposit() ? "{success}+" : "{error}-")
                    + info.format(line.delta().abs()));
            row.withFormatted("entry_balance", info.format(line.balanceAfter()));
            row.withFormatted("entry_icon", line.isDeposit() ? "LIME_DYE" : "RED_DYE");
            return row.build();
        }).toList();
    }
}
