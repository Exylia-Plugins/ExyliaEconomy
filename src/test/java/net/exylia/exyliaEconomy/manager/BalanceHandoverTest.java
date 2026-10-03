package net.exylia.exyliaEconomy.manager;

import net.exylia.exyliaEconomy.database.BalanceRow;
import net.exylia.exyliaEconomy.database.PendingRow;
import net.exylia.exyliaEconomy.testing.TestServer;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.MemoryDatabase;
import net.exylia.lib.economy.CurrencyInfo;
import net.exylia.lib.economy.Economy;
import net.exylia.lib.economy.EconomyResponse;
import net.exylia.lib.economy.Transaction;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two servers on one database passing a player's balance between them.
 *
 * <p>Each server is a {@link StoredEconomy} of its own, driven through the
 * join and quit paths the listener calls; only the database is shared.
 */
class BalanceHandoverTest {

    private static final BigDecimal START = new BigDecimal("100");
    private static final Duration WAIT = Duration.ofSeconds(30);

    private static Plugin first;
    private static Plugin second;

    @BeforeAll
    static void servers() {
        TestServer.install();
        first = TestServer.plugin("EconomyServerA");
        second = TestServer.plugin("EconomyServerB");
        MemoryDatabase.install(first, "economy-handover");
        MemoryDatabase.install(second, "economy-handover");
    }

    /** One server's economy, holding the given currencies. */
    private record Server(StoredEconomy economy, Map<String, StoredCurrency> currencies) {

        StoredCurrency currency(String id) {
            return currencies.get(id);
        }

        void join(UUID player) throws Exception {
            Map<UUID, Long> sessions = TestServer.get(economy, "sessions");
            AtomicLong next = TestServer.get(economy, "nextSession");
            sessions.put(player, next.incrementAndGet());
            TestServer.call(economy, "load", player);
        }

        void leave(UUID player) throws Exception {
            TestServer.call(economy, "leave", player);
        }

        /** The minute sweep, which folds in what the network message would have announced. */
        void sweep() throws Exception {
            TestServer.call(economy, "sweep");
        }
    }

    private static Server server(Plugin plugin, String id, CurrencyFile.Stored... settings) throws Exception {
        Constructor<StoredEconomy> constructor = StoredEconomy.class.getDeclaredConstructor(Plugin.class, Runnable.class);
        constructor.setAccessible(true);
        StoredEconomy economy = constructor.newInstance(plugin, (Runnable) () -> { });
        TestServer.set(economy, "server", id);
        Map<String, StoredCurrency> currencies = new LinkedHashMap<>();
        for (CurrencyFile.Stored stored : settings) currencies.put(stored.id(), new StoredCurrency(stored, economy));
        TestServer.set(economy, "stored", Map.copyOf(currencies));
        TestServer.set(economy, "ready", true);
        return new Server(economy, currencies);
    }

    private static CurrencyFile.Stored currency(String id, int decimals, BigDecimal max, Map<String, BigDecimal> rates) {
        CurrencyInfo plain = CurrencyInfo.of(id, id, id, "$");
        CurrencyInfo info = new CurrencyInfo(plain.id(), plain.name(), plain.namePlural(), plain.symbol(), plain.icon(),
                decimals, plain.format(), plain.compactFormat());
        return new CurrencyFile.Stored(id, info, List.of(), START, max, "", true, BigDecimal.ZERO, 0, true, rates,
                true, true, true);
    }

    /** The same currency, kept per server instead of across the network. */
    private static CurrencyFile.Stored local(String id) {
        CurrencyFile.Stored networked = currency(id, 0, BigDecimal.ZERO, Map.of());
        return new CurrencyFile.Stored(networked.id(), networked.info(), networked.aliases(), networked.start(),
                networked.max(), networked.permission(), networked.transferable(), networked.minimumTransfer(),
                networked.transferTaxPercent(), networked.exchangeable(), networked.rates(), networked.leaderboard(),
                false, networked.commands());
    }

    private static CurrencyFile.Stored dollars() {
        return currency("dollars", 2, BigDecimal.ZERO, Map.of());
    }

    private static Optional<BalanceRow> row(UUID player, String currency) {
        return Databases.of(first).repository(BalanceRow.class).find(BalanceRow.id(player, currency)).join();
    }

    private static int pending(UUID player) {
        return Databases.of(first).repository(PendingRow.class).where("player", player.toString()).find().join().size();
    }

