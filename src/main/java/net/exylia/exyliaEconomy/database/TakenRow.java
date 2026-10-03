package net.exylia.exyliaEconomy.database;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Indexed;
import net.exylia.lib.database.Table;

/**
 * The token of a {@link PendingRow} a server took, kept for a few hours.
 *
 * <p>Written before the pending row is deleted, so a server whose insert of
 * that row reported failure finds it here and does not queue it again.
 *
 * @param takenHour hours since the epoch, by which old marks are deleted
 */
@Table("exylia_balance_pending_taken")
public record TakenRow(
        @Id(length = 36) String token,
        @Indexed @Column("taken_hour") long takenHour) {

    public static final long HOUR_MILLIS = 3_600_000L;

    public TakenRow(String token) {
        this(token, System.currentTimeMillis() / HOUR_MILLIS);
    }
}
