package net.exylia.exyliaEconomy.service;

import net.exylia.exyliaEconomy.ExyliaEconomy;
import net.exylia.exyliaEconomy.common.Messages;
import net.exylia.exyliaEconomy.common.Values;
import net.exylia.exyliaEconomy.config.EconomyMessages;
import net.exylia.exyliaEconomy.database.LedgerRow;
import net.exylia.exyliaEconomy.manager.Claims;
import net.exylia.exyliaEconomy.manager.StoredEconomy;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Repository;
import net.exylia.lib.economy.CurrencyInfo;
import net.exylia.lib.economy.Economy;
import net.exylia.lib.economy.EconomyResponse;
import net.exylia.lib.economy.Transaction;
import net.exylia.lib.format.Formats;
import net.exylia.lib.input.InputParser;
import net.exylia.lib.player.ExyliaPlayer;
import net.exylia.lib.player.ExyliaPlayers;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The admin side of the ledger: reading a player's lines with their ids, exporting it to CSV and
 * rolling movements back.
 *
 * <p>A rollback is one reversal per ledger line, through the currency like any admin change, with
 * reason {@code rollback}. Each line is claimed first ({@code rollback|<id>}, kept for good), so
 * no line is ever reverted twice, whoever asks and on whichever server; a reversal the currency
 * refuses gives its claim back.
 */
public final class LedgerTools {

    /** Reason of every reversal, and of the lines a rollback never reverts. */
    public static final String ROLLBACK = "rollback";
    static final String CSV_HEADER = "id,time,player,currency,delta,balance_after,reason,initiator,server";

    private static final int LOG_PAGE = 10;
    /** The most lines one rollback looks at, newest first. */
    // ponytail: a window holding more movements than this reverts the newest ones only; page further if that matters.
    private static final int ROLLBACK_DEPTH = 500;
    private static final int EXPORT_PAGE = 1_000;
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** A rollback waiting for its confirmation, per sender: exactly the lines that were shown. */
    private static final Map<String, List<LedgerRow>> PENDING = new ConcurrentHashMap<>();

    private static EconomyMessages text() {
        return EconomyMessages.get();
    }

    private static Plugin plugin() {
        return ExyliaEconomy.getInstance().getPlugin();
    }

    private static Repository<LedgerRow> ledger() {
        return Databases.of(plugin()).repository(LedgerRow.class);
    }

    private static void sync(Runnable work) {
        ExyliaEconomy.getInstance().getTasks().run(work);
    }

    private static void failed(CommandSender sender, String what, Throwable failure) {
        ExyliaEconomy.getInstance().getDebug().error("Economy: could not " + what + ".", failure);
        sync(() -> Messages.send(sender, text().ledgerFailed()));
    }

    /** A stored currency's key, told to the sender when the currency keeps no ledger. */
    private static @Nullable String key(CommandSender sender, String currency) {
        String key = StoredEconomy.storageKey(currency);
        if (key == null) {
            Messages.send(sender, text().notAvailable(), Values.of().put("currency", Economy.info(currency).namePlural()));
        }
        return key;
    }

    // ----------------------------------------------------------------- log

    /** A player's ledger lines, newest first, ten a page, with the ids a rollback can name. */
    public void log(CommandSender sender, String targetName, @Nullable String currencyId, int page) {
        String currency = EconomyActions.resolved(sender, currencyId);
        if (currency == null) return;
        String key = key(sender, currency);
        if (key == null) return;
        CurrencyInfo info = Economy.info(currency);
        int shown = Math.max(1, page);
        ExyliaPlayers.then(sender, targetName, found -> ledger().where("player", found.id().toString())
                .where("currency", key).orderByDescending("id").skip((shown - 1) * LOG_PAGE).limit(LOG_PAGE).find()
                .whenComplete((rows, failure) -> {
                    if (failure != null) {
                        failed(sender, "read the ledger of " + found.name(), failure);
                        return;
                    }
                    sync(() -> {
                        Messages.send(sender, text().logHeader(), Values.of().put("currency", info.namePlural())
                                .put("player", found.name()).put("page", shown));
                        if (rows.isEmpty()) Messages.send(sender, text().historyEmpty());
                        for (LedgerRow row : rows) {
                            Messages.send(sender, text().logLine(), Values.of().put("id", row.id())
                                    .put("date", Formats.relative(row.createdAt())).put("delta", signed(info, row.delta()))
                                    .put("reason", EconomyActions.reasonLabel(row.reason()))
                                    .put("balance", info.format(row.balanceAfter())));
                        }
                    });
                }));
    }

    private static String signed(CurrencyInfo info, BigDecimal delta) {
        return (delta.signum() >= 0 ? "{success}+" : "{error}-") + info.format(delta.abs());
    }

    // -------------------------------------------------------------- export

