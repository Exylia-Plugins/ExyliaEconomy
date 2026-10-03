package net.exylia.exyliaEconomy.manager;

import net.exylia.exyliaEconomy.ExyliaEconomy;
import net.exylia.exyliaEconomy.database.BalanceRow;
import net.exylia.exyliaEconomy.database.CurrencyRow;
import net.exylia.exyliaEconomy.database.LedgerRow;
import net.exylia.exyliaEconomy.common.Values;
import net.exylia.exyliaEconomy.config.EconomyMessages;
import net.exylia.exyliaEconomy.database.PendingRow;
import net.exylia.exyliaEconomy.model.CurrencyRules;
import net.exylia.exyliaEconomy.model.LedgerEntry;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Repository;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.economy.BalanceChangeEvent;
import net.exylia.lib.economy.CurrencyInfo;
import net.exylia.lib.economy.CurrencyKind;
import net.exylia.lib.economy.CurrencyProvider;
import net.exylia.lib.economy.Economy;
import net.exylia.lib.economy.EconomyResponse;
import net.exylia.lib.economy.ExperienceCurrency;
import net.exylia.lib.economy.ItemCurrency;
import net.exylia.lib.economy.Transaction;
import net.exylia.lib.player.ExyliaPlayers;
import net.exylia.lib.redis.Channel;
import net.exylia.lib.redis.Channels;
import net.exylia.lib.redis.Redis;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.reward.PluginRewards;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * The runtime behind every currency this plugin keeps.
 *
 * <p>Reads the currencies from {@link CurrencyStore}, registers a
 * {@link StoredCurrency} per stored one (and ExyliaLib's item and experience
 * currencies) with {@link Economy}, loads a player's balances when they join
 * and lets go when they leave, writes the snapshot and the ledger, folds in
 * what other servers queued, and answers leaderboards and history.
 *
 * <p>ExyliaLib only talks to economies; this is one of them. A plugin paying
 * through {@code Economy.of(id)} reaches these currencies like any other, and
 * what the library has no word for — history, leaderboards, exchange, rules,
 * kinds — is asked here.
 *
 * <h2>Cross-server</h2>
 * Ownership, not synchronisation. The server a player is on is the only one
 * that writes their snapshot. Anybody else queues a {@link PendingRow} and
 * publishes the player's id on the {@code economy} channel; the owner, if
 * there is one, claims the rows at once, and otherwise they wait for the next
 * join. Each row is taken by deleting it, so a change lands exactly once no
 * matter how many servers heard about it.
 *
 * <p>Ownership moves by compare-and-set, see {@link BalanceRow}: a server the
 * player joins waits for the last one to write its final change and let go,
 * and a write by a server that lost the row is queued for the new owner
 * instead of written over it.
 *
 * <h2>Writes</h2>
 * One chain of database work per player — loading, claiming, writing, letting
 * go — so it all lands in the order it happened. Balance changes gather for
 * half a second and are written as one snapshot per currency, with
 * consecutive ledger lines for the same reason written as one line.
 */
public final class StoredEconomy implements Listener {

    /** Why a withdrawal from a player on this server was refused before their balance was read. */
    public static final String LOADING = "Your balance is still loading. Try again in a moment.";

    private static final String CHANNEL = "economy";
    /** What a server publishes on the channel after an admin changed the currencies. */
    private static final String CURRENCIES_CHANGED = "currencies";
    private static final long TOP_CACHE_MILLIS = 60_000L;
    /** How long a balance of somebody not held here is served from memory. */
    private static final long OFFLINE_CACHE_MILLIS = 30_000L;
    /** How long a joining server waits for the last one to let go before taking the balance over. */
    private static final long HANDOVER_MILLIS = 5_000L;
    private static final long HANDOVER_RETRY_TICKS = 5L;
    private static final int TAKE_ATTEMPTS = 60;
    // ponytail: a crash loses at most this window of balance changes; shorten it if that ever matters more than write volume.
    private static final long COALESCE_TICKS = 10L;
    private static final long SHUTDOWN_WAIT_SECONDS = 10L;
    private static final int PURGE_PAGE = 500;
    private static final int MAX_PURGE_DAYS = 400;
    private static final CompletableFuture<Void> DONE = CompletableFuture.completedFuture(null);

    private static volatile StoredEconomy instance;

    /** Supply snapshots: once a minute after enable, then every ten minutes. */
    private static final long SUPPLY_DELAY_TICKS = 1_200L;
    private static final long SUPPLY_PERIOD_TICKS = 12_000L;
    private static final String ANALYTICS = "net.exylia.analytics.api.ExyliaAnalytics";

    private final Plugin plugin;
    private final Logger logger;
    private final Debug debug;
    private final TaskScheduler tasks;
    private final Repository<BalanceRow> balances;
    private final Repository<PendingRow> pending;
    private final Repository<LedgerRow> ledger;
    private final String server;

    private volatile CurrencyFile.Contents contents;
    /** Swapped whole on every load, never changed in place: read from any thread. */
    private volatile Map<String, StoredCurrency> stored = Map.of();
    private volatile List<Extra> extras = List.of();
    private final Map<String, CachedTop> tops = new ConcurrentHashMap<>();
    private final Map<String, Snapshot> offline = new ConcurrentHashMap<>();
    /** The names of the players held here, stamped on every row written. */
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    /** One number per stay on this server: a load that lands after a quit is recognised by it. */
    private final Map<UUID, Long> sessions = new ConcurrentHashMap<>();
    private final AtomicLong nextSession = new AtomicLong();
    private final Map<UUID, Batch> batches = new ConcurrentHashMap<>();
    /** Delays not yet over, completed at once when the plugin stops and no tick will end them. */
    private final Set<CompletableFuture<Void>> waits = ConcurrentHashMap.newKeySet();

    /**
     * One queue of database work per player.
     *
     * <p>Every write is asynchronous and the pool has more than one
     * connection, so two writes for one player can land in either order — and
     * a snapshot of 100 landing after the snapshot of 70 that followed it is a
     * balance that grew on its own. Chained, they land in the order they were
     * made, and the ledger reads in the order things happened.
     */
    private final Map<UUID, CompletableFuture<Void>> chains = new ConcurrentHashMap<>();
    private final VaultBridge vault;
    private final CurrencyStore store;
    /** Run on the plugin's thread after every load: the alias commands follow the currencies. */
    private final Runnable afterLoad;
    private volatile boolean ready;
    private volatile boolean stopping;
    private Channel channel;
    private Channel.Subscription subscription;
    private TaskHandle sweeper;
    private TaskHandle purger;
    private TaskHandle supplier;
    /** ExyliaAnalytics.supply(String, String, BigDecimal, long), once it is found. */
    private volatile Method supply;

