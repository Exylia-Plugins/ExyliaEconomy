package net.exylia.exyliaEconomy.service;

import net.exylia.exyliaEconomy.config.EconomyMessages;
import net.exylia.exyliaEconomy.ExyliaEconomy;
import net.exylia.exyliaEconomy.common.Messages;
import net.exylia.exyliaEconomy.common.Values;
import net.exylia.exyliaEconomy.manager.CurrencyStore;
import net.exylia.exyliaEconomy.manager.StoredEconomy;
import net.exylia.exyliaEconomy.menu.HistoryMenu;
import net.exylia.exyliaEconomy.menu.TopMenu;
import net.exylia.exyliaEconomy.menu.WalletMenu;
import net.exylia.exyliaEconomy.model.CurrencyRules;
import net.exylia.exyliaEconomy.model.LedgerEntry;
import net.exylia.exyliaEconomy.Permissions;
import net.exylia.lib.economy.CurrencyInfo;
import net.exylia.lib.economy.CurrencyKind;
import net.exylia.lib.economy.Economy;
import net.exylia.lib.economy.EconomyResponse;
import net.exylia.lib.economy.Transaction;
import net.exylia.lib.economy.TransferResult;
import net.exylia.lib.format.Formats;
import net.exylia.lib.player.ExyliaPlayer;
import net.exylia.lib.player.ExyliaPlayers;
import net.exylia.lib.util.Cooldowns;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * What the economy commands do, whichever spelling reached them.
 *
 * <p>{@code /economy pay} and {@code /coins pay} both end here, so the rules
 * — who may use a currency, the least that may be sent, the tax — are applied
 * once. {@link StoredEconomy} keeps the balances and the rules; this is the part that
 * talks to players.
 */
public final class EconomyActions {

    private static final Duration PAY_COOLDOWN = Duration.ofSeconds(1);
    /** The imports running now, {@code from>into}. */
    private static final Set<String> IMPORTING = ConcurrentHashMap.newKeySet();

    private static EconomyMessages text() {
        return EconomyMessages.get();
    }

    /** The currency an argument names, or the default when it names none. */
    public static Optional<String> currency(@Nullable String id) {
        if (id == null || id.isBlank()) return Optional.of(Economy.defaultId());
        String clean = Economy.canonical(id.trim().toLowerCase(Locale.ROOT));
        return Economy.currencies().contains(clean) ? Optional.of(clean) : Optional.empty();
    }

    // ----------------------------------------------------------- questions

    public void balance(CommandSender sender, @Nullable String currencyId, @Nullable String targetName) {
        String currency = resolved(sender, currencyId);
        if (currency == null) return;
        CurrencyInfo info = Economy.info(currency);
        if (targetName == null || targetName.isBlank()) {
            if (!(sender instanceof Player self)) {
                Messages.send(sender, text().usage(), Values.of().put("command", "economy"));
                return;
            }
            Messages.send(sender, text().balance(), Values.of().put("currency", info.namePlural())
                    .put("amount", info.format(Economy.of(currency).balance(self.getUniqueId()))));
            return;
        }
        if (!sender.hasPermission(Permissions.OTHERS)) {
            Messages.send(sender, EconomyMessages.get().permissionDenied());
            return;
        }
        ExyliaPlayers.then(sender, targetName, found -> later(Economy.of(currency).balanceLater(found.id()),
                amount -> Messages.send(sender, text().balanceOther(),
                        Values.of().put("player", found.name()).put("currency", info.namePlural())
                                .put("amount", info.format(amount)))));
    }

