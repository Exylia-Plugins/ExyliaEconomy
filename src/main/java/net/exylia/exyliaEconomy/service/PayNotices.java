package net.exylia.exyliaEconomy.service;

import net.exylia.exyliaEconomy.ExyliaEconomy;
import net.exylia.lib.text.Values;
import net.exylia.exyliaEconomy.config.EconomyMessages;
import net.exylia.exyliaEconomy.database.PayNoticeRow;
import net.exylia.exyliaEconomy.manager.StoredEconomy;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Repository;
import net.exylia.lib.economy.CurrencyInfo;
import net.exylia.lib.economy.Economy;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * "While you were away": payments received off this server, summed up on the next join.
 *
 * <p>A payment to somebody not on the paying server leaves a {@link PayNoticeRow}; whichever
 * server they join next takes the rows by deleting them and shows one line per currency. Rows of
 * their own rather than the ledger, because the ledger can be turned off and is keyed per server
 * for local currencies.
 *
 * <p>ponytail: rows of players who never come back stay; a retention sweep belongs here if the
 * table ever grows enough to matter.
 */
public final class PayNotices implements Listener {

    /** Long enough after the join to land below the welcome messages. */
    private static final long DELAY_TICKS = 60L;

    private final Repository<PayNoticeRow> rows;

    /** One currency's payments: the sum, how many players sent them, and the one name when it was one. */
    record Summary(String currency, BigDecimal amount, int payers, String payerName) { }

    public PayNotices(@NotNull Plugin plugin) {
        this.rows = Databases.of(plugin).repository(PayNoticeRow.class);
    }

    /** Remembers a payment to somebody who is not on this server. */
    public void record(UUID receiver, Player payer, String currency, BigDecimal amount) {
        if (!StoredEconomy.settings().offlinePayNotice()) return;
        rows.insert(new PayNoticeRow(receiver, currency, amount, payer.getUniqueId(), payer.getName()))
                .exceptionally(failure -> {
                    ExyliaEconomy.getInstance().getDebug().error("Economy: could not keep the payment notice for "
                            + receiver + ".", failure);
                    return null;
                });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!StoredEconomy.settings().offlinePayNotice()) return;
        Player player = event.getPlayer();
        take(player.getUniqueId()).thenAccept(taken -> {
            List<Summary> summaries = summarise(taken);
            if (summaries.isEmpty()) return;
            ExyliaEconomy.getInstance().getTasks().runAtEntityLater(player, DELAY_TICKS, () -> show(player, summaries));
        }).exceptionally(failure -> {
            ExyliaEconomy.getInstance().getDebug().error("Economy: could not read the payment notices of "
                    + player.getName() + ".", failure);
            return null;
        });
    }

    private static void show(Player player, List<Summary> summaries) {
        EconomyMessages text = EconomyMessages.get();
        for (Summary summary : summaries) {
            CurrencyInfo info = Economy.info(summary.currency());
            Values values = Values.of().put("amount", info.format(summary.amount()))
                    .put("player", summary.payerName()).put("count", summary.payers());
            ExyliaEconomy.getInstance().getMessages().send(player, summary.payers() == 1 ? text.offlinePaySingle() : text.offlinePayMany(), values);
        }
    }

    /**
     * Takes a player's rows, once: a row is theirs to show only if this server's delete removed
     * it, so two joins in quick succession never show the same payment twice.
     */
    CompletableFuture<List<PayNoticeRow>> take(UUID player) {
        return rows.where("player", player.toString()).find().thenCompose(found -> {
            List<CompletableFuture<Boolean>> deletes = new ArrayList<>(found.size());
            for (PayNoticeRow row : found) deletes.add(rows.delete(row.id()).exceptionally(failure -> false));
            return CompletableFuture.allOf(deletes.toArray(new CompletableFuture[0])).thenApply(ignored -> {
                List<PayNoticeRow> taken = new ArrayList<>(found.size());
                for (int index = 0; index < found.size(); index++) {
                    if (Boolean.TRUE.equals(deletes.get(index).join())) taken.add(found.get(index));
                }
                return taken;
            });
        });
    }

    /** One summary per currency, in the order the first payment of each arrived. */
    static List<Summary> summarise(List<PayNoticeRow> rows) {
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        Map<String, Set<String>> payers = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (PayNoticeRow row : rows.stream().sorted(java.util.Comparator.comparingLong(PayNoticeRow::id)).toList()) {
            sums.merge(row.currency(), row.amount(), BigDecimal::add);
            payers.computeIfAbsent(row.currency(), ignored -> new HashSet<>()).add(row.payer());
            names.putIfAbsent(row.currency(), row.payerName());
        }
        List<Summary> out = new ArrayList<>(sums.size());
        sums.forEach((currency, amount) -> out.add(new Summary(currency, amount, payers.get(currency).size(),
                names.get(currency))));
        return out;
    }
}
