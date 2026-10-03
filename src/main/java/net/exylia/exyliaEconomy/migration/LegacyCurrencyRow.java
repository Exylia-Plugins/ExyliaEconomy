package net.exylia.exyliaEconomy.migration;

import net.exylia.exyliaEconomy.database.CurrencyRow;
import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Table;

import java.math.BigDecimal;
import java.util.List;

/**
 * A currency as ExyliaSurvivalCore kept it, in {@code sc_currencies}: the same columns as
 * {@link CurrencyRow}, under the old table name. Only read, by {@link LegacyTables}.
 */
@Table("sc_currencies")
public record LegacyCurrencyRow(
        @Id(length = 32) String id,
        @Column(length = 16) CurrencyRow.Kind kind,
        @Column int sortOrder,
        @Column(length = 512) String name,
        @Column(length = 512) String plural,
        @Column(length = 64) String symbol,
        @Column(length = Column.UNBOUNDED) String icon,
        @Column int decimals,
        @Column(length = 512) String format,
        @Column(length = 512) String compactFormat,
        @Column(length = Column.UNBOUNDED) List<String> aliases,
        @Column(length = Column.UNBOUNDED) String item,
        @Column BigDecimal start,
        @Column BigDecimal max,
        @Column(length = 128) String permission,
        @Column boolean transferable,
        @Column BigDecimal minimumTransfer,
        @Column double transferTaxPercent,
        @Column boolean exchangeable,
        @Column(length = Column.UNBOUNDED) String rates,
        @Column boolean leaderboard,
        @Column boolean networked,
        @Column boolean commands,
        @Column("created_at") long createdAt,
        @Column("updated_at") long updatedAt) {

    /** The row under its new table, column for column. */
    public CurrencyRow toRow() {
        return new CurrencyRow(id, kind, sortOrder, name, plural, symbol, icon, decimals, format, compactFormat,
                aliases, item, start, max, permission, transferable, minimumTransfer, transferTaxPercent,
                exchangeable, rates, leaderboard, networked, commands, createdAt, updatedAt);
    }
}