    /**
     * Somebody's every balance.
     *
     * <p>A player gets the screen, because six currencies are six lines of
     * scrollback and nothing to click; the console gets the lines, because
     * there is nothing to open there.
     */
    public void wallet(CommandSender sender, @Nullable String targetName) {
        Consumer<ExyliaPlayer> show = found -> {
            if (sender instanceof Player viewer) {
                WalletMenu.open(viewer, found);
                return;
            }
            List<String> ids = new ArrayList<>();
            List<CompletableFuture<BigDecimal>> reads = new ArrayList<>();
            for (String currency : Economy.ordered()) {
                if (!Economy.canUse(sender, currency)) continue;
                ids.add(currency);
                reads.add(WalletMenu.read(currency, found));
            }
            // Every balance first, then the whole wallet at once: a wallet
            // drawn line by line as reads land is a wallet in no order.
            CompletableFuture.allOf(reads.toArray(new CompletableFuture[0])).thenRun(() ->
                    ExyliaEconomy.getInstance().getTasks().run(() -> {
                        Messages.send(sender, text().walletHeader(), Values.of().put("player", found.name()));
                        for (int line = 0; line < ids.size(); line++) {
                            BigDecimal amount = reads.get(line).join();
                            if (amount == null) continue;
                            CurrencyInfo info = Economy.info(ids.get(line));
                            Messages.send(sender, text().walletLine(), Values.of()
                                    .put("currency", info.namePlural())
                                    .put("amount", info.format(amount)));
                        }
                    }));
        };
        if (targetName == null || targetName.isBlank()) {
            if (sender instanceof Player self) show.accept(ExyliaPlayers.of(self));
            return;
        }
        if (!sender.hasPermission(Permissions.OTHERS)) {
            Messages.send(sender, EconomyMessages.get().permissionDenied());
            return;
        }
        ExyliaPlayers.then(sender, targetName, show);
    }

    public void currencies(CommandSender sender) {
        Messages.send(sender, text().currenciesHeader());
        for (String currency : Economy.ordered()) {
            CurrencyInfo info = Economy.info(currency);
            Messages.send(sender, text().currenciesLine(), Values.of().put("id", currency)
                    .put("currency", info.namePlural())
                    .put("kind", kindLabel(currency)));
        }
    }

    public void top(CommandSender sender, @Nullable String currencyId, int page) {
        String currency = resolved(sender, currencyId);
        if (currency == null) return;
        if (sender instanceof Player viewer) {
            TopMenu.open(viewer, currency);
            return;
        }
        CurrencyInfo info = Economy.info(currency);
        int size = 10;
        int from = Math.max(0, page - 1) * size;
        List<StoredEconomy.TopEntry> entries = StoredEconomy.top(currency, from + size);
        Messages.send(sender, text().topHeader(), Values.of().put("currency", info.namePlural()).put("page", page));
        if (entries.size() <= from) {
            Messages.send(sender, text().topEmpty());
            return;
        }
        for (StoredEconomy.TopEntry entry : entries.subList(from, entries.size())) {
            Messages.send(sender, text().topLine(), Values.of().put("position", entry.position())
                    .put("player", entry.name()).put("amount", info.format(entry.amount())));
        }
    }

    public void history(CommandSender sender, @Nullable String currencyId, @Nullable String targetName) {
        String currency = resolved(sender, currencyId);
        if (currency == null) return;
        CurrencyInfo info = Economy.info(currency);
        Consumer<ExyliaPlayer> show = found -> {
            if (sender instanceof Player viewer) {
                HistoryMenu.open(viewer, currency, found);
                return;
            }
            StoredEconomy.history(currency, found.id(), 15)
                .thenAccept(lines -> ExyliaEconomy.getInstance().getTasks().run(() -> {
                    Messages.send(sender, text().historyHeader(),
                            Values.of().put("currency", info.namePlural()).put("player", found.name()));
                    if (lines.isEmpty()) {
                        Messages.send(sender, text().historyEmpty());
                        return;
                    }
                    for (LedgerEntry line : lines) {
                        String delta = (line.isDeposit() ? "{success}+" : "{error}-") + info.format(line.delta().abs());
                        Messages.send(sender, text().historyLine(), Values.of()
                                .put("date", Formats.relative(line.at())).put("delta", delta)
                                .put("reason", reasonLabel(line.reason())).put("balance", info.format(line.balanceAfter())));
                    }
                }));
        };
        if (targetName == null || targetName.isBlank()) {
            if (sender instanceof Player self) show.accept(ExyliaPlayers.of(self));
            return;
        }
        if (!sender.hasPermission(Permissions.OTHERS)) {
            Messages.send(sender, EconomyMessages.get().permissionDenied());
            return;
        }
        ExyliaPlayers.then(sender, targetName, show);
    }

