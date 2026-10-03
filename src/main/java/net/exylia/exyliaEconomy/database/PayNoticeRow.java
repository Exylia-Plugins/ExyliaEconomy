package net.exylia.exyliaEconomy.database;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Indexed;
import net.exylia.lib.database.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A payment somebody received while they were not on the server that sent it.
 *
 * <p>Only what to tell them: the money itself travels as a {@link PendingRow}. Whichever server
 * they join next takes these rows by deleting them and shows one summary per currency.
 *
 * @param currency the currency's id
 * @param payer    who paid, by id, so two payments from one player count as one payer
 */
@Table("exylia_pay_notices")
public record PayNoticeRow(
        @Id(generated = true) long id,
        @Indexed @Column(length = 36) String player,
        @Column(length = 64) String currency,
        @Column BigDecimal amount,
        @Column(length = 36) String payer,
        @Column(value = "payer_name", length = 64) String payerName,
        @Column("created_at") long createdAt) {

    public PayNoticeRow(UUID player, String currency, BigDecimal amount, UUID payer, String payerName) {
        this(0L, player.toString(), currency, amount, payer.toString(), payerName == null ? "" : payerName,
                System.currentTimeMillis());
    }
}
