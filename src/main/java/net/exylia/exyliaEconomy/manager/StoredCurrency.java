package net.exylia.exyliaEconomy.manager;

import net.exylia.exyliaEconomy.common.Values;
import net.exylia.exyliaEconomy.config.EconomyMessages;
import net.exylia.exyliaEconomy.database.PendingRow;
import net.exylia.exyliaEconomy.database.CurrencyRow;
import net.exylia.lib.economy.CurrencyInfo;
import net.exylia.lib.economy.CurrencyKind;
import net.exylia.lib.economy.CurrencyProvider;
import net.exylia.lib.economy.EconomyResponse;
import net.exylia.lib.economy.Transaction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * A currency this plugin keeps itself.
 *
 * <h2>Who may write a balance</h2>
 * The server the player is on. Their balance is loaded into memory when they
 * join, every operation is applied to that number and written through, and
 * the number is dropped when they leave. A server they are not on never
 * touches the snapshot: it writes a {@link PendingRow} and the owner folds it
 * in. So a balance has one writer at a time, and the ordinary "two servers
 * saved different totals" bug cannot happen.
 *
 * <p>The instance outlives a reload: {@link StoredEconomy} hands it new
 * settings and keeps the balances in memory, because a balance read back from
 * the database while a write is still on its way is the balance before the
 * write.
 *
 * <h2>What an operation on somebody who is not here means</h2>
 * A deposit is queued and applied when they are next loaded anywhere: it
 * always lands. A withdrawal from somebody on another server or offline is
 * queued too, and floored at zero when it lands — the admin who takes money
 * from somebody who logged off. A withdrawal from somebody standing on this
 * server whose balance is still being read is refused: nothing here knows
 * yet whether they can pay.
 *
 * <h2>Threads</h2>
 * Every read-modify-write is under one lock per currency. Operations arrive
 * from the game thread, from database callbacks folding in pending rows and
 * from placeholders, and two of them reading the same balance and both writing
 * it back is how a deposit vanishes. The lock is held for a map lookup and an
 * add; nothing under it touches the database.
 */
public final class StoredCurrency implements CurrencyProvider {

    /**
     * The hard ceiling on any balance, whatever the currency's own max.
     *
     * <p>The column is {@code DECIMAL(38,10)}: 28 whole digits. A balance, a
     * queued change, a sum over every balance for the money supply all have to
     * fit, so nothing is let near that: a deposit that would pass this is
     * refused, never written as a number the database rejects.
     */
    public static final BigDecimal LIMIT = new BigDecimal("1000000000000000000");

    private volatile CurrencyFile.Stored settings;
    private final StoredEconomy economy;

    /** The balances of players loaded here, which are the only ones written. */
    private final Map<UUID, BigDecimal> loaded = new ConcurrentHashMap<>();

    /** The row as last written for each loaded player. Only that player's write chain reads or changes it. */
    private final Map<UUID, Written> written = new ConcurrentHashMap<>();

    /** What the database holds: the version compared on the next write, and the amount at that version. */
    record Written(long version, BigDecimal amount) {
    }

    StoredCurrency(CurrencyFile.Stored settings, StoredEconomy economy) {
        this.settings = settings;
        this.economy = economy;
    }

    public @NotNull CurrencyFile.Stored settings() {
        return settings;
    }

    /** An admin edited the currency: the same balances, new rules. */
    void settings(@NotNull CurrencyFile.Stored changed) {
        settings = changed;
    }

    @Override
    public @NotNull String id() {
        return settings.id();
    }

    @Override
    public @NotNull String displayName() {
        return settings.info().namePlural();
    }

    @Override
    public @NotNull CurrencyInfo info() {
        return settings.info();
    }

    @Override
    public boolean isAvailable() {
        return economy.isReady();
    }

    @Override
    public @NotNull CurrencyKind kind() {
        return CurrencyKind.STORED;
    }

    /**
     * The sort order set in {@code /economyadmin}, so {@code Economy.ordered()} lists the
     * currencies the way the admin arranged them, in this plugin and in every other.
     */
    @Override
    public int order() {
        return economy.currencyStore().get(id()).map(CurrencyRow::sortOrder).orElse(Integer.MAX_VALUE);
    }

    /** The permission its rules name, which {@code Economy.canUse} checks; blank is everybody. */
    @Override
    public @Nullable String permission() {
        String permission = settings.permission();
        return permission == null || permission.isBlank() ? null : permission;
    }

    @Override
    public boolean servesVault() {
        return economy.vault().serving() == this;
    }