    // ------------------------------------------------------------- players

    public void pay(Player sender, @Nullable String currencyId, String targetName, String typedAmount) {
        String currency = resolved(sender, currencyId);
        if (currency == null) return;
        CurrencyInfo info = Economy.info(currency);
        BigDecimal amount = amount(typedAmount);
        if (amount == null) {
            Messages.send(sender, text().invalidAmount());
            return;
        }
        CurrencyRules rules = StoredEconomy.rules(currency).orElse(null);
        if (rules != null && !rules.transferable()) {
            Messages.send(sender, text().payDisabled(), Values.of().put("currency", info.namePlural()));
            return;
        }
        if (rules != null && amount.compareTo(rules.minimumTransfer()) < 0) {
            Messages.send(sender, text().payMinimum(), Values.of().put("amount", info.format(rules.minimumTransfer())));
            return;
        }
        if (StoredEconomy.loading(sender.getUniqueId(), currency)) {
            Messages.send(sender, text().stillLoading());
            return;
        }
        ExyliaPlayers.then(sender, targetName, found -> {
            if (found.id().equals(sender.getUniqueId())) {
                Messages.send(sender, text().paySelf());
                return;
            }
            // One payment a second to the same player, dropped silently past
            // that: the minimum amount sent in a loop is a chat flood for the
            // receiver, and a "slow down" line per attempt would be one for
            // the sender.
            if (!Cooldowns.tryStart(sender, "exyliaeconomy:pay:" + found.id(), PAY_COOLDOWN)) {
                return;
            }
            BigDecimal tax = rules == null ? BigDecimal.ZERO : rules.tax(amount);
            Economy.CurrencyView view = Economy.of(currency);
            if (!view.has(sender.getUniqueId(), amount.add(tax))) {
                Messages.send(sender, text().notEnough(), Values.of().put("currency", info.namePlural())
                        .put("amount", info.format(amount.add(tax).subtract(view.balance(sender.getUniqueId())))));
                return;
            }
            if (tax.signum() > 0 && !view.withdraw(sender.getUniqueId(), tax,
                    Transaction.of("pay:tax").by(sender.getUniqueId())).isSuccess()) {
                Messages.send(sender, text().notEnough(), Values.of().put("currency", info.namePlural())
                        .put("amount", info.format(tax)));
                return;
            }
            TransferResult result = view.transfer(sender.getUniqueId(), found.id(), amount,
                    Transaction.of("pay").by(sender.getUniqueId()));
            if (!result.isSuccess()) {
                if (tax.signum() > 0) view.deposit(sender.getUniqueId(), tax, Transaction.of("pay:tax-refund"));
                if (result.type() == TransferResult.Type.INSUFFICIENT_FUNDS || result.message() == null) {
                    Messages.send(sender, text().notEnough(), Values.of().put("currency", info.namePlural())
                            .put("amount", info.format(amount)));
                } else {
                    Messages.send(sender, text().refused(), Values.of().put("reason", result.message()));
                }
                return;
            }
            Messages.send(sender, text().paid(), Values.of().put("player", found.name())
                    .put("amount", info.format(amount)).put("tax", info.format(tax)));
            Player receiver = found.here();
            if (receiver != null) {
                Messages.send(receiver, text().received(), Values.of().put("player", sender.getName())
                        .put("amount", info.format(amount)));
            }
        });
    }

