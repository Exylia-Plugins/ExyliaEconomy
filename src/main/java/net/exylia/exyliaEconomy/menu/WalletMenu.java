package net.exylia.exyliaEconomy.menu;

import net.exylia.exyliaEconomy.ExyliaEconomy;
import net.exylia.lib.text.Values;
import net.exylia.exyliaEconomy.service.EconomyActions;
import net.exylia.lib.economy.CurrencyInfo;
import net.exylia.lib.economy.Economy;
import net.exylia.lib.player.ExyliaPlayer;
import net.exylia.lib.ui.UiEntry;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A wallet: every currency somebody holds, on one screen.
 *
 * <p>The chat version said the same thing in one line per currency, which on a
 * server with six of them is six lines of scrollback and no way to act on any
 * of them. Here each currency is an item: what it is worth, what kind it is,
 * and a click into its history or its leaderboard.
 *
 * <p>Console keeps the lines — there is no screen to open — so
 * {@link EconomyActions#wallet} still writes them for anything that is not a
 * player.
 */
public final class WalletMenu {

    /** The menu's file, as {@link EconomyMenus} registers it. */
    public static final String ID = "menus/user/wallet";

    private WalletMenu() {
        throw new AssertionError("No instances.");
    }

    /**
     * Reads every balance, then opens the screen.
     *
     * <p>Every currency at once rather than one read per draw: a wallet drawn
     * as the reads land is a wallet in no order.
     */
    public static void open(Player viewer, ExyliaPlayer owner) {
        List<String> ids = visible(viewer);
        List<CompletableFuture<BigDecimal>> reads = new ArrayList<>(ids.size());
        for (String id : ids) reads.add(read(id, owner));

        CompletableFuture.allOf(reads.toArray(new CompletableFuture[0])).thenRun(() ->
                ExyliaEconomy.getInstance().getTasks().runAtEntity(viewer, () -> {
                    if (!viewer.isOnline()) return;
                    List<UiEntry> rows = new ArrayList<>(ids.size());
                    for (int index = 0; index < ids.size(); index++) {
                        BigDecimal balance = reads.get(index).join();
                        if (balance != null) rows.add(row(ids.get(index), balance, owner));
                    }
                    ExyliaEconomy.getInstance().getMenus()
                            .open(viewer, ID, context(owner, ids.size()).map(), rows);
                }));
    }

    /**
     * One balance of a wallet, or {@code null} once logged when it cannot be read.
     *
     * <p>Never fails: one currency whose read failed would otherwise take the
     * whole wallet with it, and the viewer would get no answer at all.
     */
    public static CompletableFuture<BigDecimal> read(String currency, ExyliaPlayer owner) {
        return Economy.of(currency).balanceLater(owner.id()).exceptionally(failure -> {
            ExyliaEconomy.getInstance().getDebug()
                    .error("Could not read the " + currency + " balance of " + owner.name(), failure);
            return null;
        });
    }

    /** The currencies this viewer is allowed to see at all. */
    public static List<String> visible(CommandSender viewer) {
        List<String> ids = new ArrayList<>();
        for (String id : Economy.ordered()) {
            if (Economy.canUse(viewer, id)) ids.add(id);
        }
        return ids;
    }

    private static Values context(ExyliaPlayer owner, int currencies) {
        return Values.of()
                .put("wallet_owner", owner.name())
                .put("wallet_owner_id", owner.id().toString())
                .put("wallet_currencies", currencies);
    }

    private static UiEntry row(String id, BigDecimal balance, ExyliaPlayer owner) {
        CurrencyInfo info = Economy.info(id);
        UiEntry.Builder row = UiEntry.of(id);
        row.with("currency_id", id);
        row.with("wallet_owner_id", owner.id().toString());
        row.withFormatted("currency", info.namePlural());
        row.withFormatted("currency_icon", info.icon());
        row.withFormatted("currency_balance", info.format(balance));
        row.withFormatted("currency_kind", EconomyActions.kindLabel(id));
        return row.build();
    }
}
