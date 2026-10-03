package net.exylia.exyliaEconomy.manager;

import net.exylia.exyliaEconomy.database.BalanceRow;
import net.exylia.exyliaEconomy.database.ImportedRow;
import net.exylia.exyliaEconomy.database.PendingRow;
import net.exylia.exyliaEconomy.testing.TestServer;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.FlakyRepository;
import net.exylia.lib.database.MemoryDatabase;
import net.exylia.lib.database.Repository;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a database that drops, or half-drops, a write does to a balance: nothing.
 *
 * <p>One server on an in-memory database, with {@link FlakyRepository} put in
 * front of the table under test.
 */
class MoneySafetyTest {

    private static final BigDecimal START = new BigDecimal("100");
    private static final Duration WAIT = Duration.ofSeconds(30);

    private static Plugin plugin;

    @BeforeAll
    static void server() {
        TestServer.install();
        plugin = TestServer.plugin("EconomySafety");
        MemoryDatabase.install(plugin, "economy-safety");
    }

    private static StoredEconomy economy(CurrencyFile.Stored... settings) throws Exception {
        Constructor<StoredEconomy> constructor = StoredEconomy.class.getDeclaredConstructor(Plugin.class, Runnable.class);
        constructor.setAccessible(true);
        StoredEconomy economy = constructor.newInstance(plugin, (Runnable) () -> { });
        TestServer.set(economy, "server", "S");
        Map<String, StoredCurrency> currencies = new java.util.LinkedHashMap<>();
        for (CurrencyFile.Stored stored : settings) currencies.put(stored.id(), new StoredCurrency(stored, economy));
        TestServer.set(economy, "stored", Map.copyOf(currencies));
        TestServer.set(economy, "ready", true);
        return economy;
    }

    private static CurrencyFile.Stored currency(String id, BigDecimal max) {
        CurrencyInfo plain = CurrencyInfo.of(id, id, id, "$");
        CurrencyInfo info = new CurrencyInfo(plain.id(), plain.name(), plain.namePlural(), plain.symbol(), plain.icon(),
                2, plain.format(), plain.compactFormat());
        return new CurrencyFile.Stored(id, info, List.of(), START, max, "", true, BigDecimal.ZERO, 0, true, Map.of(),
                true, true, true);
    }

    private static void join(StoredEconomy economy, UUID player) throws Exception {
        Map<UUID, Long> sessions = TestServer.get(economy, "sessions");
        AtomicLong next = TestServer.get(economy, "nextSession");
        sessions.put(player, next.incrementAndGet());
        TestServer.call(economy, "load", player);
    }

    private static Optional<BalanceRow> row(UUID player, String currency) {
        return Databases.of(plugin).repository(BalanceRow.class).find(BalanceRow.id(player, currency)).join();
    }

    private static boolean holds(UUID player, String currency, String amount) {
        return row(player, currency).filter(stored -> stored.amount().compareTo(new BigDecimal(amount)) == 0).isPresent();
    }

    private static List<PendingRow> pending(UUID player) {
        return Databases.of(plugin).repository(PendingRow.class).where("player", player.toString()).find().join();
    }

    /** Puts a failing repository in front of one of the economy's tables. */
    private static <T> void flaky(StoredEconomy economy, String field, String method, boolean commit,
                                  AtomicInteger failures) throws Exception {
        Repository<T> real = TestServer.get(economy, field);
        TestServer.set(economy, field, FlakyRepository.failing(real, method, commit, failures));
    }

    private static StoredCurrency loaded(StoredEconomy economy, String id, UUID player) throws Exception {
        StoredCurrency currency = economy.currency(id);
        join(economy, player);
        TestServer.await("the balance loads", WAIT, () -> currency.isLoaded(player));
        return currency;
    }

    // ------------------------------------------------------------- writes

    @Test
    @DisplayName("a balance write the database refuses is tried again until it lands")
    void refusedWriteLands() throws Exception {
        StoredEconomy economy = economy(currency("w1", BigDecimal.ZERO));
        UUID player = UUID.randomUUID();
        StoredCurrency dollars = loaded(economy, "w1", player);
        AtomicInteger failures = new AtomicInteger(2);
        flaky(economy, "balances", "updateIf", false, failures);

        dollars.deposit(player, new BigDecimal("50"), Transaction.of("test"));

        TestServer.await("the deposit is written", WAIT, () -> holds(player, "w1", "150"));
        assertTrue(dollars.isLoaded(player), "a refused write was taken for another server's");
        assertEquals(0, new BigDecimal("150").compareTo(dollars.balance(player)));
        assertTrue(pending(player).isEmpty());
    }

