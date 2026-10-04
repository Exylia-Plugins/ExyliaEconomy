package net.exylia.exyliaEconomy.migration;

import net.exylia.exyliaEconomy.database.EconomySettingsRow;
import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Table;

/**
 * The economy settings as ExyliaSurvivalCore kept them, in {@code sc_economy_settings}: the same
 * columns as {@link EconomySettingsRow}, under the old table name. Only read, by {@link LegacyTables}.
 */
@Table("sc_economy_settings")
public record LegacyEconomySettingsRow(
        @Id(length = 16) String id,
        @Column boolean experienceLevels,
        @Column boolean experiencePoints,
        @Column(length = 32) String vaultProvide,
        @Column boolean vaultForce,
        @Column boolean ledger,
        @Column("updated_at") long updatedAt,
        @Column int ledgerDays,
        @Column(length = Column.UNBOUNDED) String imports) {

    /** The row under its new table, column for column. */
    public EconomySettingsRow toRow() {
        return new EconomySettingsRow(id, experienceLevels, experiencePoints, vaultProvide, vaultForce, ledger,
                updatedAt, ledgerDays, imports, null, false, true);
    }
}
