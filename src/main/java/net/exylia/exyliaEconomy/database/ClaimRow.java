package net.exylia.exyliaEconomy.database;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Indexed;
import net.exylia.lib.database.Table;

/**
 * Something done at most once across the network: a ledger line rolled back, an interest payout.
 *
 * @param id          what was done, such as {@code rollback|42} or {@code interest|coins|490000|<player>}
 * @param claimedAt   when it was claimed; {@code 0} while nobody holds it
 * @param expiresDay  the day, since the epoch, from which the row may be deleted; {@code 0} keeps it
 */
@Table("exylia_economy_claims")
public record ClaimRow(
        @Id(length = 160) String id,
        @Column("claimed_at") long claimedAt,
        @Indexed @Column("expires_day") long expiresDay) {
}
