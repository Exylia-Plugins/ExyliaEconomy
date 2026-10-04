package net.exylia.exyliaEconomy.service;

import net.exylia.exyliaEconomy.ExyliaEconomy;
import net.exylia.exyliaEconomy.Permissions;
import net.exylia.exyliaEconomy.common.Messages;
import net.exylia.exyliaEconomy.common.Values;
import net.exylia.exyliaEconomy.config.EconomyConfig;
import net.exylia.exyliaEconomy.config.EconomyMessages;
import net.exylia.exyliaEconomy.database.BalanceRow;
import net.exylia.exyliaEconomy.database.LedgerRow;
import net.exylia.exyliaEconomy.manager.Claims;
import net.exylia.exyliaEconomy.manager.StoredEconomy;
import net.exylia.lib.database.Databases;
import net.exylia.lib.economy.CurrencyInfo;
import net.exylia.lib.economy.Economy;
import net.exylia.lib.economy.Transaction;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.util.TimeFormats;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Periodic interest on stored currencies, as {@code config.yml} sets it per currency.
 *
 * <p>Time is cut into slots of the currency's interval since the epoch; a payout happens once the
 * clock enters a new slot, never on the slot a server starts in. Each payout is claimed per player
 * and slot ({@code interest|<key>|<slot>|<player>}), so a networked currency is paid once however
 * many servers see the player, and a restart never pays a slot again. Each server pays only the
 * players on it; with {@code online-only: false} one server per slot also pays every other
 * balance row, queued for whoever holds it.
 */
public final class Interest {

    private static final long CHECK_TICKS = 600L;
    private static final long MIN_INTERVAL_MILLIS = 60_000L;
    /** Claims outlive their slot by this much; older ones are deleted. */
    private static final int KEEP_DAYS = 2;
    private static final int PURGE_LOOKBACK_DAYS = 7;
    private static final int OFFLINE_PAGE = 500;

    private final Plugin plugin;
    private final Claims claims;
    /** The slot each currency was last paid for here, or started in. */
    private final Map<String, Long> slots = new ConcurrentHashMap<>();
    private TaskHandle timer;

    public Interest(@NotNull Plugin plugin) {
        this.plugin = plugin;
        this.claims = new Claims(plugin);
    }

    public void start() {
        timer = ExyliaEconomy.getInstance().getTasks().runTimer(CHECK_TICKS, CHECK_TICKS, this::check);
        purge();
    }

    public void stop() {
        if (timer != null) timer.cancel();
    }

    /** The slot a moment falls in. */
    static long slot(long now, long intervalMillis) {
        return now / intervalMillis;
    }

    /** The claim of one player's payout in one slot. */
    static String claimId(String key, long slot, UUID player) {
        return "interest|" + key + "|" + slot + "|" + player;
    }

    /**
     * What a balance earns in one payout: {@code rate} percent, cut to the currency's decimals, and
     * never more than {@code max} when that is set.
     */
    static BigDecimal earned(BigDecimal balance, double ratePercent, @Nullable BigDecimal max, int decimals) {
        if (balance.signum() <= 0 || ratePercent <= 0) return BigDecimal.ZERO;
        BigDecimal amount = balance.multiply(BigDecimal.valueOf(ratePercent))
                .divide(BigDecimal.valueOf(100), Math.max(0, decimals), RoundingMode.DOWN);
        return max != null && max.signum() > 0 ? amount.min(max) : amount;
    }