    /**
     * A change asked for here may be queued for the server holding the player,
     * so {@link StoredEconomy} announces each one once it is written, wherever
     * that is.
     */
    @Override
    public boolean announcesChanges() {
        return true;
    }

    @Override
    public @NotNull String currencyName(boolean plural) {
        return plural ? settings.info().namePlural() : settings.info().name();
    }

    @Override
    public @NotNull String symbol() {
        return settings.info().symbol();
    }

    // ------------------------------------------------------------- memory

    /** Whether this player's balance is held here, and so may be written. */
    public boolean isLoaded(@NotNull UUID player) {
        return loaded.containsKey(player);
    }

    /**
     * Puts a claimed balance in memory: the player is now this server's.
     *
     * @param here asked under the lock, so a quit cannot slip between the check and the load
     * @return whether it was loaded; {@code false} when the player has already gone
     */
    synchronized boolean load(@NotNull UUID player, @NotNull BigDecimal amount, long version,
                              @NotNull BooleanSupplier here) {
        if (!here.getAsBoolean()) return false;
        loaded.put(player, amount);
        written.put(player, new Written(version, amount));
        return true;
    }

    /**
     * Forgets a player who has left.
     *
     * @return the balance they had, which is what the release writes; {@code null}
     *         when they were not held, and so have no row to let go of
     */
    synchronized @Nullable BigDecimal unload(@NotNull UUID player) {
        return loaded.remove(player);
    }

    /** Another server took the balance over: nothing here may write it any more. */
    synchronized void lost(@NotNull UUID player) {
        loaded.remove(player);
        written.remove(player);
    }

    /** Every player held here right now. */
    Set<UUID> loadedPlayers() {
        return Set.copyOf(loaded.keySet());
    }

    @Nullable Written written(@NotNull UUID player) {
        return written.get(player);
    }

    void wrote(@NotNull UUID player, @NotNull Written row) {
        written.put(player, row);
    }

    @Nullable Written forgetWritten(@NotNull UUID player) {
        return written.remove(player);
    }

    // --------------------------------------------------------- operations

    @Override
    public @NotNull BigDecimal balance(@NotNull UUID player) {
        BigDecimal here = loaded.get(player);
        if (here != null) return here;
        return economy.snapshot(this, player);
    }

    /** {@link #balance}, with somebody not held here read from their row in line: what Vault's callers expect. */
    @NotNull BigDecimal balanceNow(@NotNull UUID player) {
        BigDecimal here = loaded.get(player);
        return here != null ? here : economy.snapshotNow(this, player);
    }

    @Override
    public @NotNull CompletableFuture<BigDecimal> balanceLater(@NotNull UUID player) {
        BigDecimal here = loaded.get(player);
        if (here != null) return CompletableFuture.completedFuture(here);
        return economy.snapshotLater(this, player);
    }

    @Override
    public @NotNull EconomyResponse deposit(@NotNull UUID player, @NotNull BigDecimal amount) {
        return deposit(player, amount, Transaction.NONE);
    }

    /**
     * Adds an amount, all of it or none: one that would take the balance over
     * its ceiling is refused, never cut down to fit. A caller told "paid" has
     * paid in full — a transfer charged the sender the whole amount.
     */
    @Override
    public @NotNull EconomyResponse deposit(@NotNull UUID player, @NotNull BigDecimal amount,
                                            @NotNull Transaction transaction) {
        BigDecimal scaled = info().scale(amount);
        if (scaled.signum() <= 0) return EconomyResponse.invalidAmount();
        synchronized (this) {
            BigDecimal ceiling = ceiling();
            if (scaled.compareTo(ceiling) > 0) return overCeiling(ceiling);
            BigDecimal current = loaded.get(player);
            if (current == null) {
                // Somebody held elsewhere: checked against the last read of
                // their row, which is as far as this server can see. What
                // still slips past is handled where it lands, see applyPending.
                if (economy.snapshot(this, player).add(scaled).compareTo(ceiling) > 0) return overCeiling(ceiling);
                economy.queue(this, player, scaled, false, transaction);
                return EconomyResponse.success(scaled, BigDecimal.ZERO);
            }
            BigDecimal after = current.add(scaled);
            if (after.compareTo(ceiling) > 0) {
                return current.compareTo(ceiling) >= 0
                        ? EconomyResponse.failure(Values.of("amount", info().format(ceiling))
                                .apply(EconomyMessages.get().atCeiling()))
                        : overCeiling(ceiling);
            }
            apply(player, after, scaled, transaction);
            return EconomyResponse.success(scaled, after);
        }
    }