    public void exchange(Player sender, @Nullable String fromId, String toId, String typedAmount) {
        String from = resolved(sender, fromId);
        if (from == null) return;
        BigDecimal amount = amount(typedAmount);
        if (amount == null) {
            Messages.send(sender, text().invalidAmount());
            return;
        }
        // Whoever may not use the target may not be paid in it either. An id
        // that is no currency is left to the exchange, which says so.
        String to = currency(toId).orElse(null);
        if (to != null && !Economy.canUse(sender, to)) {
            Messages.send(sender, text().noPermission(), Values.of().put("currency", Economy.info(to).namePlural()));
            return;
        }
        if (StoredEconomy.loading(sender.getUniqueId(), from)) {
            Messages.send(sender, text().stillLoading());
            return;
        }
        StoredEconomy.Exchange exchange = StoredEconomy.exchange(sender.getUniqueId(), from, toId, amount);
        EconomyResponse response = exchange.response();
        if (!response.isSuccess()) {
            String reason = response.type() == EconomyResponse.Type.INSUFFICIENT_FUNDS
                    ? Values.of().put("currency", Economy.info(from).namePlural())
                            .put("amount", Economy.info(from).format(response.shortfall())).apply(text().notEnough())
                    : String.valueOf(response.message());
            Messages.send(sender, text().exchangeFailed(), Values.of().put("reason", reason));
            return;
        }
        Messages.send(sender, text().exchanged(), Values.of().put("from", Economy.info(from).format(exchange.taken()))
                .put("to", Economy.format(toId, response.amount())));
    }

    // --------------------------------------------------------------- admin

    public void give(CommandSender sender, @Nullable String currencyId, String targetName, String typedAmount) {
        adminOp(sender, currencyId, targetName, typedAmount, (currency, found, amount) -> {
            CurrencyInfo info = Economy.info(currency);
            EconomyResponse response = Economy.of(currency)
                    .deposit(found.id(), amount, Transaction.of("admin:give").by(initiator(sender)));
            if (!response.isSuccess()) {
                refused(sender, response, info);
                return;
            }
            if (queues(found, currency)) {
                queued(sender, found, "{success}+" + info.format(amount));
                return;
            }
            Messages.send(sender, text().given(), Values.of().put("player", found.name())
                    .put("amount", info.format(amount)).put("balance", info.format(response.balance())));
            Player target = found.here();
            if (target != null) Messages.send(target, text().givenNotify(), Values.of().put("amount", info.format(amount)));
        });
    }

    public void take(CommandSender sender, @Nullable String currencyId, String targetName, String typedAmount) {
        adminOp(sender, currencyId, targetName, typedAmount, (currency, found, amount) -> {
            CurrencyInfo info = Economy.info(currency);
            EconomyResponse response = Economy.of(currency)
                    .withdraw(found.id(), amount, Transaction.of("admin:take").by(initiator(sender)));
            if (!response.isSuccess()) {
                if (response.type() == EconomyResponse.Type.INSUFFICIENT_FUNDS) {
                    Messages.send(sender, text().notEnough(), Values.of().put("currency", info.namePlural())
                            .put("amount", info.format(response.shortfall())));
                } else if (StoredEconomy.loading(found.id(), currency)) {
                    Messages.send(sender, text().stillLoading());
                } else {
                    refused(sender, response, info);
                }
                return;
            }
            if (queues(found, currency)) {
                queued(sender, found, "{error}-" + info.format(amount));
                return;
            }
            Messages.send(sender, text().taken(), Values.of().put("player", found.name())
                    .put("amount", info.format(amount)).put("balance", info.format(response.balance())));
            Player target = found.here();
            if (target != null) Messages.send(target, text().takenNotify(), Values.of().put("amount", info.format(amount)));
        });
    }