    private record CachedTop(long at, List<TopEntry> entries) { }

    private record Snapshot(long at, BigDecimal amount) { }

    /** An item or experience currency, with the settings it was built from. */
    private record Extra(CurrencyProvider provider, @Nullable CurrencyFile.Item item) { }

    /** One change waiting to be written: its ledger line, or its pending row if the balance was lost. */
    private record Move(StoredCurrency currency, BigDecimal delta, BigDecimal after, Transaction transaction) { }

    /** A player's changes gathering for one write. */
    private static final class Batch {
        final Map<StoredCurrency, BigDecimal> amounts = new LinkedHashMap<>();
        final List<Move> moves = new ArrayList<>();
        CompletableFuture<Void> due;
    }

    /** One line of a leaderboard. */
    public record TopEntry(int position, @NotNull UUID player, @NotNull String name,
                           @NotNull BigDecimal amount) {
    }

    private StoredEconomy(Plugin plugin, Runnable afterLoad) {
        this.plugin = plugin;
        this.afterLoad = afterLoad;
        this.store = new CurrencyStore(plugin);
        this.logger = plugin.getLogger();
        this.debug = Debug.of(plugin);
        this.tasks = Tasks.of(plugin);
        this.balances = Databases.of(plugin).repository(BalanceRow.class);
        this.pending = Databases.of(plugin).repository(PendingRow.class);
        this.ledger = Databases.of(plugin).repository(LedgerRow.class);
        this.server = Redis.serverId(plugin);
        this.vault = new VaultBridge(plugin);
    }

    // ------------------------------------------------------------ lifecycle

    /**
     * Called once by the plugin as it enables.
     *
     * @param afterLoad run on the plugin's thread after every load of the
     *                  currencies, this one included
     */
    public static void init(@NotNull Plugin plugin, @NotNull Runnable afterLoad) {
        StoredEconomy economy = new StoredEconomy(plugin, afterLoad);
        instance = economy;
        // Not waited for: a join here freezes the server, because the query does
        // not start before the first tick. Applied on that tick, which also
        // loads the players already here.
        economy.store.load().whenComplete((read, failure) -> {
            if (failure != null) {
                economy.debug.error("Economy: could not read the currencies from the database; none are"
                        + " registered until /economyadmin reload succeeds.", failure);
                return;
            }
            economy.tasks.run(() -> {
                if (instance == economy) economy.apply(read);
            });
        });
        Bukkit.getPluginManager().registerEvents(economy, plugin);
        economy.channel = Channels.of(plugin).channel(CHANNEL);
        economy.subscription = economy.channel.subscribe(message -> {
            if (message.local()) return;
            economy.wake(message.payload());
        });
        // A periodic sweep for the message that never arrived, and an hourly
        // look for ledger lines past their retention.
        economy.sweeper = economy.tasks.runAsyncTimer(200L, 1200L, economy::sweep);
        economy.purger = economy.tasks.runAsyncTimer(6000L, 72_000L, economy::purgeLedger);
        economy.supplier = economy.tasks.runAsyncTimer(SUPPLY_DELAY_TICKS, SUPPLY_PERIOD_TICKS, economy::reportSupply);
    }

    /** Whether the economy runs and its currencies are still being read, so "no economy" is not the answer yet. */
    public static boolean loading() {
        StoredEconomy economy = instance;
        return economy != null && !economy.ready;
    }

    /** Whether a player on this server is still waiting for their balance in a stored currency to be read. */
    public static boolean loading(@NotNull UUID player, @NotNull String id) {
        StoredEconomy economy = instance;
        if (economy == null) return false;
        StoredCurrency currency = economy.currency(Economy.canonical(id));
        return currency != null && economy.isHere(player) && !currency.isLoaded(player);
    }

    /**
     * Re-reads the currencies from the database: {@code /economyadmin reload},
     * or another server saying an admin changed them.
     *
     * <p>Read off the game thread and applied back on the plugin's.
     */
    public static void reload() {
        StoredEconomy economy = instance;
        if (economy == null) return;
        economy.store.fetch().whenComplete((read, failure) -> {
            if (failure != null) {
                economy.debug.error("Economy: could not re-read the currencies; the old ones stay.", failure);
                return;
            }
            economy.tasks.run(() -> {
                if (instance == economy) economy.apply(read);
            });
        });
    }

    /** The rows an admin edits, or {@code null} while the economy is not running. */
    public static @Nullable CurrencyStore store() {
        StoredEconomy economy = instance;
        return economy == null ? null : economy.store;
    }

    /**
     * An admin changed the currencies: register them again here now, and tell
     * the other servers once the write has landed so they read it.
     *
     * <p>Call on the plugin's thread, after changing the store.
     *
     * @param written the store's write
     */
    public static void changed(@NotNull CompletableFuture<?> written) {
        StoredEconomy economy = instance;
        if (economy == null) return;
        economy.apply(economy.store.contents());
        written.whenComplete((ignored, failure) -> {
            if (failure != null) {
                economy.debug.error("Economy: could not write a currency change.", failure);
            } else if (economy.channel != null) {
                economy.channel.publish(CURRENCIES_CHANGED);
            }
        });
    }

    /**
     * Called by the plugin as it disables, while the database is still up.
     *
     * <p>Lets go of every player still here and waits, up to ten seconds, for
     * every write already queued: the library only waits for queries that
     * have started, not for the links queued behind them.
     */
    public static void shutdown() {
        StoredEconomy economy = instance;
        if (economy == null) return;
        instance = null;
        economy.ready = false;
        economy.stopping = true;
        if (economy.sweeper != null) economy.sweeper.cancel();
        if (economy.purger != null) economy.purger.cancel();
        if (economy.supplier != null) economy.supplier.cancel();
        if (economy.subscription != null) economy.subscription.close();
        for (UUID player : List.copyOf(economy.sessions.keySet())) economy.leave(player);
        economy.waits.forEach(wait -> wait.complete(null));
        economy.drain();
        HandlerList.unregisterAll(economy);
        economy.vault.unpublish();
        economy.unregisterAll();
        Economy.overlays(Map.of());
    }