    @Test
    @DisplayName("a write that commits but reports failure is not applied twice")
    void committedFailureIsNotDoubled() throws Exception {
        StoredEconomy economy = economy(currency("w2", BigDecimal.ZERO));
        UUID player = UUID.randomUUID();
        StoredCurrency dollars = loaded(economy, "w2", player);
        flaky(economy, "balances", "updateIf", true, new AtomicInteger(1));

        dollars.deposit(player, new BigDecimal("50"), Transaction.of("test"));
        TestServer.await("the first deposit is written", WAIT, () -> holds(player, "w2", "150"));
        dollars.deposit(player, new BigDecimal("25"), Transaction.of("test"));
        TestServer.await("the second deposit is written", WAIT, () -> holds(player, "w2", "175"));
        Thread.sleep(1_500L);

        assertTrue(dollars.isLoaded(player), "its own committed write was taken for another server's");
        assertEquals(0, new BigDecimal("175").compareTo(dollars.balance(player)));
        assertTrue(pending(player).isEmpty(), "the changes were queued again on top of the written balance");
        assertTrue(holds(player, "w2", "175"));
    }

    @Test
    @DisplayName("a player who quits while their write is failing leaves with what they had")
    void quitAfterFailedWrite() throws Exception {
        StoredEconomy economy = economy(currency("w3", BigDecimal.ZERO));
        UUID player = UUID.randomUUID();
        StoredCurrency dollars = loaded(economy, "w3", player);
        flaky(economy, "balances", "updateIf", false, new AtomicInteger(3));

        dollars.deposit(player, new BigDecimal("50"), Transaction.of("test"));
        TestServer.call(economy, "leave", player);

        TestServer.await("the balance is written and let go", WAIT, () -> row(player, "w3")
                .filter(stored -> stored.amount().compareTo(new BigDecimal("150")) == 0 && stored.owner().isEmpty())
                .isPresent());
    }

    @Test
    @DisplayName("a withdrawal is written at once; a deposit waits to be gathered")
    void withdrawalSkipsTheWait() throws Exception {
        StoredEconomy economy = economy(currency("w4", BigDecimal.ZERO));
        UUID player = UUID.randomUUID();
        StoredCurrency dollars = loaded(economy, "w4", player);
        Map<UUID, Object> batches = TestServer.get(economy, "batches");

        dollars.deposit(player, BigDecimal.TEN, Transaction.of("test"));
        CompletableFuture<?> gathering = TestServer.get(batches.get(player), "due");
        assertFalse(gathering.isDone(), "a deposit was written without being gathered");

        dollars.withdraw(player, BigDecimal.ONE, Transaction.of("test"));
        CompletableFuture<?> due = TestServer.get(batches.get(player), "due");
        assertTrue(due == null || due.isDone(), "a withdrawal waited to be gathered");
        TestServer.await("the withdrawal is written", WAIT, () -> holds(player, "w4", "109"));
    }

    // ------------------------------------------------------------- queue

    @Test
    @DisplayName("a queued change the database refuses is queued again, once")
    void refusedQueueLands() throws Exception {
        StoredEconomy economy = economy(currency("q1", BigDecimal.ZERO));
        UUID away = UUID.randomUUID();
        flaky(economy, "pending", "insert", false, new AtomicInteger(1));

        assertTrue(economy.currency("q1").deposit(away, new BigDecimal("40"), Transaction.of("pay")).isSuccess());

        TestServer.await("the change is queued", WAIT, () -> pending(away).size() == 1);
        assertEquals(0, new BigDecimal("40").compareTo(pending(away).get(0).amount()));
    }

    @Test
    @DisplayName("a queued change that committed but reported failure is not queued twice")
    void committedQueueIsNotDoubled() throws Exception {
        StoredEconomy economy = economy(currency("q2", BigDecimal.ZERO));
        UUID away = UUID.randomUUID();
        flaky(economy, "pending", "insert", true, new AtomicInteger(1));

        economy.currency("q2").deposit(away, new BigDecimal("40"), Transaction.of("pay"));
        TestServer.await("the change is queued", WAIT, () -> pending(away).size() == 1);
        Thread.sleep(2_500L);

        assertEquals(1, pending(away).size(), "the retry queued the change a second time");
    }