    private void check() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, EconomyConfig.Interest> entry : EconomyConfig.get().interestOrEmpty().entrySet()) {
            String currency = EconomyActions.currency(entry.getKey()).orElse(null);
            String key = currency == null ? null : StoredEconomy.storageKey(currency);
            EconomyConfig.Interest settings = entry.getValue();
            long interval = settings.interval() == null ? 0L : settings.interval().toMillis();
            if (key == null || interval < MIN_INTERVAL_MILLIS || settings.rate() <= 0) continue;
            long slot = slot(now, interval);
            Long last = slots.putIfAbsent(key, slot);
            if (last == null || slot <= last) continue;
            slots.put(key, slot);
            payOnline(currency, key, slot, settings, Duration.ofMillis(interval - now % interval));
            if (!settings.onlineOnly()) payOffline(currency, key, slot, settings);
        }
        if (now / LedgerRow.DAY_MILLIS != (now - CHECK_TICKS * 50L) / LedgerRow.DAY_MILLIS) purge();
    }

    private void payOnline(String currency, String key, long slot, EconomyConfig.Interest settings, Duration next) {
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            UUID id = player.getUniqueId();
            if (!player.hasPermission(Permissions.INTEREST) || !Economy.canUse(player, currency)
                    || StoredEconomy.loading(id, currency)) continue;
            pay(currency, key, slot, id, Economy.of(currency).balance(id), settings).thenAccept(paid -> {
                if (paid.signum() <= 0) return;
                ExyliaEconomy.getInstance().getTasks().runAtEntity(player, () -> Messages.send(player,
                        EconomyMessages.get().interestPaid(), Values.of().put("amount", Economy.info(currency).format(paid))
                                .put("currency", Economy.info(currency).namePlural())
                                .put("next", TimeFormats.render(next, TimeFormats.Style.COMPACT))));
            });
        }
    }

    /** Claims and pays one player's slot; completes with what was paid, zero when nothing was. */
    private CompletableFuture<BigDecimal> pay(String currency, String key, long slot, UUID player, BigDecimal balance,
                                              EconomyConfig.Interest settings) {
        CurrencyInfo info = Economy.info(currency);
        BigDecimal amount = earned(balance, settings.rate(), Economy.parseAmount(settings.max()), info.scaleDigits());
        if (amount.signum() <= 0) return CompletableFuture.completedFuture(BigDecimal.ZERO);
        return claims.claim(claimId(key, slot, player), KEEP_DAYS).thenApply(won -> {
            if (!won) return BigDecimal.ZERO;
            // Refused (the ceiling): the slot stays claimed, nobody retries it.
            return Economy.of(currency).deposit(player, amount, Transaction.of("interest")).isSuccess()
                    ? amount : BigDecimal.ZERO;
        }).exceptionally(failure -> {
            ExyliaEconomy.getInstance().getDebug().error("Economy: could not pay the interest of " + player + ".", failure);
            return BigDecimal.ZERO;
        });
    }

    /**
     * Every balance row of the currency, paid by the one server that claims the slot for it.
     *
     * <p>ponytail: one claim and one queued change per balance per payout, and the base is the row
     * as last written; fine for thousands of balances, batch it if a currency holds far more.
     * Permissions cannot be read for somebody offline, so every balance earns.
     */
    private void payOffline(String currency, String key, long slot, EconomyConfig.Interest settings) {
        claims.claim("interest|" + key + "|" + slot + "|all", KEEP_DAYS).thenAccept(won -> {
            if (won) offlinePage(currency, key, slot, settings, 0, new HashSet<>());
        }).exceptionally(failure -> {
            ExyliaEconomy.getInstance().getDebug().error("Economy: could not pay the offline interest of " + currency + ".", failure);
            return null;
        });
    }

    private void offlinePage(String currency, String key, long slot, EconomyConfig.Interest settings, int from,
                             Set<UUID> seen) {
        Databases.of(plugin).repository(BalanceRow.class).where("currency", key).orderByDescending("amount")
                .skip(from).limit(OFFLINE_PAGE).find().thenAccept(rows -> {
                    CompletableFuture<?> step = CompletableFuture.completedFuture(null);
                    for (BalanceRow row : rows) {
                        // Somebody on this server was already asked about by payOnline, permission and all.
                        if (!seen.add(row.uuid()) || Bukkit.getPlayer(row.uuid()) != null) continue;
                        step = step.thenCompose(ignored -> pay(currency, key, slot, row.uuid(), row.amount(), settings));
                    }
                    if (rows.size() == OFFLINE_PAGE) {
                        step.thenRun(() -> offlinePage(currency, key, slot, settings, from + OFFLINE_PAGE, seen));
                    }
                });
    }

    private void purge() {
        long today = System.currentTimeMillis() / LedgerRow.DAY_MILLIS;
        for (long day = today - PURGE_LOOKBACK_DAYS; day < today; day++) {
            claims.purge(day).exceptionally(failure -> null);
        }
    }
}