    /**
     * Writes ledger lines to {@code exports/} as CSV, off the game thread.
     *
     * @param scope a currency id, or {@code all}
     * @param days  only the last this many days, by the day each line was written; {@code 0} is everything
     */
    public void export(CommandSender sender, String scope, int days) {
        boolean all = "all".equalsIgnoreCase(scope);
        String key = null;
        if (!all) {
            String currency = EconomyActions.currency(scope).orElse(null);
            if (currency == null) {
                Messages.send(sender, text().noCurrency(), Values.of().put("currency", scope));
                return;
            }
            key = key(sender, currency);
            if (key == null) return;
        }
        String only = key;
        Predicate<LedgerRow> wanted = row -> only == null || only.equals(row.currency());
        String name = "ledger-" + (all ? "all" : scope.toLowerCase(java.util.Locale.ROOT)) + "-"
                + LocalDateTime.now().format(FILE_STAMP) + ".csv";
        Path file = plugin().getDataFolder().toPath().resolve("exports").resolve(name);
        Messages.send(sender, text().exportStarted());
        CompletableFuture<List<LedgerRow>> read = days > 0 ? readDays(days) : readAll(0, new ArrayList<>());
        read.thenApply(rows -> {
            List<LedgerRow> kept = rows.stream().filter(wanted).toList();
            try {
                write(file, kept);
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
            return kept.size();
        }).whenComplete((count, failure) -> {
            if (failure != null) {
                failed(sender, "export the ledger to " + file, failure);
                return;
            }
            sync(() -> Messages.send(sender, text().exportDone(), Values.of().put("count", count).put("file", name)));
        });
    }

    // ponytail: reads the whole export into memory before writing; stream page by page if exports reach millions of lines.
    private static CompletableFuture<List<LedgerRow>> readAll(int from, List<LedgerRow> into) {
        return ledger().all().orderBy("id").skip(from).limit(EXPORT_PAGE).find().thenCompose(page -> {
            into.addAll(page);
            return page.size() < EXPORT_PAGE ? CompletableFuture.completedFuture(into)
                    : readAll(from + EXPORT_PAGE, into);
        });
    }

    private static CompletableFuture<List<LedgerRow>> readDays(int days) {
        long today = System.currentTimeMillis() / LedgerRow.DAY_MILLIS;
        CompletableFuture<List<LedgerRow>> step = CompletableFuture.completedFuture(new ArrayList<>());
        for (long day = today - days + 1; day <= today; day++) {
            long at = day;
            step = step.thenCompose(rows -> ledger().where("created_day", at).orderBy("id").find()
                    .thenApply(found -> {
                        rows.addAll(found);
                        return rows;
                    }));
        }
        return step;
    }

    static void write(Path file, List<LedgerRow> rows) throws IOException {
        Files.createDirectories(file.getParent());
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write(CSV_HEADER);
            out.newLine();
            for (LedgerRow row : rows) {
                out.write(csv(row, ZoneId.systemDefault()));
                out.newLine();
            }
        }
    }

    /** One ledger line as a CSV record: RFC 4180 quoting, ISO time in the given zone. */
    static String csv(LedgerRow row, ZoneId zone) {
        return String.join(",", String.valueOf(row.id()),
                Instant.ofEpochMilli(row.createdAt()).atZone(zone).toOffsetDateTime().toString(),
                cell(row.player()), cell(row.currency()), row.delta().toPlainString(),
                row.balanceAfter().toPlainString(), cell(row.reason()), cell(row.initiator()), cell(row.server()));
    }

    private static String cell(@Nullable String value) {
        if (value == null) return "";
        // A leading formula character is neutralised: an export opened in a spreadsheet runs nothing.
        String safe = !value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        boolean quote = safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r");
        return quote ? "\"" + safe.replace("\"", "\"\"") + "\"" : safe;
    }

    // ------------------------------------------------------------ rollback

    /**
     * Reverts a player's movements in one currency, asking first.
     *
     * @param window a duration back from now ({@code 1h}, {@code 2d}), or one line by id ({@code #42})
     */
    public void rollback(CommandSender sender, String targetName, String window, @Nullable String currencyId,
                         boolean confirmed) {
        String currency = EconomyActions.resolved(sender, currencyId);
        if (currency == null) return;
        Long entry = window.startsWith("#") ? parseId(window.substring(1)) : null;
        Duration span = entry == null ? InputParser.duration().parse(window.trim()).value() : null;
        if (entry == null && (span == null || span.isZero() || span.isNegative())) {
            Messages.send(sender, text().invalidWindow());
            return;
        }
        String key = key(sender, currency);
        if (key == null) return;
        UUID initiator = EconomyActions.initiator(sender);
        String who = initiator == null ? "console" : initiator.toString();
        ExyliaPlayers.then(sender, targetName, found -> {
            String what = "rollback|" + found.id() + "|" + currency + "|" + window.toLowerCase(java.util.Locale.ROOT);
            if (confirmed) {
                List<LedgerRow> rows = PENDING.remove(who);
                if (rows == null || !Confirmations.confirm(who, what, System.currentTimeMillis())) {
                    Messages.send(sender, text().confirmExpired());
                    return;
                }
                apply(sender, found, currency, rows, initiator);
                return;
            }
            candidates(found.id(), key, entry, span).thenCompose(LedgerTools::unclaimed).whenComplete((rows, failure) -> {
                if (failure != null) {
                    failed(sender, "read the ledger of " + found.name(), failure);
                    return;
                }
                sync(() -> {
                    CurrencyInfo info = Economy.info(currency);
                    if (rows.isEmpty()) {
                        Messages.send(sender, text().rollbackNothing(), Values.of().put("player", found.name()));
                        return;
                    }
                    PENDING.put(who, rows);
                    Confirmations.ask(who, what, System.currentTimeMillis());
                    Messages.send(sender, text().rollbackConfirm(), Values.of().put("player", found.name())
                            .put("count", rows.size()).put("net", signed(info, reversal(rows)))
                            .put("command", "/economyadmin rollback " + found.name() + " " + window + " " + currency + " confirm"));
                });
            });
        });
    }

