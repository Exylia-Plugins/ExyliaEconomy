package net.exylia.exyliaEconomy.action;

import net.exylia.lib.economy.Economy;
import net.exylia.exyliaEconomy.ExyliaEconomy;
import net.exylia.exyliaEconomy.menu.HistoryMenu;
import net.exylia.exyliaEconomy.menu.TopMenu;
import net.exylia.exyliaEconomy.menu.WalletMenu;
import net.exylia.exyliaEconomy.service.EconomyActions;
import net.exylia.lib.action.ActionResult;
import net.exylia.lib.action.PluginActions;
import net.exylia.lib.player.ExyliaPlayer;
import net.exylia.lib.player.ExyliaPlayers;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** The {@code exyliaeconomy:economy_*} actions the wallet, the board and the ledger bind. */
public final class EconomyActionRegister {

    private final ExyliaEconomy plugin;
    private final PluginActions actions;

    public EconomyActionRegister(ExyliaEconomy plugin) {
        this.plugin = plugin;
        this.actions = plugin.getActions();
    }

    public void registerAll() {
        new CurrencyAdminActions(plugin).registerAll();

        actions.registerSync("economy_wallet", (ctx, args) -> {
            Player player = ctx.player();
            if (player != null) WalletMenu.open(player, owner(player, args.string(0, "")));
            return ActionResult.success();
        });

        actions.registerSync("economy_history", (ctx, args) -> {
            Player player = ctx.player();
            String currency = args.string(0, "");
            // A currency the viewer may not use is not shown to them and not
            // opened for them either: the click is where that is checked.
            if (player != null && EconomyActions.currency(currency).isPresent()
                    && Economy.canUse(player, currency)) {
                HistoryMenu.open(player, currency, owner(player, args.string(1, "")));
            }
            return ActionResult.success();
        });

        actions.registerSync("economy_top", (ctx, args) -> {
            Player player = ctx.player();
            String currency = args.string(0, "");
            if (player != null && EconomyActions.currency(currency).isPresent()
                    && Economy.canUse(player, currency)) {
                TopMenu.open(player, currency);
            }
            return ActionResult.success();
        });
    }

    /** Whose wallet a click is about: the one named, or the one clicking. */
    private static ExyliaPlayer owner(Player viewer, @Nullable String id) {
        if (id == null || id.isBlank()) return ExyliaPlayers.of(viewer);
        try {
            UUID parsed = UUID.fromString(id);
            ExyliaPlayer known = ExyliaPlayers.cached(parsed);
            return known != null ? known
                    : ExyliaPlayers.of(parsed, ExyliaPlayers.nameOr(parsed, "?"));
        } catch (IllegalArgumentException notAnId) {
            return ExyliaPlayers.of(viewer);
        }
    }
}
