package net.exylia.exyliaEconomy.database;

import net.exylia.exyliaEconomy.manager.CurrencyFile;
import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Table;
import net.exylia.lib.economy.CurrencyInfo;

import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One currency, as an administrator set it up in game.
 *
 * <p>Every kind shares the row, and a column a kind has no use for is simply
 * ignored: an overlay has no balance ceiling, a stored currency no item. The
 * id is the key balances are stored under, so it is never changed once the
 * row exists.
 *
 * <p>Payments and interest: {@code payConfirmAbove} is the amount above which
 * {@code /pay} asks first ({@code 0} never); {@code banknotes} lets
 * {@code /withdraw} print it; {@code interestRate} is the percent paid every
 * {@code interestInterval} seconds ({@code 0} pays nothing), never more than
 * {@code interestMax} ({@code 0} no cap), and {@code interestOffline} also pays
 * the players not online. A column added after a row was written reads as
 * zero or false, which is every one of these turned off.
 */
@Table("exylia_currencies")
public record CurrencyRow(
        @Id(length = 32) String id,
        @Column(length = 16) Kind kind,
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
        @Column BigDecimal payConfirmAbove,
        @Column boolean banknotes,
        @Column double interestRate,
        @Column long interestInterval,
        @Column BigDecimal interestMax,
        @Column boolean interestOffline,
        @Column("created_at") long createdAt,
        @Column("updated_at") long updatedAt) {

    /** What a row describes. */
    public enum Kind {
        /** Kept in this plugin's own table. */
        STORED,
        /** An exact item in the player's inventory. */
        ITEM,
        /** How a currency another plugin provides looks. */
        DISPLAY
    }

    /** Seconds between interest payouts until an admin picks otherwise. */
    public static final long DEFAULT_INTEREST_INTERVAL = 3600;

    /** A new currency of a kind, with the settings a fresh one starts from. */
    public static CurrencyRow blank(String id, Kind kind, int sortOrder) {
        long now = System.currentTimeMillis();
        return new CurrencyRow(id, kind, sortOrder, "", "", "", kind == Kind.ITEM ? "" : "GOLD_INGOT",
                kind == Kind.DISPLAY ? -1 : 0, "%amount% %name%", "",
                kind == Kind.DISPLAY ? List.of() : List.of(id), "", BigDecimal.ZERO, BigDecimal.valueOf(-1), "",
                true, BigDecimal.ONE, 0, false, "", true, true, true,
                BigDecimal.ZERO, false, 0, DEFAULT_INTEREST_INTERVAL, BigDecimal.ZERO, false, now, now);
    }

    /** A stored currency read from the old file. */
    public static CurrencyRow of(CurrencyFile.Stored stored, int sortOrder) {
        long now = System.currentTimeMillis();
        CurrencyInfo info = stored.info();
        return new CurrencyRow(stored.id(), Kind.STORED, sortOrder, info.name(), info.namePlural(), info.symbol(),
                info.icon(), info.decimals(), info.format(), info.compactFormat(), stored.aliases(), "",
                stored.start(), stored.max(), stored.permission(), stored.transferable(), stored.minimumTransfer(),
                stored.transferTaxPercent(), stored.exchangeable(), encodeRates(stored.rates()),
                stored.leaderboard(), stored.networked(), stored.commands(),
                BigDecimal.ZERO, false, 0, DEFAULT_INTEREST_INTERVAL, BigDecimal.ZERO, false, now, now);
    }

    /** An item currency read from the old file. */
    public static CurrencyRow of(CurrencyFile.Item item, int sortOrder) {
        return of(Kind.ITEM, item.info(), sortOrder).edit(row -> {
            row.item = item.item();
            row.aliases = item.aliases();
        });
    }

    /** A display overlay read from the old file. */
    public static CurrencyRow of(CurrencyInfo overlay, int sortOrder) {
        return of(Kind.DISPLAY, overlay, sortOrder).edit(row -> row.aliases = List.of());
    }

    private static CurrencyRow of(Kind kind, CurrencyInfo info, int sortOrder) {
        return blank(info.id(), kind, sortOrder).edit(row -> {
            row.name = info.name();
            row.plural = info.namePlural();
            row.symbol = info.symbol();
            row.icon = info.icon();
            row.decimals = info.decimals();
            row.format = info.format();
            row.compactFormat = info.compactFormat();
        });
    }

    /** The amount above which a payment asks first, or {@code null} when it never does. */
    public @Nullable BigDecimal confirmAbove() {
        return payConfirmAbove == null || payConfirmAbove.signum() <= 0 ? null : payConfirmAbove;
    }

    /** How often interest is paid; an unset interval reads as the default hour. */
    public Duration interestEvery() {
        return Duration.ofSeconds(interestInterval > 0 ? interestInterval : DEFAULT_INTEREST_INTERVAL);
    }

    /** How the currency looks. */
    public CurrencyInfo info() {
        return new CurrencyInfo(id, name, plural, symbol, icon, decimals, format, compactFormat);
    }

    /** The row as the stored currency the runtime registers. */
    public CurrencyFile.Stored stored() {
        return new CurrencyFile.Stored(id, info(), aliases == null ? List.of() : List.copyOf(aliases), start, max,
                permission == null ? "" : permission, transferable, minimumTransfer, Math.max(0, transferTaxPercent),
                exchangeable, decodeRates(rates), leaderboard, networked, commands);
    }

    /** The row as the item currency the runtime registers. */
    public CurrencyFile.Item itemCurrency() {
        return new CurrencyFile.Item(id, info(), item == null ? "" : item.trim(),
                aliases == null ? List.of() : List.copyOf(aliases));
    }

    /** The same row with some fields changed, stamped as updated. */
    public CurrencyRow edit(Consumer<Draft> change) {
        Draft draft = new Draft(this);
        change.accept(draft);
        return draft.build();
    }

    /**
     * Exchange rates as one column: {@code shards=0.01;gems=2}.
     *
     * <p>Text rather than a map column, because it is also what an admin
     * reads and types back.
     */
    public static String encodeRates(Map<String, BigDecimal> rates) {
        List<String> parts = new ArrayList<>();
        rates.forEach((other, rate) -> parts.add(other + "=" + rate.stripTrailingZeros().toPlainString()));
        return String.join(";", parts);
    }

    /**
     * Reads rates back, from the column or from what an admin typed.
     *
     * <p>Commas and semicolons both separate, because one is what the column
     * holds and the other is what people type.
     *
     * @throws IllegalArgumentException when a part is not {@code id=positive number}
     */
    public static Map<String, BigDecimal> decodeRates(String text) {
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        if (text == null || text.isBlank()) return rates;
        for (String part : text.split("[;,]")) {
            if (part.isBlank()) continue;
            String[] pair = part.split("=", 2);
            if (pair.length != 2 || pair[0].isBlank()) throw new IllegalArgumentException("not id=rate: " + part.trim());
            BigDecimal rate = new BigDecimal(pair[1].trim());
            if (rate.signum() <= 0) throw new IllegalArgumentException("not a positive rate: " + part.trim());
            rates.put(pair[0].trim().toLowerCase(Locale.ROOT), rate);
        }
        return rates;
    }

    /** The mutable copy {@link #edit} hands out. */
    public static final class Draft {
        public String name, plural, symbol, icon, format, compactFormat, item, permission, rates;
        public int sortOrder, decimals;
        public List<String> aliases;
        public BigDecimal start, max, minimumTransfer, payConfirmAbove, interestMax;
        public double transferTaxPercent, interestRate;
        public long interestInterval;
        public boolean transferable, exchangeable, leaderboard, networked, commands, banknotes, interestOffline;
        private final CurrencyRow source;

        private Draft(CurrencyRow row) {
            source = row;
            name = row.name; plural = row.plural; symbol = row.symbol; icon = row.icon;
            format = row.format; compactFormat = row.compactFormat; item = row.item;
            permission = row.permission; rates = row.rates; sortOrder = row.sortOrder;
            decimals = row.decimals; aliases = row.aliases; start = row.start; max = row.max;
            minimumTransfer = row.minimumTransfer; transferTaxPercent = row.transferTaxPercent;
            transferable = row.transferable; exchangeable = row.exchangeable;
            leaderboard = row.leaderboard; networked = row.networked; commands = row.commands;
            payConfirmAbove = row.payConfirmAbove; banknotes = row.banknotes; interestRate = row.interestRate;
            interestInterval = row.interestInterval; interestMax = row.interestMax;
            interestOffline = row.interestOffline;
        }

        private CurrencyRow build() {
            return new CurrencyRow(source.id, source.kind, sortOrder, name, plural, symbol, icon, decimals, format,
                    compactFormat, aliases, item, start, max, permission, transferable, minimumTransfer,
                    transferTaxPercent, exchangeable, rates, leaderboard, networked, commands, payConfirmAbove,
                    banknotes, interestRate, interestInterval, interestMax, interestOffline, source.createdAt,
                    System.currentTimeMillis());
        }
    }
}
