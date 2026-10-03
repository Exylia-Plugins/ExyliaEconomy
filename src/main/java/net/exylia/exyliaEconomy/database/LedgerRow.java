package net.exylia.exyliaEconomy.database;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Index;
import net.exylia.lib.database.Indexed;
import net.exylia.lib.database.Table;
import net.exylia.exyliaEconomy.model.LedgerEntry;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of the ledger: what moved, why, and what the balance read after.
 *
 * <p>{@code currency} is keyed like a balance row: the id, or {@code id@server}
 * for a currency each server keeps its own of.
 *
 * @param createdDay the day it was written, in days since the epoch: what the
 *                   retention sweep deletes by, a day at a time. Zero on lines
 *                   written before the column existed.
 */
@Table("exylia_ledger")
@Index(columns = {"player", "currency", "created_at"}, descending = {"created_at"})
@Index(columns = {"player", "currency", "id"}, descending = {"id"})
public record LedgerRow(
        @Id(generated = true) long id,
        @Indexed @Column(length = 36) String player,
        @Column(length = 100) String currency,
        @Column BigDecimal delta,
        @Column("balance_after") BigDecimal balanceAfter,
        @Column(length = 160) String reason,
        @Column(length = 36) String initiator,
        @Column(length = 64) String server,
        @Column("created_at") long createdAt,
        @Indexed @Column("created_day") long createdDay) {

    public static final long DAY_MILLIS = 86_400_000L;

    public LedgerRow(UUID player, String currency, BigDecimal delta, BigDecimal after,
                     String reason, UUID initiator, String server) {
        this(0L, player.toString(), currency, delta, after, reason,
                initiator == null ? null : initiator.toString(), server, System.currentTimeMillis(),
                System.currentTimeMillis() / DAY_MILLIS);
    }

    /** @param currencyId the currency's id, which the stored key may carry a server on */
    public LedgerEntry entry(String currencyId) {
        return new LedgerEntry(currencyId, UUID.fromString(player), delta, balanceAfter, reason,
                initiator == null ? null : UUID.fromString(initiator), server, createdAt);
    }
}