    private void drain() {
        CompletableFuture<?>[] tails = chains.values().toArray(new CompletableFuture[0]);
        try {
            CompletableFuture.allOf(tails).get(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException slow) {
            logger.warning("Economy: some balance writes had not landed after " + SHUTDOWN_WAIT_SECONDS
                    + " seconds; what they changed is lost if the database never receives them.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException ignored) {
            // Every link already logs its own failure.
        }
    }

    /** The rules of a stored currency, for the commands put on it. */
    public static @NotNull Optional<CurrencyRules> rules(@NotNull String id) {
        StoredEconomy economy = instance;
        if (economy == null) return Optional.empty();
        StoredCurrency currency = economy.currency(id);
        if (currency == null) return Optional.empty();
        CurrencyFile.Stored s = currency.settings();
        return Optional.of(new CurrencyRules(s.id(), s.aliases(), s.start(), s.max(),
                s.permission(), s.transferable(), s.minimumTransfer(), BigDecimal.valueOf(s.transferTaxPercent()),
                s.exchangeable(), s.rates(), s.leaderboard(), s.commands()));
    }

    boolean isReady() {
        return ready;
    }

    CurrencyStore currencyStore() {
        return store;
    }

    VaultBridge vault() {
        return vault;
    }

    /** Whether a player is on this server, their balance read or not. */
    boolean isHere(UUID player) {
        return sessions.containsKey(player);
    }

    /**
     * Registers what was read.
     *
     * <p>A currency that was already registered keeps its instance and the
     * balances it holds, and only takes the new settings: reading the players'
     * balances back while their writes are still on the way would read what
     * they had before those writes.
     */
    private void apply(CurrencyFile.Contents read) {
        contents = read;
        Map<String, StoredCurrency> before = stored;
        Map<String, StoredCurrency> next = new LinkedHashMap<>();
        for (CurrencyFile.Stored settings : read.stored().values()) {
            StoredCurrency kept = before.get(settings.id());
            if (kept != null) {
                kept.settings(settings);
                next.put(settings.id(), kept);
                continue;
            }
            StoredCurrency created = new StoredCurrency(settings, this);
            if (register(created)) next.put(settings.id(), created);
        }
        for (StoredCurrency gone : before.values()) {
            if (next.get(gone.id()) == gone) continue;
            Economy.unregister(gone.id());
            for (UUID player : gone.loadedPlayers()) {
                if (gone.unload(player)) chain(player, () -> release(gone, player));
            }
        }
        stored = Collections.unmodifiableMap(next);
        tops.keySet().retainAll(next.keySet());
        extras = applyExtras(read);
        ready = true;

        for (Player online : Bukkit.getOnlinePlayers()) {
            UUID id = online.getUniqueId();
            sessions.computeIfAbsent(id, ignored -> nextSession.incrementAndGet());
            names.putIfAbsent(id, online.getName());
            load(id);
        }
        // Neither exists everywhere this runs, and neither is worth a currency
        // that fails to load.
        String provide = read.vaultProvide().toLowerCase(Locale.ROOT);
        StoredCurrency named = provide.isBlank() ? null : next.get(provide);
        if (named == null && !provide.isBlank()) {
            logger.warning("Economy: Vault is set to '" + provide + "', which is not a stored currency.");
        }
        // ExyliaLib's default currency is 'vault'. With no economy plugin behind
        // Vault, leaving it empty leaves the whole server without money, so the
        // first stored currency serves it instead.
        StoredCurrency published = named != null || vault.othersServe()
                ? named : next.values().stream().findFirst().orElse(null);
        if (published != named) {
            logger.warning("Economy: no currency is set for Vault and no economy plugin is installed, so '"
                    + published.id() + "' serves Vault. Pick another in /economyadmin.");
        }
        safely("publish the Vault economy", () -> vault.publish(published, read.vaultForce()));
        // The library's 'vault' currency describes itself the generic way: two
        // decimals, the symbol in front. While one of ours serves Vault, 'vault'
        // is that currency, and %economy_balance% must write it the same way
        // /dollars does. The file's own 'vault' overlay still goes on top.
        Map<String, CurrencyInfo> overlays = new LinkedHashMap<>(read.display());
        StoredCurrency serving = vault.serving();
        if (serving != null) overlays.put("vault", serving.info().overlaid(read.overlay("vault")));
        Economy.overlays(overlays);
        logger.info("Economy: " + next.size() + " stored, " + read.items().size() + " item and "
                + ((read.experienceLevels() ? 1 : 0) + (read.experiencePoints() ? 1 : 0))
                + " experience currencies.");
        safely("install the currency commands", afterLoad);
    }

    /** Where the owner put a currency in the list, from its row; unarranged ones come last. */
    private int order(String id) {
        return store.get(id).map(CurrencyRow::sortOrder).orElse(Integer.MAX_VALUE);
    }

    /**
     * The item and experience currencies, from ExyliaLib.
     *
     * <p>They keep nothing of their own — the balance is on the player — so
     * one whose settings changed is simply built again; an unchanged one stays
     * registered.
     */
    private List<Extra> applyExtras(CurrencyFile.Contents read) {
        PluginRewards rewards = ExyliaEconomy.getInstance().getRewards();
        Map<String, Extra> before = new HashMap<>();
        for (Extra extra : extras) before.put(extra.provider().id(), extra);

        List<Extra> wanted = new ArrayList<>();
        if (read.experienceLevels()) {
            Extra kept = before.get(ExperienceCurrency.LEVELS);
            wanted.add(kept != null && kept.provider().order() == order(ExperienceCurrency.LEVELS) ? kept
                    : new Extra(ExperienceCurrency.levels(rewards, order(ExperienceCurrency.LEVELS)), null));
        }
        if (read.experiencePoints()) {
            Extra kept = before.get(ExperienceCurrency.POINTS);
            wanted.add(kept != null && kept.provider().order() == order(ExperienceCurrency.POINTS) ? kept
                    : new Extra(ExperienceCurrency.points(rewards, order(ExperienceCurrency.POINTS)), null));
        }
        for (CurrencyFile.Item item : read.items().values()) {
            Extra kept = before.get(item.id());
            if (kept != null && item.equals(kept.item()) && kept.provider().order() == order(item.id())) {
                wanted.add(kept);
                continue;
            }
            ItemStack stack = itemOf(item);
            if (stack != null) wanted.add(new Extra(ItemCurrency.of(rewards, item.info(), stack, order(item.id())), item));
        }
        for (Extra old : before.values()) {
            if (!wanted.contains(old)) Economy.unregister(old.provider().id());
        }
        List<Extra> registered = new ArrayList<>();
        for (Extra extra : wanted) {
            if (before.containsValue(extra) || register(extra.provider())) registered.add(extra);
        }
        return List.copyOf(registered);
    }

    /**
     * The item an item currency is: a material name or a {@code bytes:}
     * snapshot, the same two spellings every item in the ecosystem's files uses.
     */
    private @Nullable ItemStack itemOf(CurrencyFile.Item settings) {
        String raw = settings.item().trim();
        try {
            if (raw.toLowerCase(Locale.ROOT).startsWith("bytes:")) {
                return ItemStack.deserializeBytes(Base64.getDecoder().decode(raw.substring("bytes:".length())));
            }
            Material material = Material.getMaterial(raw.toUpperCase(Locale.ROOT));
            if (material == null || !material.isItem()) throw new IllegalArgumentException("not an item: " + raw);
            return new ItemStack(material);
        } catch (RuntimeException unreadable) {
            logger.warning("Economy: item currency '" + settings.id() + "' holds '"
                    + raw + "', which is not an item (" + unreadable.getMessage() + ").");
            return null;
        }
    }

    private boolean register(CurrencyProvider provider) {
        try {
            Economy.register(provider);
            return true;
        } catch (RuntimeException taken) {
            logger.warning("Economy: the currency '" + provider.id() + "' is not registered: " + taken.getMessage());
            return false;
        }
    }

    private void unregisterAll() {
        for (StoredCurrency currency : stored.values()) Economy.unregister(currency.id());
        for (Extra extra : extras) Economy.unregister(extra.provider().id());
        stored = Map.of();
        extras = List.of();
        tops.clear();
    }

    /** Every stored currency, in file order. */
    public @NotNull Collection<StoredCurrency> currencies() {
        return stored.values();
    }

    public @NotNull CurrencyFile.Contents contents() {
        return contents;
    }

    public @Nullable StoredCurrency currency(@NotNull String id) {
        return stored.get(id.toLowerCase(Locale.ROOT));
    }

    private @Nullable CurrencyProvider extra(String id) {
        for (Extra extra : extras) {
            if (extra.provider().id().equals(id)) return extra.provider();
        }
        return null;
    }

    // --------------------------------------------------------------- players

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        sessions.put(id, nextSession.incrementAndGet());
        names.put(id, event.getPlayer().getName());
        load(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        leave(event.getPlayer().getUniqueId());
    }

    /** Another plugin's economy came or went: who serves Vault may have changed. */
    @EventHandler
    public void onServiceRegister(ServiceRegisterEvent event) {
        vault.refresh();
    }

    @EventHandler
    public void onServiceUnregister(ServiceUnregisterEvent event) {
        vault.refresh();
    }

    /**
     * Lets go of a player: whatever is still gathering is written at once, and
     * each balance is handed back to the network after its last write.
     */
    private void leave(UUID player) {
        sessions.remove(player);
        Batch batch = batches.get(player);
        if (batch != null) {
            synchronized (batch) {
                if (batch.due != null) batch.due.complete(null);
            }
        }
        for (StoredCurrency currency : stored.values()) {
            if (currency.unload(player)) chain(player, () -> release(currency, player));
        }
        chain(player, () -> {
            // Kept when they are already back: the next stay reuses them.
            if (!sessions.containsKey(player)) {
                batches.remove(player);
                names.remove(player);
            }
            return DONE;
        });
        // The chain is dropped once it is drained, not before: a write still
        // in flight for a player who just left is the write that matters most.
        CompletableFuture<Void> tail = chains.get(player);
        if (tail != null) tail.whenComplete((ignored, failure) -> chains.remove(player, tail));
    }

    /**
     * Takes ownership of a player's balances, then folds in what other servers
     * queued.
     *
     * <p>Until a balance is claimed a deposit is queued like any other
     * server's and applied a moment later, and a withdrawal is refused.
     */
    private void load(UUID player) {
        Long session = sessions.get(player);
        if (session == null) return;
        chain(player, () -> {
            CompletableFuture<Void> step = DONE;
            for (StoredCurrency currency : stored.values()) {
                step = step.thenCompose(ignored -> currency.isLoaded(player) ? DONE
                        : take(currency, player, session, System.currentTimeMillis() + HANDOVER_MILLIS, 0)
                                .exceptionally(failure -> {
                                    debug.error("Economy: could not load the " + currency.id() + " balance of "
                                            + player + "; it is tried again within a minute.", failure);
                                    return null;
                                }));
            }
            return step.thenCompose(ignored -> claimPending(player));
        });
    }

    /**
     * Claims one balance row for this server.
     *
     * <p>Waits while another server still holds it, up to
     * {@link #HANDOVER_MILLIS}, and takes it anyway after that. The claim
     * compares the version that was read, so a last write from the old owner
     * landing in between makes it read again rather than write over it.
     */
    private CompletableFuture<Void> take(StoredCurrency currency, UUID player, long session, long deadline,
                                         int attempt) {
        if (stopping || !here(player, session) || currency.isLoaded(player)) return DONE;
        if (attempt >= TAKE_ATTEMPTS) {
            logger.warning("Economy: gave up claiming the " + currency.id() + " balance of " + player
                    + " for now; it is tried again within a minute.");
            return DONE;
        }
        String id = BalanceRow.id(player, key(currency));
        return balances.find(id).thenCompose(found -> {
            if (found.isEmpty()) {
                // Created only when still missing: another server that made it
                // first gets a version plus zero, which changes nothing. Held by
                // nobody until claimed, so a quit before the claim leaves no
                // owner for the next server to wait out.
                BalanceRow fresh = new BalanceRow(player, name(player), key(currency),
                        currency.clamp(currency.settings().start()), "", 0L);
                return balances.increment(fresh, "version")
                        .thenCompose(ignored -> take(currency, player, session, deadline, attempt + 1));
            }
            BalanceRow row = found.get();
            if (!row.freeFor(server) && System.currentTimeMillis() < deadline) {
                return later(HANDOVER_RETRY_TICKS)
                        .thenCompose(ignored -> take(currency, player, session, deadline, attempt + 1));
            }
            BigDecimal amount = currency.clamp(row.amount());
            long version = row.version() + 1;
            BalanceRow claimed = new BalanceRow(player, name(player), key(currency), amount, server, version);
            return claim(claimed, row.version()).thenCompose(won -> {
                if (!won) return take(currency, player, session, deadline, attempt + 1);
                if (currency.load(player, amount, version, () -> here(player, session))) {
                    offline.remove(id);
                    // Loaded here, not through the facade: the library's cache
                    // still holds whatever it read before, the placeholder zero
                    // of an absent player included.
                    Economy.remember(currency.id(), player, amount);
                    return DONE;
                }
                // They left while it was being claimed: hand it straight back.
                BalanceRow released = new BalanceRow(player, name(player), key(currency), amount, "", version + 1);
                return balances.updateIf(released, "version", version).thenApply(ignored -> (Void) null);
            });
        });
    }

    /** A compare-and-set on the version, including the rows written before the column existed. */
    private CompletableFuture<Boolean> claim(BalanceRow row, long expected) {
        if (expected != 0L) return balances.updateIf(row, "version", expected);
        return balances.updateIf(row, "version", 0L).thenCompose(won -> won
                ? CompletableFuture.completedFuture(true)
                : balances.updateIf(row, "version", null));
    }

    /** The last write of a player's stay: the balance with nobody's name on it. */
    private CompletableFuture<Void> release(StoredCurrency currency, UUID player) {
        StoredCurrency.Written last = currency.forgetWritten(player);
        if (last == null) return DONE;
        BalanceRow row = new BalanceRow(player, name(player), key(currency), last.amount(), "", last.version() + 1);
        return balances.updateIf(row, "version", last.version()).thenApply(ignored -> null);
    }

    /**
     * Folds in what other servers queued for a player on this server, oldest
     * first, one row after the other: an absolute {@code set} and the deposit
     * queued after it apply in that order.
     */
    private CompletableFuture<Void> claimPending(UUID player) {
        if (!isHere(player)) return DONE;
        return pending.where("player", player.toString()).orderBy("created_at").orderBy("id").find()
                .thenCompose(rows -> {
                    CompletableFuture<Void> step = DONE;
                    for (PendingRow row : rows) step = step.thenCompose(ignored -> claimRow(player, row));
                    return step;
                });
    }

    private CompletableFuture<Void> claimRow(UUID player, PendingRow row) {
        StoredCurrency currency = pendingCurrency(row.currency());
        CurrencyProvider extra = currency == null ? extra(row.currency()) : null;
        if (currency == null ? extra == null || !isHere(player) : !currency.isLoaded(player)) return DONE;
        return pending.delete(row.id()).thenCompose(taken -> {
            if (!Boolean.TRUE.equals(taken)) return DONE;
            if (currency != null && currency.applyPending(player, row)) return DONE;
            if (extra != null) {
                Transaction transaction = new Transaction(row.reason() == null ? "api" : row.reason(),
                        row.initiator() == null ? null : UUID.fromString(row.initiator()));
                if (extra.deposit(player, row.amount(), transaction).isSuccess()) return DONE;
            }
            // Ours now, and not applied: back in the queue rather than away.
            return insertPending(new PendingRow(player, row.currency(), row.amount(), row.absolute(), row.reason(),
                    row.initiator() == null ? null : UUID.fromString(row.initiator())));
        });
    }

    /** A message from another server: somebody's balance has something waiting. */
    private void wake(String payload) {
        if (CURRENCIES_CHANGED.equals(payload.trim())) {
            reload();
            return;
        }
        UUID player;
        try {
            player = UUID.fromString(payload.trim());
        } catch (IllegalArgumentException notAnId) {
            return;
        }
        for (StoredCurrency currency : stored.values()) offline.remove(BalanceRow.id(player, key(currency)));
        if (isHere(player)) chain(player, () -> claimPending(player));
    }

    /**
     * Every minute: one query per player here for what the network did not
     * tell us about, and another try at a balance that could not be claimed.
     */
    private void sweep() {
        long now = System.currentTimeMillis();
        offline.values().removeIf(snapshot -> now - snapshot.at() > OFFLINE_CACHE_MILLIS);
        for (UUID player : sessions.keySet()) {
            boolean unclaimed = stored.values().stream().anyMatch(currency -> !currency.isLoaded(player));
            if (unclaimed) {
                load(player);
            } else {
                chain(player, () -> claimPending(player));
            }
        }
    }

    // ---------------------------------------------------------------- writes

    /**
     * A balance changed in memory: gather it for the player's next write.
     *
     * <p>Called under the currency's lock, so changes reach the batch in the
     * order they were made.
     */
    void written(StoredCurrency currency, UUID player, BigDecimal after, BigDecimal moved,
                 Transaction transaction) {
        Batch batch = batches.computeIfAbsent(player, ignored -> new Batch());
        CompletableFuture<Void> due;
        synchronized (batch) {
            batch.amounts.put(currency, after);
            if (moved.signum() != 0) batch.moves.add(new Move(currency, moved, after, transaction));
            if (batch.due != null) return;
            due = later(COALESCE_TICKS);
            batch.due = due;
        }
        chain(player, () -> due.thenCompose(ignored -> flush(player, batch)));
    }

    /** Writes what a player's batch gathered: the latest amount per currency, then its ledger lines. */
    private CompletableFuture<Void> flush(UUID player, Batch batch) {
        Map<StoredCurrency, BigDecimal> amounts;
        List<Move> moves;
        synchronized (batch) {
            amounts = new LinkedHashMap<>(batch.amounts);
            moves = List.copyOf(batch.moves);
            batch.amounts.clear();
            batch.moves.clear();
            batch.due = null;
        }
        CompletableFuture<Void> step = DONE;
        for (Map.Entry<StoredCurrency, BigDecimal> entry : amounts.entrySet()) {
            StoredCurrency currency = entry.getKey();
            List<Move> own = moves.stream().filter(move -> move.currency() == currency).toList();
            step = step.thenCompose(ignored -> snapshot(currency, player, entry.getValue(), own));
        }
        return step;
    }

    private CompletableFuture<Void> snapshot(StoredCurrency currency, UUID player, BigDecimal amount,
                                             List<Move> moves) {
        StoredCurrency.Written last = currency.written(player);
        if (last == null) return requeue(currency, player, moves);
        long version = last.version() + 1;
        BalanceRow row = new BalanceRow(player, name(player), key(currency), amount, server, version);
        return balances.updateIf(row, "version", last.version()).thenCompose(won -> {
            if (won) {
                currency.wrote(player, new StoredCurrency.Written(version, amount));
                announce(player, moves);
                return writeLedger(player, moves);
            }
            logger.warning("Economy: another server took over the " + currency.id() + " balance of " + player
                    + "; the changes made here are queued for it.");
            currency.lost(player);
            return requeue(currency, player, moves);
        });
    }

    /**
     * Tells the server about changes that have just been written.
     *
     * <p>Here and not where they were asked for: a change for a player held
     * elsewhere is queued and lands on that server, and a write that loses the
     * balance to another server is queued for it. Announcing what was written
     * is what makes each change count once across the network.
     */
    private void announce(UUID player, List<Move> moves) {
        for (Move move : moves) {
            try {
                new BalanceChangeEvent(player, move.currency().id(), move.after().subtract(move.delta()),
                        move.after(), move.transaction()).callEvent();
            } catch (RuntimeException | LinkageError failure) {
                debug.error("Economy: a listener failed on a balance change.", failure);
            }
        }
    }

    /**
     * Hands every stored currency's money supply to ExyliaAnalytics, when it is
     * installed: the sum of the balances and how many of them hold something.
     *
     * <p>Added up and counted in the database, never read: a balance is never
     * negative (a withdrawal refuses to overdraw and a set clamps at zero), so
     * the sum over every row is the sum of the positive ones, and the holders
     * are the rows minus the empty ones.
     */
    private void reportSupply() {
        Method report = supply;
        if (report == null) {
            try {
                report = Class.forName(ANALYTICS).getMethod("supply", String.class, String.class, BigDecimal.class,
                        long.class);
                supply = report;
            } catch (ReflectiveOperationException | LinkageError absent) {
                return;
            }
        }
        if (!ready || stopping) return;
        Method call = report;
        CompletableFuture<Void> step = DONE;
        for (StoredCurrency currency : stored.values()) {
            String key = key(currency);
            step = step.thenCompose(ignored -> supplyOf(key).thenAccept(total -> {
                try {
                    call.invoke(null, currency.id(), key, total.sum(), total.holders());
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    debug.error("Economy: ExyliaAnalytics refused the money supply of " + currency.id() + ".", failure);
                }
            }));
        }
        step.exceptionally(failure -> {
            debug.error("Economy: could not read the money supply.", failure);
            return null;
        });
    }

    private record Supply(BigDecimal sum, long holders) { }

    private CompletableFuture<Supply> supplyOf(String key) {
        CompletableFuture<BigDecimal> sum = balances.where("currency", key).sum("amount");
        CompletableFuture<Long> rows = balances.where("currency", key).count();
        CompletableFuture<Long> empty = balances.where("currency", key).where("amount", BigDecimal.ZERO).count();
        return sum.thenCombine(rows, (total, all) -> new Supply(total, all))
                .thenCombine(empty, (supply, none) -> new Supply(supply.sum(), supply.holders() - none));
    }

    /** Changes a lost balance never wrote, as pending rows for whoever holds it now. */
    private CompletableFuture<Void> requeue(StoredCurrency currency, UUID player, List<Move> moves) {
        CompletableFuture<Void> step = DONE;
        for (Move move : moves) {
            step = step.thenCompose(ignored -> insertPending(new PendingRow(player, key(currency), move.delta(),
                    false, move.transaction().reason(), move.transaction().initiator())));
        }
        return step.thenRun(() -> {
            if (channel != null && !moves.isEmpty()) channel.publish(player.toString());
        });
    }

    /**
     * The ledger lines of a write, one after the other.
     *
     * <p>Consecutive changes for the same reason by the same initiator are one
     * line: an autosell flush is one sale, not a hundred.
     */
    private CompletableFuture<Void> writeLedger(UUID player, List<Move> moves) {
        CurrencyFile.Contents current = contents;
        if (moves.isEmpty() || current == null || !current.ledger()) return DONE;
        List<LedgerRow> lines = new ArrayList<>();
        Move open = null;
        BigDecimal delta = BigDecimal.ZERO;
        for (Move move : moves) {
            if (open != null && !(open.transaction().reason().equals(move.transaction().reason())
                    && Objects.equals(open.transaction().initiator(), move.transaction().initiator()))) {
                lines.add(line(player, open, delta));
                delta = BigDecimal.ZERO;
            }
            delta = delta.add(move.delta());
            open = move;
        }
        if (open != null) lines.add(line(player, open, delta));
        CompletableFuture<Void> step = DONE;
        for (LedgerRow line : lines) {
            if (line.delta().signum() == 0) continue;
            step = step.thenCompose(ignored -> ledger.insert(line).thenApply(id -> null));
        }
        return step;
    }

    private LedgerRow line(UUID player, Move last, BigDecimal delta) {
        return new LedgerRow(player, key(last.currency()), delta, last.after(), last.transaction().reason(),
                last.transaction().initiator(), server);
    }

    /**
     * The id a currency's balances are stored under.
     *
     * <p>A networked currency is one balance across the network, so its rows
     * are keyed by its id alone and the servers hand ownership of them over.
     * One that is not belongs to the server it was earned on: its rows are
     * keyed {@code id@server}, which is a different row, a different
     * leaderboard and a different queue on every server sharing the database.
     */
    private String key(StoredCurrency currency) {
        return currency.settings().networked() ? currency.id() : currency.id() + "@" + server;
    }

    /**
     * The stored currency a queued row is for, or {@code null} when the row is
     * not this server's to take.
     *
     * <p>A row queued for a currency kept per server carries the server it
     * belongs to; nobody else claims it. A row with no scope is claimed by
     * whoever reads it, which is what a networked currency means and what the
     * rows queued before a currency was made local need to stop existing.
     */
    private @Nullable StoredCurrency pendingCurrency(String key) {
        int scope = key.indexOf('@');
        if (scope < 0) return stored.get(key);
        if (!key.substring(scope + 1).equals(server)) return null;
        return stored.get(key.substring(0, scope));
    }

    /** Runs database work once the player's earlier work has landed. */
    private void chain(UUID player, Supplier<CompletableFuture<Void>> work) {
        chains.compute(player, (id, previous) -> {
            CompletableFuture<Void> start = previous == null ? DONE : previous;
            return start.handle((ignored, failure) -> null).thenCompose(ignored -> work.get())
                    .exceptionally(failure -> {
                        debug.error("Economy: could not write a balance of " + id, failure);
                        return null;
                    });
        });
    }

    /** Completes after a number of ticks, or at once when the plugin stops first. */
    private CompletableFuture<Void> later(long ticks) {
        CompletableFuture<Void> wait = new CompletableFuture<>();
        if (stopping) return DONE;
        waits.add(wait);
        wait.whenComplete((ignored, failure) -> waits.remove(wait));
        tasks.runAsyncLater(ticks, () -> wait.complete(null));
        return wait;
    }

    /** A change for somebody this server does not hold: queue it and say so. */
    void queue(StoredCurrency currency, UUID player, BigDecimal amount, boolean absolute,
               Transaction transaction) {
        offline.remove(BalanceRow.id(player, key(currency)));
        pending.insert(new PendingRow(player, key(currency), amount, absolute, transaction.reason(),
                transaction.initiator())).thenAccept(id -> {
            if (isHere(player)) {
                // Still being read here: folded in right after the load, not at the next sweep.
                chain(player, () -> claimPending(player));
            } else if (channel != null) {
                channel.publish(player.toString());
            }
        });
    }

    private CompletableFuture<Void> insertPending(PendingRow row) {
        return pending.insert(row).thenApply(id -> null);
    }

    private String name(UUID player) {
        String held = names.get(player);
        return held != null ? held : ExyliaPlayers.nameOr(player, "");
    }

    private boolean here(UUID player, long session) {
        Long current = sessions.get(player);
        return current != null && current == session;
    }

    /**
     * The last snapshot of a player who is not held here.
     *
     * <p>Kept for half a minute, because plugins that poll offline balances —
     * a Vault leaderboard, a hologram — ask far more often than they change;
     * a message from another server about the player forgets it. The first
     * read of somebody never seen is zero while the row is fetched: reading
     * the database in line here would block whatever thread asked, which is
     * usually the one running the game.
     */
    BigDecimal snapshot(StoredCurrency currency, UUID player) {
        String id = BalanceRow.id(player, key(currency));
        Snapshot known = offline.get(id);
        long now = System.currentTimeMillis();
        if (known == null || now - known.at() > OFFLINE_CACHE_MILLIS) {
            // Marked fresh before the read, so a scoreboard asking every tick fetches once.
            offline.put(id, new Snapshot(now, known == null ? BigDecimal.ZERO : known.amount()));
            snapshotLater(currency, player);
        }
        return known == null ? BigDecimal.ZERO : known.amount();
    }

    /**
     * The same snapshot, waited for.
     *
     * <p>What a command asking about somebody who is not here needs: the row
     * itself rather than the zero that stands in until the warm-up lands.
     * Completes on the database's thread.
     */
    CompletableFuture<BigDecimal> snapshotLater(StoredCurrency currency, UUID player) {
        String id = BalanceRow.id(player, key(currency));
        return balances.find(id).thenApply(found -> {
            BigDecimal amount = found.map(BalanceRow::amount).orElse(BigDecimal.ZERO);
            offline.put(id, new Snapshot(System.currentTimeMillis(), amount));
            Economy.remember(currency.id(), player, amount);
            return amount;
        });
    }

    /**
     * Deletes ledger lines past the retention set in {@code /economyadmin}.
     *
     * <p>A day at a time by {@code created_day}, one statement per day. Lines
     * written before that column existed have none, and go one by one from
     * the oldest, once.
     */
    private void purgeLedger() {
        int days = store.settings().keptLedgerDays();
        if (days <= 0) return;
        long cutoff = System.currentTimeMillis() / LedgerRow.DAY_MILLIS - days;
        ledger.all().orderBy("id").limit(PURGE_PAGE).find().thenCompose(oldest -> {
            CompletableFuture<Void> step = DONE;
            long firstDay = 0L;
            int legacy = 0;
            for (LedgerRow row : oldest) {
                if (row.createdDay() > 0) {
                    firstDay = row.createdDay();
                    break;
                }
                if (row.createdAt() / LedgerRow.DAY_MILLIS >= cutoff) break;
                legacy++;
                step = step.thenCompose(ignored -> ledger.delete(row.id()).thenApply(deleted -> null));
            }
            for (long day = Math.max(firstDay, cutoff - MAX_PURGE_DAYS); firstDay > 0 && day < cutoff; day++) {
                long expired = day;
                step = step.thenCompose(ignored -> ledger.where("created_day", expired).delete().thenApply(n -> null));
            }
            return legacy == PURGE_PAGE ? step.thenRun(this::purgeLedger) : step;
        }).exceptionally(failure -> {
            debug.error("Economy: could not delete old ledger lines.", failure);
            return null;
        });
    }

    // ------------------------------------------------------------- questions

    /**
     * A player's most recent lines in a stored currency's ledger, newest
     * first.
     *
     * <p>Empty for a currency that keeps no ledger — Vault, PlayerPoints, an
     * item — and completes off the server thread.
     */
    public static @NotNull CompletableFuture<List<LedgerEntry>> history(String id, UUID player, int limit) {
        StoredEconomy economy = instance;
        StoredCurrency currency = economy == null ? null : economy.currency(id);
        if (currency == null) return CompletableFuture.completedFuture(List.of());
        return economy.ledger.where("player", player.toString()).where("currency", economy.key(currency))
                // By key, not by time: two lines written in the same millisecond
                // have one order in the table and none by their timestamp.
                .orderByDescending("id").limit(Math.max(1, Math.min(200, limit))).find()
                .thenApply(rows -> rows.stream().map(row -> row.entry(currency.id())).toList());
    }

    /**
     * The richest players in a stored currency, richest first.
     *
     * <p>Cached for a minute, because a leaderboard on a scoreboard is read
     * every tick, and refreshed in the background while the old entries keep
     * being served. Empty for a currency this plugin does not store.
     */
    public static @NotNull List<TopEntry> top(String id, int limit) {
        StoredEconomy economy = instance;
        if (economy == null) return List.of();
        StoredCurrency currency = economy.currency(id);
        if (currency == null || !currency.settings().leaderboard()) return List.of();
        String key = economy.key(currency);
        CachedTop cached = economy.tops.get(key);
        long now = System.currentTimeMillis();
        if (cached == null || now - cached.at() > TOP_CACHE_MILLIS) {
            // Refreshed in the background; whoever asked gets what was there.
            economy.tops.put(key, new CachedTop(now, cached == null ? List.of() : cached.entries()));
            economy.balances.where("currency", key).orderByDescending("amount").limit(100).find()
                    .thenAccept(rows -> {
                        List<TopEntry> entries = new ArrayList<>(rows.size());
                        int position = 1;
                        for (BalanceRow row : rows) {
                            entries.add(new TopEntry(position++, row.uuid(),
                                    ExyliaPlayers.nameOr(row.uuid(), row.name()), row.amount()));
                        }
                        economy.tops.put(key, new CachedTop(System.currentTimeMillis(), entries));
                    });
            if (cached == null) return List.of();
        }
        List<TopEntry> entries = cached == null ? List.of() : cached.entries();
        return entries.subList(0, Math.min(Math.max(0, limit), entries.size()));
    }

    /**
     * Swaps an amount of one currency for another at the configured rate.
     *
     * <p>The rate is the one set on the source currency for the target. What is
     * received is the typed amount, cut to the source's decimals, times the
     * rate, cut to the target's decimals. Only what that received amount costs
     * is withdrawn — received divided by the rate, rounded up to the source's
     * decimals and never more than was typed — so the part the target's
     * decimals round away stays with the player. The source is withdrawn first,
     * the target deposited second, and a failed deposit refunds the source —
     * the same order a transfer between players uses.
     *
     * @return the outcome, with the amount of {@code to} received as its amount,
     *         and the amount of {@code from} withdrawn
     */
    public static @NotNull Exchange exchange(UUID player, String fromId, String toId, BigDecimal typed) {
        StoredEconomy economy = instance;
        if (economy == null) return Exchange.refused(EconomyResponse.notAvailable());
        StoredCurrency from = economy.currency(fromId);
        if (from == null || !from.settings().exchangeable()) {
            return Exchange.refused(EconomyResponse.failure(EconomyMessages.get().exchangeNotAllowed()));
        }
        String to = toId.toLowerCase(Locale.ROOT);
        BigDecimal rate = from.settings().rates().get(to);
        if (rate == null || Economy.kind(to) == CurrencyKind.UNKNOWN) {
            return Exchange.refused(EconomyResponse.failure(Values.of("from", from.id()).put("to", toId)
                    .apply(EconomyMessages.get().exchangeNoRate())));
        }
        BigDecimal typedAmount = from.info().scale(typed);
        if (typedAmount.signum() <= 0) return Exchange.refused(EconomyResponse.invalidAmount());
        BigDecimal received = Economy.info(to).scale(typedAmount.multiply(rate));
        if (received.signum() <= 0) return Exchange.refused(EconomyResponse.invalidAmount());
        BigDecimal amount = received.divide(rate, from.info().scaleDigits(), RoundingMode.UP).min(typedAmount);

        Transaction transaction = Transaction.of("exchange:" + from.id() + ">" + to).by(player);
        EconomyResponse taken = Economy.of(from.id()).withdraw(player, amount, transaction);
        if (!taken.isSuccess()) return Exchange.refused(taken);
        EconomyResponse given = Economy.of(to).deposit(player, received, transaction);
        if (given.isSuccess() && given.amount().compareTo(received) < 0
                && Economy.of(to).withdraw(player, given.amount(), Transaction.of("exchange:refund").by(player)).isSuccess()) {
            // A ceiling let only part of it in: undone rather than paid for in full.
            given = EconomyResponse.failure(Values.of("currency", Economy.info(to).namePlural())
                    .apply(EconomyMessages.get().exchangeOverCeiling()));
        }
        if (!given.isSuccess()) {
            Economy.of(from.id()).deposit(player, amount, Transaction.of("exchange:refund").by(player));
            return Exchange.refused(given);
        }
        return new Exchange(EconomyResponse.success(received, given.balance()), amount);
    }

    /**
     * How an exchange ended.
     *
     * @param response the outcome, with the amount received as its amount
     * @param taken    what was withdrawn from the source currency; zero when refused
     */
    public record Exchange(@NotNull EconomyResponse response, @NotNull BigDecimal taken) {

        static Exchange refused(EconomyResponse response) {
            return new Exchange(response, BigDecimal.ZERO);
        }
    }

    /**
     * Adds every listed player's balance in one currency to a stored one.
     *
     * <p>Read one player at a time through {@code balanceLater}. A player held
     * here is paid at once; everybody else gets a pending row without a
     * message each, folded in on their next load or within a minute on the
     * server they are on.
     *
     * @return how many balances were imported, completing off the server thread
     */
    public static @NotNull CompletableFuture<Integer> importBalances(@NotNull String from, @NotNull String into,
                                                                     @NotNull List<UUID> players,
                                                                     @Nullable UUID initiator) {
        StoredEconomy economy = instance;
        StoredCurrency target = economy == null ? null : economy.currency(into);
        if (target == null) return CompletableFuture.completedFuture(0);
        Transaction transaction = Transaction.of("import:" + from).by(initiator);
        AtomicInteger count = new AtomicInteger();
        CompletableFuture<Void> step = DONE;
        for (UUID player : players) {
            step = step.thenCompose(ignored -> Economy.of(from).balanceLater(player).thenCompose(balance -> {
                BigDecimal amount = target.info().scale(balance);
                if (amount.signum() <= 0) return DONE;
                count.incrementAndGet();
                if (target.isLoaded(player)) {
                    target.deposit(player, amount, transaction);
                    return DONE;
                }
                return economy.insertPending(new PendingRow(player, economy.key(target), amount, false,
                        transaction.reason(), initiator));
            }));
        }
        return step.thenApply(ignored -> count.get());
    }

    private void safely(String what, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException | LinkageError failure) {
            logger.warning("Economy: could not " + what + ": " + failure);
        }
    }
}
