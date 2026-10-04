package net.exylia.exyliaEconomy.manager;

import net.exylia.exyliaEconomy.database.ClaimRow;
import net.exylia.exyliaEconomy.database.LedgerRow;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Repository;
import org.jetbrains.annotations.NotNull;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;

/**
 * Exactly-once across every server on the database, by the same claim imports use.
 *
 * <p>The row is created unclaimed at zero if missing, then stamped by whoever still finds it at
 * zero: one compare-and-set, so of any number of servers asking at once exactly one is told yes.
 * A claim whose work was refused is {@link #release released} for the next try.
 */
public final class Claims {

    private final Repository<ClaimRow> rows;

    public Claims(@NotNull Plugin plugin) {
        this.rows = Databases.of(plugin).repository(ClaimRow.class);
    }

    /**
     * @param keepDays how many days the claim is kept; {@code 0} keeps it for good
     * @return whether this caller won it
     */
    public @NotNull CompletableFuture<Boolean> claim(@NotNull String id, int keepDays) {
        long expires = keepDays <= 0 ? 0L : System.currentTimeMillis() / LedgerRow.DAY_MILLIS + keepDays;
        return rows.increment(new ClaimRow(id, 0L, expires), "claimedAt")
                .thenCompose(created -> rows.updateIf(new ClaimRow(id, System.currentTimeMillis(), expires),
                        "claimedAt", 0L));
    }

    /** Whether somebody holds it. */
    public @NotNull CompletableFuture<Boolean> claimed(@NotNull String id) {
        return rows.find(id).thenApply(found -> found.filter(row -> row.claimedAt() != 0L).isPresent());
    }

    /** Gives a won claim back, because the work it allowed was refused. */
    public @NotNull CompletableFuture<Void> release(@NotNull String id) {
        return rows.delete(id).thenApply(ignored -> null);
    }

    /** Deletes the claims that expired on a day: run for the last few days, it keeps the table small. */
    public @NotNull CompletableFuture<Void> purge(long day) {
        return rows.where("expires_day", day).delete().thenApply(ignored -> null);
    }
}