    @Test
    @DisplayName("a currency that is not networked keeps one balance per server")
    void perServerCurrency() throws Exception {
        CurrencyFile.Stored coins = local("coins");
        Server a = server(first, "A", coins);
        Server b = server(second, "B", coins);
        UUID player = UUID.randomUUID();
        a.join(player);
        TestServer.await("A holds its own balance", WAIT, () -> a.currency("coins").isLoaded(player));
        a.currency("coins").deposit(player, new BigDecimal("50"), Transaction.of("test"));
        // Paid on B while they stand on A: B's own balance, never A's.
        b.currency("coins").deposit(player, new BigDecimal("7"), Transaction.of("test"));
        a.leave(player);
        TestServer.await("A wrote its row", WAIT, () -> row(player, "coins@A")
                .filter(stored -> stored.amount().compareTo(new BigDecimal("150")) == 0).isPresent());

        b.join(player);
        BigDecimal onB = new BigDecimal("107");
        TestServer.await("B holds a balance of its own", WAIT, () -> {
            try {
                b.sweep();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            return b.currency("coins").isLoaded(player)
                    && b.currency("coins").balance(player).compareTo(onB) == 0
                    && pending(player) == 0;
        });
        b.leave(player);
        TestServer.await("B wrote its row", WAIT, () -> row(player, "coins@B")
                .filter(stored -> stored.amount().compareTo(onB) == 0).isPresent());
        assertEquals(0, new BigDecimal("150").compareTo(row(player, "coins@A").orElseThrow().amount()),
                "B changed the balance A keeps");
        assertTrue(row(player, "coins").isEmpty(), "a shared row for a currency kept per server");
    }

    @Test
    @DisplayName("a player hopping servers while both pay them ends with every deposit, once")
    void hopWhileBothPay() throws Exception {
        Server a = server(first, "A", dollars());
        Server b = server(second, "B", dollars());
        UUID player = UUID.randomUUID();
        a.join(player);
        TestServer.await("A holds the balance", WAIT, () -> a.currency("dollars").isLoaded(player));

        int each = 150;
        ExecutorService payers = Executors.newFixedThreadPool(4);
        List<Future<?>> paying = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            StoredCurrency payer = (i % 2 == 0 ? a : b).currency("dollars");
            paying.add(payers.submit(() -> {
                for (int n = 0; n < each; n++) {
                    payer.deposit(player, BigDecimal.ONE, Transaction.of("test"));
                    if (n % 10 == 0) Thread.sleep(1L);
                }
                return null;
            }));
        }
        Thread.sleep(30L);
        a.leave(player);
        b.join(player);
        for (Future<?> job : paying) job.get(60, TimeUnit.SECONDS);
        payers.shutdown();

        BigDecimal expected = START.add(BigDecimal.valueOf(4L * each));
        TestServer.await("B holds every deposit", WAIT, () -> {
            try {
                b.sweep();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            return b.currency("dollars").isLoaded(player)
                    && b.currency("dollars").balance(player).compareTo(expected) == 0
                    && pending(player) == 0;
        });
        b.leave(player);
        TestServer.await("the row is written and let go", WAIT, () -> row(player, "dollars")
                .filter(stored -> stored.amount().compareTo(expected) == 0 && stored.owner().isEmpty()).isPresent());
    }

    @Test
    @DisplayName("a new player who quits before their balance loads leaves it free for the next server")
    void quitBeforeLoad() throws Exception {
        Server a = server(first, "A", dollars());
        Server b = server(second, "B", dollars());
        UUID player = UUID.randomUUID();
        a.join(player);
        a.leave(player);
        TestServer.await("the row is created", WAIT, () -> row(player, "dollars").isPresent());
        Thread.sleep(300L);

        long started = System.currentTimeMillis();
        b.join(player);
        TestServer.await("B holds the balance", WAIT, () -> b.currency("dollars").isLoaded(player));
        long took = System.currentTimeMillis() - started;
        assertTrue(took < 3_000L, "B waited " + took + "ms for a server that had already let go");
        assertEquals(0, START.compareTo(b.currency("dollars").balance(player)));
        b.leave(player);
    }

    @Test
    @DisplayName("an exchange a ceiling would cut short takes nothing")
    void exchangeOverCeiling() throws Exception {
        Server x = server(first, "X",
                currency("cash", 2, new BigDecimal("1000"), Map.of()),
                currency("gems", 0, BigDecimal.ZERO, Map.of("cash", new BigDecimal("10"))));
        StoredCurrency cash = x.currency("cash");
        StoredCurrency gems = x.currency("gems");
        Economy.register(cash);
        Economy.register(gems);
        TestServer.set(StoredEconomy.class, "instance", x.economy());
        try {
            UUID player = UUID.randomUUID();
            x.join(player);
            TestServer.await("the balances load", WAIT, () -> cash.isLoaded(player) && gems.isLoaded(player));
            cash.set(player, new BigDecimal("990"), Transaction.NONE);

            EconomyResponse response = StoredEconomy.exchange(player, "gems", "cash", new BigDecimal("5")).response();

            assertFalse(response.isSuccess(), String.valueOf(response));
            assertEquals(0, START.compareTo(gems.balance(player)), "gems taken for cash that never arrived");
            assertEquals(0, new BigDecimal("990").compareTo(cash.balance(player)));
        } finally {
            TestServer.set(StoredEconomy.class, "instance", null);
            Economy.unregister("cash");
            Economy.unregister("gems");
        }
    }

    @Test
    @DisplayName("an exchange takes only what the received amount costs, not what the target's decimals round away")
    void exchangeKeepsTheRoundedAwayPart() throws Exception {
        Server x = server(first, "Y",
                currency("cash", 2, BigDecimal.ZERO, Map.of("gems", new BigDecimal("0.01"))),
                currency("gems", 0, BigDecimal.ZERO, Map.of()));
        StoredCurrency cash = x.currency("cash");
        StoredCurrency gems = x.currency("gems");
        Economy.register(cash);
        Economy.register(gems);
        TestServer.set(StoredEconomy.class, "instance", x.economy());
        try {
            UUID player = UUID.randomUUID();
            x.join(player);
            TestServer.await("the balances load", WAIT, () -> cash.isLoaded(player) && gems.isLoaded(player));
            cash.set(player, new BigDecimal("150.55"), Transaction.NONE);

            StoredEconomy.Exchange exchange = StoredEconomy.exchange(player, "cash", "gems", new BigDecimal("150.55"));

            assertTrue(exchange.response().isSuccess(), String.valueOf(exchange.response()));
            assertEquals(0, BigDecimal.ONE.compareTo(exchange.response().amount()));
            assertEquals(0, new BigDecimal("100").compareTo(exchange.taken()), "reported as taken");
            assertEquals(0, new BigDecimal("50.55").compareTo(cash.balance(player)), "cash lost to rounding");
            assertEquals(0, START.add(BigDecimal.ONE).compareTo(gems.balance(player)));
        } finally {
            TestServer.set(StoredEconomy.class, "instance", null);
            Economy.unregister("cash");
            Economy.unregister("gems");
        }
    }
}