    // ----------------------------------------------------------- ceilings

    @Test
    @DisplayName("a deposit over the ceiling is refused whole, never cut to fit")
    void depositOverCeilingIsRefused() throws Exception {
        StoredEconomy economy = economy(currency("c1", new BigDecimal("1000")));
        UUID player = UUID.randomUUID();
        StoredCurrency capped = loaded(economy, "c1", player);
        capped.set(player, new BigDecimal("990"), Transaction.NONE);

        EconomyResponse response = capped.deposit(player, new BigDecimal("20"), Transaction.of("test"));

        assertFalse(response.isSuccess(), String.valueOf(response));
        assertEquals(0, new BigDecimal("990").compareTo(capped.balance(player)));
    }

    @Test
    @DisplayName("no operation stores a number past the hard limit, here or queued")
    void hardLimit() throws Exception {
        StoredEconomy economy = economy(currency("c2", BigDecimal.ZERO));
        UUID player = UUID.randomUUID();
        UUID away = UUID.randomUUID();
        StoredCurrency open = loaded(economy, "c2", player);
        BigDecimal huge = StoredCurrency.LIMIT.multiply(BigDecimal.TEN);

        assertFalse(open.deposit(player, huge, Transaction.NONE).isSuccess());
        assertFalse(open.set(player, huge, Transaction.NONE).isSuccess());
        assertFalse(open.deposit(away, huge, Transaction.NONE).isSuccess());
        assertFalse(open.withdraw(away, huge, Transaction.NONE).isSuccess());
        open.set(player, StoredCurrency.LIMIT, Transaction.NONE);
        assertFalse(open.deposit(player, new BigDecimal("0.01"), Transaction.NONE).isSuccess());

        assertEquals(0, StoredCurrency.LIMIT.compareTo(open.balance(player)));
        Thread.sleep(200L);
        assertTrue(pending(away).isEmpty(), "a change past the limit was queued");
    }

    @Test
    @DisplayName("Vault's doubles are rounded to the currency, not cut down")
    void vaultRounding() {
        CurrencyInfo cents = new CurrencyInfo("v", "v", "v", "$", "", 2, "", "");
        assertEquals(new BigDecimal("6.90"), VaultBridge.money(cents, 2.3 * 3));
        assertEquals(new BigDecimal("0.30"), VaultBridge.money(cents, 0.1 + 0.2));
        assertNull(VaultBridge.money(cents, Double.NaN));
        assertNull(VaultBridge.money(cents, -1D));
    }

    // ------------------------------------------------------------- import

    @Test
    @DisplayName("an import run twice pays every player once")
    void importIsOncePerPlayer() throws Exception {
        StoredEconomy economy = economy(currency("isrc", BigDecimal.ZERO), currency("idst", BigDecimal.ZERO));
        StoredCurrency source = economy.currency("isrc");
        StoredCurrency target = economy.currency("idst");
        Economy.register(source);
        Economy.register(target);
        TestServer.set(StoredEconomy.class, "instance", economy);
        try {
            UUID here = UUID.randomUUID();
            UUID away = UUID.randomUUID();
            loaded(economy, "isrc", here);
            TestServer.await("both balances load", WAIT, () -> target.isLoaded(here));
            join(economy, away);
            TestServer.await("the other player loads", WAIT, () -> target.isLoaded(away));
            TestServer.call(economy, "leave", away);
            TestServer.await("their rows are let go", WAIT, () -> row(away, "isrc")
                    .filter(stored -> stored.owner().isEmpty()).isPresent());

            assertEquals(2, StoredEconomy.importBalances("isrc", "idst", List.of(here, away), null).join());
            assertEquals(0, StoredEconomy.importBalances("isrc", "idst", List.of(here, away), null).join());

            assertEquals(0, new BigDecimal("200").compareTo(target.balance(here)), "imported twice");
            assertEquals(1, pending(away).size(), "queued twice");
        } finally {
            TestServer.set(StoredEconomy.class, "instance", null);
            Economy.unregister("isrc");
            Economy.unregister("idst");
        }
    }

    // --------------------------------------------------- unanswered writes

