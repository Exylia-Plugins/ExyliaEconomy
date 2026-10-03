package net.exylia.exyliaEconomy.manager;

import net.exylia.exyliaEconomy.database.LedgerRow;
import net.exylia.exyliaEconomy.model.LedgerEntry;
import net.exylia.exyliaEconomy.testing.TestServer;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.MemoryDatabase;
import net.exylia.lib.economy.CurrencyInfo;
import net.exylia.lib.economy.Transaction;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A currency each server keeps its own of writes its ledger under that
 * server's key, so one server's history never shows another server's lines.
 */
class LedgerKeyTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    private static StoredEconomy economy(Plugin plugin, String id, StoredCurrency[] holder) throws Exception {
        Constructor<StoredEconomy> constructor = StoredEconomy.class.getDeclaredConstructor(Plugin.class, Runnable.class);
        constructor.setAccessible(true);
        StoredEconomy economy = constructor.newInstance(plugin, (Runnable) () -> { });
        TestServer.set(economy, "server", id);
        CurrencyInfo plain = CurrencyInfo.of("gems", "gem", "gems", "G");
        CurrencyInfo info = new CurrencyInfo(plain.id(), plain.name(), plain.namePlural(), plain.symbol(), plain.icon(),
                0, plain.format(), plain.compactFormat());
        CurrencyFile.Stored settings = new CurrencyFile.Stored("gems", info, List.of(), BigDecimal.ZERO, BigDecimal.ZERO,
                "", true, BigDecimal.ZERO, 0, true, Map.of(), true, false, true);
        holder[0] = new StoredCurrency(settings, economy);
        TestServer.set(economy, "stored", Map.of("gems", holder[0]));
        TestServer.set(economy, "contents", new CurrencyFile.Contents(Map.of("gems", settings), Map.of(), Map.of(),
                false, false, "", false, true));
        TestServer.set(economy, "ready", true);
        return economy;
    }

    @Test
    void historyReadsOnlyThisServersLines() throws Exception {
        TestServer.install();
        Plugin first = TestServer.plugin("LedgerServerA");
        Plugin second = TestServer.plugin("LedgerServerB");
        MemoryDatabase.install(first, "ledger-key");
        MemoryDatabase.install(second, "ledger-key");
        StoredCurrency[] onA = new StoredCurrency[1];
        StoredEconomy a = economy(first, "A", onA);
        StoredEconomy b = economy(second, "B", new StoredCurrency[1]);
        UUID player = UUID.randomUUID();
        Map<UUID, Long> sessions = TestServer.get(a, "sessions");
        sessions.put(player, TestServer.<AtomicLong>get(a, "nextSession").incrementAndGet());
        TestServer.call(a, "load", player);
        TestServer.await("A holds the balance", WAIT, () -> onA[0].isLoaded(player));

        onA[0].deposit(player, new BigDecimal("5"), Transaction.of("test:ledger"));
        var ledger = Databases.of(first).repository(LedgerRow.class);
        TestServer.await("the line is written", WAIT,
                () -> !ledger.where("player", player.toString()).find().join().isEmpty());

        assertEquals("gems@A", ledger.where("player", player.toString()).find().join().get(0).currency());
        TestServer.set(StoredEconomy.class, "instance", a);
        List<LedgerEntry> here = StoredEconomy.history("gems", player, 10).join();
        assertEquals(1, here.size());
        assertEquals("gems", here.get(0).currency());
        TestServer.set(StoredEconomy.class, "instance", b);
        assertEquals(List.of(), StoredEconomy.history("gems", player, 10).join());
        TestServer.set(StoredEconomy.class, "instance", null);
    }
}
