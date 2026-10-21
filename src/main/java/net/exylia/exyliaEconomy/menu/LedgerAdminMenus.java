package net.exylia.exyliaEconomy.menu;

import net.exylia.exyliaEconomy.ExyliaEconomy;
import net.exylia.exyliaEconomy.database.LedgerRow;
import net.exylia.exyliaEconomy.service.EconomyActions;
import net.exylia.lib.economy.CurrencyInfo;
import net.exylia.lib.economy.Economy;
import net.exylia.lib.format.Formats;
import net.exylia.lib.player.ExyliaPlayer;
import net.exylia.lib.player.ExyliaPlayers;
import net.exylia.lib.text.Values;
import net.exylia.lib.ui.UiEntry;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The ledger {@code /economyadmin log} opens for a player: one player's lines in one currency,
 * newest first, each opening on its own with a button that rolls it back.
 */
public final class LedgerAdminMenus {

    public static final String LIST = "menus/admin/ledger_admin_list";
    public static final String ENTRY = "menus/admin/ledger_admin_entry";

    private LedgerAdminMenus() {
        throw new AssertionError("No instances.");
    }

    public static void openList(Player viewer, ExyliaPlayer owner, String currency, List<LedgerRow> lines) {
        CurrencyInfo info = Economy.info(currency);
        menus().open(viewer, LIST, Values.of()
                .put("ledger_owner", owner.name())
                .put("currency_id", currency)
                .put("currency", info.namePlural())
                .put("currency_icon", info.icon())
                .put("ledger_size", lines.size())
                .map(), lines.stream().map(line -> UiEntry.of(line)
                .with("ledger_owner", owner.name())
                .with("currency_id", currency)
                .withFormatted("currency", info.namePlural())
                .with("entry_id", line.id())
                .withFormatted("entry_icon", icon(line.delta()))
                .withFormatted("entry_delta", signed(info, line.delta()))
                .withFormatted("entry_balance", info.format(line.balanceAfter()))
                .withFormatted("entry_reason", EconomyActions.reasonLabel(line.reason()))
                .withFormatted("entry_when", Formats.relative(line.createdAt()))
                .build()).toList());
    }

    public static void openEntry(Player viewer, LedgerRow line, String currency) {
        CurrencyInfo info = Economy.info(currency);
        String owner = ExyliaPlayers.nameOr(UUID.fromString(line.player()), line.player());
        String initiator = line.initiator() == null ? "-"
                : ExyliaPlayers.nameOr(UUID.fromString(line.initiator()), line.initiator());
        menus().open(viewer, ENTRY, Values.of()
                .put("ledger_owner", owner)
                .put("currency_id", currency)
                .put("currency", info.namePlural())
                .put("entry_id", line.id())
                .put("entry_icon", icon(line.delta()))
                .put("entry_delta", signed(info, line.delta()))
                .put("entry_balance", info.format(line.balanceAfter()))
                .put("entry_reason", EconomyActions.reasonLabel(line.reason()))
                .put("entry_initiator", initiator)
                .put("entry_server", line.server() == null ? "-" : line.server())
                .put("entry_when", Formats.relative(line.createdAt()))
                .map());
    }

    private static String icon(BigDecimal delta) {
        return delta.signum() >= 0 ? "LIME_DYE" : "RED_DYE";
    }

    private static String signed(CurrencyInfo info, BigDecimal delta) {
        return (delta.signum() >= 0 ? "{success}+" : "{error}-") + info.format(delta.abs());
    }

    private static EconomyMenus menus() {
        return ExyliaEconomy.getInstance().getMenus();
    }
}