    /** Puts a player's row where another server would leave it, versions ahead of this one. */
    private static void movedOn(UUID player, String currency, long ahead) {
        Repository<BalanceRow> rows = Databases.of(plugin).repository(BalanceRow.class);
        BalanceRow now = row(player, currency).orElseThrow();
        rows.save(new BalanceRow(player, "", currency, new BigDecimal("77"), "B", now.version() + ahead)).join();
    }

    @Test
    @DisplayName("an unanswered write the next owner built on is logged, not queued twice")
    void unansweredWriteBuiltOnIsNotRequeued() throws Exception {
        StoredEconomy economy = economy(currency("u1", BigDecimal.ZERO));
        UUID player = UUID.randomUUID();
        StoredCurrency dollars = loaded(economy, "u1", player);
        movedOn(player, "u1", 2);
        flaky(economy, "balances", "updateIf", false, new AtomicInteger(1));

        dollars.withdraw(player, BigDecimal.ONE, Transaction.of("test"));

        TestServer.await("the balance is let go", WAIT, () -> !dollars.isLoaded(player));
        Thread.sleep(500L);
        assertTrue(pending(player).isEmpty(), "a change that may have landed was queued again");
        assertTrue(holds(player, "u1", "77"));
    }

    @Test
    @DisplayName("an unanswered write another server's claim beat is queued for it")
    void unansweredWriteBeatenIsRequeued() throws Exception {
        StoredEconomy economy = economy(currency("u2", BigDecimal.ZERO));
        UUID player = UUID.randomUUID();
        StoredCurrency dollars = loaded(economy, "u2", player);
        movedOn(player, "u2", 1);
        flaky(economy, "balances", "updateIf", false, new AtomicInteger(1));

        dollars.withdraw(player, BigDecimal.ONE, Transaction.of("test"));

        TestServer.await("the change is queued", WAIT, () -> pending(player).size() == 1);
        assertEquals(0, BigDecimal.ONE.negate().compareTo(pending(player).get(0).amount()));
    }

    @Test
    @DisplayName("a write the database refuses for good is not tried forever")
    void refusedForGoodIsNotRetried() throws Exception {
        StoredEconomy economy = economy(currency("u3", BigDecimal.ZERO));
        UUID player = UUID.randomUUID();
        UUID away = UUID.randomUUID();
        StoredCurrency dollars = loaded(economy, "u3", player);
        AtomicInteger writes = new AtomicInteger(1);
        AtomicInteger inserts = new AtomicInteger(1);
        Repository<BalanceRow> balances = TestServer.get(economy, "balances");
        TestServer.set(economy, "balances", FlakyRepository.failing(balances, "updateIf", false, writes,
                () -> new IllegalStateException("a statement the database rejects")));
        Repository<PendingRow> queue = TestServer.get(economy, "pending");
        TestServer.set(economy, "pending", FlakyRepository.failing(queue, "insert", false, inserts,
                () -> new IllegalStateException("a statement the database rejects")));

        dollars.withdraw(player, BigDecimal.ONE, Transaction.of("test"));
        dollars.deposit(away, BigDecimal.TEN, Transaction.of("test"));
        Thread.sleep(2_500L);

        assertTrue(holds(player, "u3", "100"), "the refused write was tried again");
        assertTrue(pending(away).isEmpty(), "the refused insert was tried again");
    }

    @Test
    @DisplayName("a queued change taken before its insert's retry is not queued again")
    void takenBeforeRetryIsNotDoubled() throws Exception {
        StoredEconomy economy = economy(currency("q3", BigDecimal.ZERO));
        UUID away = UUID.randomUUID();
        flaky(economy, "pending", "insert", true, new AtomicInteger(1));

        economy.currency("q3").deposit(away, new BigDecimal("40"), Transaction.of("pay"));
        TestServer.await("the change is queued", WAIT, () -> pending(away).size() == 1);
        PendingRow row = pending(away).get(0);
        assertTrue((Boolean) ((CompletableFuture<?>) TestServer.call(economy, "takePending", row)).join());
        Thread.sleep(2_500L);

        assertTrue(pending(away).isEmpty(), "the retry queued a change that was already taken");
    }

    // ----------------------------------------------- ceilings, queued