    public void set(CommandSender sender, @Nullable String currencyId, String targetName, String typedAmount) {
        String currency = resolved(sender, currencyId);
        if (currency == null) return;
        BigDecimal amount = "0".equals(typedAmount) ? BigDecimal.ZERO : amount(typedAmount);
        if (amount == null) {
            Messages.send(sender, text().invalidAmount());
            return;
        }
        ExyliaPlayers.then(sender, targetName, found -> {
            CurrencyInfo info = Economy.info(currency);
            EconomyResponse response = Economy.of(currency)
                    .set(found.id(), amount, Transaction.of("admin:set").by(initiator(sender)));
            if (!response.isSuccess()) {
                refused(sender, response, info);
                return;
            }
            if (queues(found, currency)) {
                queued(sender, found, "{highlight}= " + info.format(amount));
                return;
            }
            Messages.send(sender, text().set(), Values.of().put("player", found.name())
                    .put("currency", info.namePlural()).put("amount", info.format(response.balance())));
        });
    }

    public void reset(CommandSender sender, @Nullable String currencyId, String targetName) {
        String currency = resolved(sender, currencyId);
        if (currency == null) return;
        ExyliaPlayers.then(sender, targetName, found -> {
            BigDecimal start = StoredEconomy.rules(currency).map(CurrencyRules::start).orElse(BigDecimal.ZERO);
            Economy.of(currency).set(found.id(), start, Transaction.of("admin:reset").by(initiator(sender)));
            CurrencyInfo info = Economy.info(currency);
            if (queues(found, currency)) {
                queued(sender, found, "{highlight}= " + info.format(start));
                return;
            }
            Messages.send(sender, text().reset(), Values.of().put("player", found.name())
                    .put("currency", info.namePlural()));
        });
    }

    /**
     * Copies every known player's balance from one currency into another.
     *
     * <p>Walks the server's own player list, because neither Vault nor
     * PlayerPoints can list balances: what has played here is what can be
     * imported. Each pair is remembered once a run finishes, and a second run
     * is refused unless the admin says {@code again}; even then nobody already
     * paid is paid twice.
     */
    public void importFrom(CommandSender sender, String fromId, String intoId, boolean again) {
        String from = currency(fromId).orElse(null);
        String into = currency(intoId).orElse(null);
        if (from == null || into == null || Economy.kind(into) != CurrencyKind.STORED) {
            Messages.send(sender, text().noCurrency(), Values.of().put("currency", from == null ? fromId : intoId));
            return;
        }
        CurrencyStore store = StoredEconomy.store();
        if (store == null) {
            Messages.send(sender, EconomyMessages.get().economyOff());
            return;
        }
        Values names = Values.of().put("from", Economy.info(from).namePlural())
                .put("currency", Economy.info(into).namePlural());
        // Both are canonical, so vault names the currency serving it: copying
        // a currency into itself would double every balance.
        if (from.equals(into)) {
            Messages.send(sender, text().importSame(), names);
            return;
        }
        if (!again && store.settings().imported(from, into)) {
            Messages.send(sender, text().importAlready(), names);
            return;
        }
        // A second run typed while this one reads is refused; one after it
        // skips whoever this one paid.
        String pair = from + ">" + into;
        if (!IMPORTING.add(pair)) {
            Messages.send(sender, text().importRunning(), names);
            return;
        }
        Messages.send(sender, text().importStarted(), names);
        List<UUID> players = Arrays.stream(Bukkit.getOfflinePlayers()).map(OfflinePlayer::getUniqueId).toList();
        StoredEconomy.importBalances(from, into, players, initiator(sender)).whenComplete((count, failure) ->
                ExyliaEconomy.getInstance().getTasks().run(() -> {
                    IMPORTING.remove(pair);
                    if (failure != null) {
                        ExyliaEconomy.getInstance().getDebug().error("Economy: the import from " + from + " into "
                                + into + " stopped.", failure);
                        Messages.send(sender, text().importFailed(), names);
                        return;
                    }
                    store.save(store.settings().withImport(from, into));
                    Messages.send(sender, text().importDone(), Values.of().put("count", count)
                            .put("currency", Economy.info(into).namePlural()));
                }));
    }

    // -------------------------------------------------------------- inside

