package net.exylia.exyliaEconomy.manager;

import net.exylia.exyliaEconomy.database.BalanceRow;
import net.exylia.exyliaEconomy.database.PendingRow;
import net.exylia.exyliaEconomy.testing.TestServer;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.MemoryDatabase;
import net.exylia.lib.economy.BalanceChangeEvent;
import net.exylia.lib.economy.CurrencyInfo;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What ExyliaAnalytics reads from the economy: every written change announced
 * once across the network, and each currency's money supply.
 */
class EconomyAnalyticsTest {

    private static final BigDecimal START = new BigDecimal("100");
    private static final Duration WAIT = Duration.ofSeconds(30);

    private static Plugin first;
    private static Plugin second;

    @BeforeAll
    static void servers() {
        TestServer.install();
        first = TestServer.plugin("AnalyticsServerA");
        second = TestServer.plugin("AnalyticsServerB");
        MemoryDatabase.install(first, "economy-analytics");
        MemoryDatabase.install(second, "economy-analytics");
    }

    private static StoredEconomy economy(Plugin plugin, String id, StoredCurrency[] holder) throws Exception {
        Constructor<StoredEconomy> constructor = StoredEconomy.class.getDeclaredConstructor(Plugin.class, Runnable.class);
        constructor.setAccessible(true);
        StoredEconomy economy = constructor.newInstance(plugin, (Runnable) () -> { });
        TestServer.set(economy, "server", id);
        CurrencyInfo plain = CurrencyInfo.of("dollars", "dollars", "dollars", "$");
        CurrencyInfo info = new CurrencyInfo(plain.id(), plain.name(), plain.namePlural(), plain.symbol(), plain.icon(),
                2, plain.format(), plain.compactFormat());
        CurrencyFile.Stored settings = new CurrencyFile.Stored("dollars", info, List.of(), START, BigDecimal.ZERO, "",
                true, BigDecimal.ZERO, 0, true, Map.of(), true, true, true);
        holder[0] = new StoredCurrency(settings, economy);
        TestServer.set(economy, "stored", Map.of("dollars", holder[0]));
        TestServer.set(economy, "ready", true);
        return economy;
    }

    private static void join(StoredEconomy economy, UUID player) throws Exception {
        Map<UUID, Long> sessions = TestServer.get(economy, "sessions");
        AtomicLong next = TestServer.get(economy, "nextSession");
        sessions.put(player, next.incrementAndGet());
        TestServer.call(economy, "load", player);
    }

    private static List<String> announced(UUID player) {
        return TestServer.events(BalanceChangeEvent.class).stream()
                .filter(event -> event.player().equals(player))
                .map(event -> event.transaction().reason() + ":" + event.delta().stripTrailingZeros().toPlainString())
                .sorted()
                .toList();
    }

    @Test
    @DisplayName("a change is announced once, on the server that wrote it, queued or not")
    void everyChangeOnce() throws Exception {
        StoredCurrency[] onA = new StoredCurrency[1];
        StoredCurrency[] onB = new StoredCurrency[1];
        StoredEconomy a = economy(first, "A", onA);
        economy(second, "B", onB);
        UUID player = UUID.randomUUID();
        join(a, player);
        TestServer.await("A holds the balance", WAIT, () -> onA[0].isLoaded(player));

        onA[0].deposit(player, new BigDecimal("50"), Transaction.of("test:here"));
        // B does not hold the player: its deposit is queued for A, which writes it.
        onB[0].deposit(player, new BigDecimal("7"), Transaction.of("test:queued"));
        onA[0].withdraw(player, new BigDecimal("20"), Transaction.of("test:spent"));
        TestServer.await("A folded in B's deposit and wrote it all", WAIT, () -> {
            try {
                TestServer.call(a, "sweep");
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            return Databases.of(first).repository(PendingRow.class).where("player", player.toString()).find().join()
                    .isEmpty() && announced(player).size() == 3;
        });

        assertEquals(List.of("test:here:50", "test:queued:7", "test:spent:-20"), announced(player));
        TestServer.call(a, "leave", player);
    }

    @Test
    @DisplayName("the money supply adds every balance in the database and counts only the ones holding something")
    void supply() throws Exception {
        StoredCurrency[] holder = new StoredCurrency[1];
        StoredEconomy economy = economy(first, "A", holder);
        String key = "supply-test";
        var balances = Databases.of(first).repository(BalanceRow.class);
        for (String amount : List.of("10.50", "3", "0", "200", "1", "0")) {
            balances.save(new BalanceRow(UUID.randomUUID(), "", key, new BigDecimal(amount), "", 0L)).join();
        }
        CompletableFuture<?> read = (CompletableFuture<?>) TestServer.call(economy, "supplyOf", key);
        Object supply = read.get();

        assertEquals(0, new BigDecimal("214.50").compareTo(TestServer.<BigDecimal>get(supply, "sum")));
        assertEquals(4L, TestServer.<Long>get(supply, "holders"));
    }
}