    @Test
    @DisplayName("a payment that does not fit under the ceiling goes back to whoever paid")
    void overflowGoesBackToPayer() throws Exception {
        StoredEconomy economy = economy(currency("c3", new BigDecimal("1000")));
        UUID payer = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        StoredCurrency capped = loaded(economy, "c3", payer);
        Databases.of(plugin).repository(BalanceRow.class)
                .save(new BalanceRow(receiver, "", "c3", new BigDecimal("990"), "", 1L)).join();
        Databases.of(plugin).repository(PendingRow.class)
                .insert(new PendingRow(receiver, "c3", new BigDecimal("50"), false, "pay", payer)).join();

        join(economy, receiver);

        TestServer.await("the payment lands", WAIT, () -> capped.isLoaded(receiver) && pending(receiver).isEmpty());
        assertEquals(0, new BigDecimal("1000").compareTo(capped.balance(receiver)));
        assertEquals(0, new BigDecimal("140").compareTo(capped.balance(payer)), "the cut part was not given back");
    }

    @Test
    @DisplayName("a deposit for somebody elsewhere is refused when their last read balance cannot take it")
    void queuedDepositOverCeilingIsRefused() throws Exception {
        StoredEconomy economy = economy(currency("c4", new BigDecimal("1000")));
        UUID away = UUID.randomUUID();
        Databases.of(plugin).repository(BalanceRow.class)
                .save(new BalanceRow(away, "", "c4", new BigDecimal("990"), "", 1L)).join();
        StoredCurrency capped = economy.currency("c4");
        economy.snapshotLater(capped, away).join();

        assertFalse(capped.deposit(away, new BigDecimal("50"), Transaction.of("pay")).isSuccess());
        Thread.sleep(200L);
        assertTrue(pending(away).isEmpty());
    }

    // ------------------------------------------------------- offline reads

    @Test
    @DisplayName("an offline read that just failed is neither waited for nor asked again at once")
    void failedOfflineReadIsRemembered() throws Exception {
        StoredEconomy economy = economy(currency("r1", BigDecimal.ZERO));
        UUID away = UUID.randomUUID();
        StoredCurrency dollars = economy.currency("r1");
        AtomicInteger failures = new AtomicInteger(2);
        flaky(economy, "balances", "find", false, failures);

        CompletableFuture<BigDecimal> read = economy.snapshotLater(dollars, away);
        TestServer.await("the read fails", WAIT, read::isDone);
        assertTrue(read.isCompletedExceptionally());

        long started = System.nanoTime();
        assertEquals(0, BigDecimal.ZERO.compareTo(dollars.balanceNow(away)));
        assertTrue(System.nanoTime() - started < 500_000_000L, "a read that just failed was waited for again");
        assertEquals(1, failures.get(), "a read that just failed was asked again");
    }

    @Test
    @DisplayName("an import started twice at once pays every player once, and a refused one can run again")
    void importClaimIsAtomic() throws Exception {
        StoredEconomy economy = economy(currency("jsrc", BigDecimal.ZERO), currency("jdst", new BigDecimal("150")));
        StoredCurrency source = economy.currency("jsrc");
        StoredCurrency target = economy.currency("jdst");
        Economy.register(source);
        Economy.register(target);
        TestServer.set(StoredEconomy.class, "instance", economy);
        try {
            UUID away = UUID.randomUUID();
            UUID full = UUID.randomUUID();
            Databases.of(plugin).repository(BalanceRow.class)
                    .save(new BalanceRow(away, "", "jsrc", new BigDecimal("30"), "", 1L)).join();
            Databases.of(plugin).repository(BalanceRow.class)
                    .save(new BalanceRow(full, "", "jsrc", new BigDecimal("100"), "", 1L)).join();
            loaded(economy, "jdst", full);
            TestServer.await("both balances load", WAIT, () -> source.isLoaded(full));

            CompletableFuture<Integer> one = StoredEconomy.importBalances("jsrc", "jdst", List.of(away, full), null);
            CompletableFuture<Integer> two = StoredEconomy.importBalances("jsrc", "jdst", List.of(away, full), null);

            assertEquals(1, one.join() + two.join(), "paid twice, or the refused one counted");
            assertEquals(1, pending(away).size(), "queued twice");
            assertFalse(Databases.of(plugin).repository(ImportedRow.class)
                    .exists(ImportedRow.id("jsrc", "jdst", full)).join(), "a refused import stayed marked as done");
        } finally {
            TestServer.set(StoredEconomy.class, "instance", null);
            Economy.unregister("jsrc");
            Economy.unregister("jdst");
        }
    }
}