    private interface AdminOp {
        void run(String currency, ExyliaPlayer found, BigDecimal amount);
    }

    private void adminOp(CommandSender sender, @Nullable String currencyId, String targetName,
                         String typedAmount, AdminOp op) {
        String currency = resolved(sender, currencyId);
        if (currency == null) return;
        BigDecimal amount = amount(typedAmount);
        if (amount == null) {
            Messages.send(sender, text().invalidAmount());
            return;
        }
        ExyliaPlayers.then(sender, targetName, found -> op.run(currency, found, amount));
    }

    /**
     * A typed amount, or {@code null} when it is not a positive one.
     *
     * <p>ExyliaLib's reading refuses exponents and anything past 30 whole
     * digits or 10 decimals; what a balance may actually hold is the
     * currency's to refuse, with its ceiling.
     */
    static @Nullable BigDecimal amount(@Nullable String typed) {
        return Economy.parseAmount(typed);
    }

    /** Why a change was refused, in the currency's own words when it gave any. */
    private static void refused(CommandSender sender, EconomyResponse response, CurrencyInfo info) {
        if (response.type() == EconomyResponse.Type.FAILURE && response.message() != null) {
            Messages.send(sender, text().refused(), Values.of().put("reason", response.message()));
        } else if (response.type() == EconomyResponse.Type.INVALID_AMOUNT) {
            Messages.send(sender, text().invalidAmount());
        } else {
            Messages.send(sender, text().notAvailable(), Values.of().put("currency", info.namePlural()));
        }
    }

    /** The currency asked for, told to the sender when it cannot be used. */
    private @Nullable String resolved(CommandSender sender, @Nullable String currencyId) {
        Optional<String> currency = currency(currencyId);
        if (currency.isEmpty()) {
            Messages.send(sender, text().noCurrency(), Values.of().put("currency", currencyId == null ? "" : currencyId));
            return null;
        }
        if (!Economy.canUse(sender, currency.get())) {
            Messages.send(sender, text().noPermission(), Values.of().put("currency", Economy.info(currency.get()).namePlural()));
            return null;
        }
        return currency.get();
    }

    /**
     * Whether a change to this player lands now or waits for them to be seen.
     *
     * <p>A stored balance is owned by the server the player is on, so a change
     * made anywhere else is a queued row, not a new balance: the sender is
     * told that rather than shown a "now" that is the zero of a balance
     * nothing here holds.
     */
    private static boolean queues(ExyliaPlayer found, String currency) {
        return found.here() == null && Economy.kind(currency) == CurrencyKind.STORED;
    }

    /** Tells the sender a change is waiting, with its sign so it reads right. */
    private static void queued(CommandSender sender, ExyliaPlayer found, String change) {
        Messages.send(sender, text().queued(), Values.of()
                .put("player", found.name()).put("amount", change));
    }

    /** A ledger reason as players read it: {@code shop:sell} is a shop sale. */
    public static String reasonLabel(String reason) {
        return text().label(text().reasons(), reason);
    }

    /** What kind of currency an id is, as players read it. */
    public static String kindLabel(String currency) {
        return text().label(text().kinds(), Economy.kind(currency).name().toLowerCase(Locale.ROOT));
    }

    /** Runs an answer on the server thread, whichever thread the read landed on. */
    private static void later(CompletableFuture<BigDecimal> read, Consumer<BigDecimal> show) {
        read.thenAccept(amount -> ExyliaEconomy.getInstance().getTasks().run(() -> show.accept(amount)));
    }

    private static @Nullable UUID initiator(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : null;
    }

    /** The ids that get a command of their own, and their aliases. */
    public static List<CurrencyRules> withCommands() {
        List<CurrencyRules> out = new ArrayList<>();
        for (String id : Economy.ordered()) {
            StoredEconomy.rules(id).filter(rules -> rules.commands() && !rules.aliases().isEmpty()).ifPresent(out::add);
        }
        return out;
    }
}
