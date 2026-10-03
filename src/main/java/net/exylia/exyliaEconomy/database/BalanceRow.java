package net.exylia.exyliaEconomy.database;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Index;
import net.exylia.lib.database.Indexed;
import net.exylia.lib.database.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One player's balance in one stored currency: the snapshot.
 *
 * <p>Written only by the server the player is on. Every other server that
 * wants to change it writes a {@link PendingRow} instead, which the owner
 * folds in; that ownership is what keeps two servers from overwriting each
 * other's number.
 *
 * <h2>Handing a balance over</h2>
 * Every write is a compare-and-set on {@code version}, and each one moves it
 * up by one. The server holding the player writes {@code owner} as its own id
 * and blanks it as the last write before it lets go. A server the player joins
 * waits for that blank before it claims the row, and takes it over anyway
 * once a few seconds have passed, which is a server that crashed while holding
 * somebody. Whichever of two servers writes second finds the version moved and
 * loses: the claim reads the row again, and a holder that lost queues what it
 * had not written as pending rows for the new owner.
 *
 * <h2>Which balance a row is</h2>
 * {@code currency} is the currency's id for one shared across the network,
 * and {@code id@server} for one each server keeps its own of. So the two
 * kinds live in the same table and a local currency simply has one row per
 * server, whose owner never changes.
 *
 * @param owner   the server holding the player, blank when nobody does
 * @param version how many times the row has been written since it was versioned
 */
@Table("exylia_balances")
@Index(columns = {"currency", "amount"}, descending = {"amount"})
public record BalanceRow(
        @Id(length = 160) String id,
        @Indexed @Column(length = 36) String player,
        @Column(length = 64) String name,
        @Indexed @Column(length = 100) String currency,
        @Column BigDecimal amount,
        @Column("updated_at") long updatedAt,
        @Column(length = 64) String owner,
        @Column long version) {

    public BalanceRow(UUID player, String name, String currency, BigDecimal amount, String owner, long version) {
        this(id(player, currency), player.toString(), name == null ? "" : name, currency, amount,
                System.currentTimeMillis(), owner == null ? "" : owner, version);
    }

    public static String id(UUID player, String currency) {
        return player + "|" + currency;
    }

    public UUID uuid() {
        return UUID.fromString(player);
    }

    /** Whether no server holds the player, or this one does. */
    public boolean freeFor(String server) {
        return owner == null || owner.isEmpty() || owner.equals(server);
    }
}
