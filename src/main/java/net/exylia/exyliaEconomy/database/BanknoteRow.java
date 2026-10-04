package net.exylia.exyliaEconomy.database;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Table;

import java.math.BigDecimal;

/**
 * One banknote /withdraw printed. The row, never the item, says what a note is worth.
 *
 * @param id         the note's id, also stored on the item
 * @param currency   the currency's id
 * @param issuer     who withdrew it
 * @param redeemedAt when it was redeemed; {@code 0} while it is still worth something
 * @param redeemedBy who redeemed it, or blank
 */
@Table("exylia_banknotes")
public record BanknoteRow(
        @Id(length = 36) String id,
        @Column(length = 64) String currency,
        @Column BigDecimal amount,
        @Column(length = 36) String issuer,
        @Column(value = "issuer_name", length = 64) String issuerName,
        @Column("issued_at") long issuedAt,
        @Column("redeemed_at") long redeemedAt,
        @Column(value = "redeemed_by", length = 36) String redeemedBy) {

    public BanknoteRow redeemed(String by, long at) {
        return new BanknoteRow(id, currency, amount, issuer, issuerName, issuedAt, at, by);
    }
}