    @Override
    public @NotNull EconomyResponse withdraw(@NotNull UUID player, @NotNull BigDecimal amount) {
        return withdraw(player, amount, Transaction.NONE);
    }

    @Override
    public @NotNull EconomyResponse withdraw(@NotNull UUID player, @NotNull BigDecimal amount,
                                             @NotNull Transaction transaction) {
        BigDecimal scaled = info().scale(amount);
        if (scaled.signum() <= 0) return EconomyResponse.invalidAmount();
        // More than any balance can hold: refused here rather than queued as a
        // row the column cannot store.
        if (scaled.compareTo(LIMIT) > 0) return EconomyResponse.insufficientFunds(scaled, loaded.getOrDefault(player, BigDecimal.ZERO));
        synchronized (this) {
            BigDecimal current = loaded.get(player);
            if (current == null) {
                if (economy.isHere(player)) {
                    return EconomyResponse.failure(StoredEconomy.LOADING);
                }
                economy.queue(this, player, scaled.negate(), false, transaction);
                return EconomyResponse.success(scaled, BigDecimal.ZERO);
            }
            if (current.compareTo(scaled) < 0) {
                return EconomyResponse.insufficientFunds(scaled, current);
            }
            BigDecimal after = current.subtract(scaled);
            apply(player, after, scaled.negate(), transaction);
            return EconomyResponse.success(scaled, after);
        }
    }

    @Override
    public @NotNull EconomyResponse set(@NotNull UUID player, @NotNull BigDecimal amount) {
        return set(player, amount, Transaction.NONE);
    }

    @Override
    public @NotNull EconomyResponse set(@NotNull UUID player, @NotNull BigDecimal amount,
                                        @NotNull Transaction transaction) {
        BigDecimal after = info().scale(amount.max(BigDecimal.ZERO));
        synchronized (this) {
            BigDecimal ceiling = ceiling();
            if (after.compareTo(ceiling) > 0) return overCeiling(ceiling);
            BigDecimal current = loaded.get(player);
            if (current == null) {
                economy.queue(this, player, after, true, transaction);
                return EconomyResponse.success(after, after);
            }
            apply(player, after, after.subtract(current), transaction);
            return EconomyResponse.success(after, after);
        }
    }

    /**
     * Folds a queued change into a loaded balance.
     *
     * <p>Clamped, because it was already promised; whatever a deposit loses
     * to the ceiling is handed to {@link StoredEconomy#overflowed}, never
     * simply dropped.
     *
     * @return whether the player was still held here; {@code false} leaves the change to the caller
     */
    boolean applyPending(@NotNull UUID player, @NotNull PendingRow pending) {
        BigDecimal cut;
        synchronized (this) {
            BigDecimal current = loaded.get(player);
            if (current == null) return false;
            BigDecimal wanted = pending.absolute() ? pending.amount() : current.add(pending.amount());
            BigDecimal after = clamp(wanted);
            BigDecimal moved = after.subtract(current);
            UUID initiator = pending.initiator() == null ? null : UUID.fromString(pending.initiator());
            apply(player, after, moved, new Transaction(pending.reason() == null ? "api" : pending.reason(), initiator));
            // Only what the ceiling took from a deposit: a withdrawal floored at zero takes nobody's money,
            // and a set says what the balance is, not what anybody paid.
            cut = !pending.absolute() && pending.amount().signum() > 0
                    ? info().scale(wanted).subtract(after).max(BigDecimal.ZERO).min(pending.amount()) : BigDecimal.ZERO;
        }
        if (cut.signum() > 0) economy.overflowed(this, player, pending, cut);
        return true;
    }

    /** Writes a new balance to memory, then hands it to the player's write chain. */
    private void apply(UUID player, BigDecimal after, BigDecimal moved, Transaction transaction) {
        loaded.put(player, after);
        economy.written(this, player, after, moved, transaction);
    }

    /**
     * Never below zero, never above the ceiling, never finer than the currency.
     *
     * <p>For what was already promised — a starting balance, a queued change
     * landing — which can only be cut, not refused.
     */
    BigDecimal clamp(BigDecimal amount) {
        return info().scale(amount.max(BigDecimal.ZERO)).min(ceiling());
    }

    /** The most a balance may hold: the currency's own max, and never more than {@link #LIMIT}. */
    BigDecimal ceiling() {
        BigDecimal limit = info().scale(LIMIT);
        return settings.isCapped() ? info().scale(settings.max()).min(limit) : limit;
    }

    private EconomyResponse overCeiling(BigDecimal ceiling) {
        return EconomyResponse.failure(Values.of("amount", info().format(ceiling))
                .apply(EconomyMessages.get().overCeiling()));
    }
}