    private static @Nullable Long parseId(String typed) {
        try {
            long id = Long.parseLong(typed.trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** The lines a rollback would revert: the player's, in the currency, never a rollback itself. */
    private static CompletableFuture<List<LedgerRow>> candidates(UUID player, String key, @Nullable Long entry,
                                                                @Nullable Duration span) {
        Predicate<LedgerRow> theirs = row -> row.player().equals(player.toString()) && row.currency().equals(key)
                && !ROLLBACK.equals(row.reason());
        if (entry != null) {
            return ledger().find(entry).thenApply(found -> found.filter(theirs).map(List::of).orElse(List.of()));
        }
        long since = System.currentTimeMillis() - span.toMillis();
        return ledger().where("player", player.toString()).where("currency", key).orderByDescending("id")
                .limit(ROLLBACK_DEPTH).find()
                .thenApply(rows -> rows.stream().filter(theirs).filter(row -> row.createdAt() >= since).toList());
    }

    /** Leaves out what an earlier rollback already reverted. */
    private static CompletableFuture<List<LedgerRow>> unclaimed(List<LedgerRow> rows) {
        Claims claims = new Claims(plugin());
        List<CompletableFuture<Boolean>> done = rows.stream().map(row -> claims.claimed(claimId(row))).toList();
        return CompletableFuture.allOf(done.toArray(new CompletableFuture[0])).thenApply(ignored -> {
            List<LedgerRow> open = new ArrayList<>();
            for (int index = 0; index < rows.size(); index++) if (!done.get(index).join()) open.add(rows.get(index));
            return open;
        });
    }

    /** What reverting these lines moves: the opposite of their sum. */
    static BigDecimal reversal(List<LedgerRow> rows) {
        return rows.stream().map(LedgerRow::delta).reduce(BigDecimal.ZERO, BigDecimal::add).negate();
    }

    static String claimId(LedgerRow row) {
        return "rollback|" + row.id();
    }

    private static void apply(CommandSender sender, ExyliaPlayer found, String currency, List<LedgerRow> rows,
                              @Nullable UUID initiator) {
        Transaction transaction = Transaction.of(ROLLBACK).by(initiator);
        Economy.CurrencyView view = Economy.of(currency);
        revert(rows, new Claims(plugin()), row -> {
            BigDecimal amount = row.delta().abs();
            EconomyResponse done = row.delta().signum() > 0 ? view.withdraw(found.id(), amount, transaction)
                    : view.deposit(found.id(), amount, transaction);
            return done.isSuccess();
        }).whenComplete((result, failure) -> {
            if (failure != null) {
                failed(sender, "roll back " + found.name(), failure);
                return;
            }
            CurrencyInfo info = Economy.info(currency);
            sync(() -> Messages.send(sender, text().rollbackDone(), Values.of().put("player", found.name())
                    .put("count", result.reverted().size()).put("net", signed(info, reversal(result.reverted())))
                    .put("failed", result.failed()).put("skipped", result.skipped())));
        });
    }

    /**
     * @param reverted the lines reverted by this call
     * @param skipped  lines another rollback had already claimed
     * @param failed   lines whose reversal the currency refused, left as they were
     */
    record Result(List<LedgerRow> reverted, int skipped, int failed) { }

    /**
     * Reverts each line once: claimed, then reversed, the claim given back when the reversal is refused.
     * One after the other, so the balance moves in a predictable order.
     *
     * @param reverse applies one line's reversal, answering whether the currency took it
     */
    static CompletableFuture<Result> revert(List<LedgerRow> rows, Claims claims, Predicate<LedgerRow> reverse) {
        List<LedgerRow> reverted = new ArrayList<>();
        int[] skipped = {0};
        int[] failed = {0};
        CompletableFuture<Void> step = CompletableFuture.completedFuture(null);
        for (LedgerRow row : rows) {
            step = step.thenCompose(ignored -> claims.claim(claimId(row), 0)).thenCompose(won -> {
                if (!won) {
                    skipped[0]++;
                    return CompletableFuture.completedFuture(null);
                }
                if (reverse.test(row)) {
                    reverted.add(row);
                    return CompletableFuture.completedFuture(null);
                }
                failed[0]++;
                return claims.release(claimId(row));
            });
        }
        return step.thenApply(ignored -> new Result(List.copyOf(reverted), skipped[0], failed[0]));
    }
}
