package net.exylia.exyliaEconomy.manager;

import net.exylia.exyliaEconomy.database.BalanceRow;
import net.exylia.exyliaEconomy.testing.TestServer;
import net.exylia.lib.database.MemoryDatabase;
import net.exylia.lib.economy.CurrencyInfo;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /paytoggle and the leaderboard exemption, as the database keeps them. */
class PlayerFlagsTest {

    private static Plugin plugin;
    private static Plugin other;

    @BeforeAll
    static void server() {
        TestServer.install();
        plugin = TestServer.plugin("EconomyFlags");
        other = TestServer.plugin("EconomyFlagsOther");
        // Two servers on one database.
        MemoryDatabase.install(plugin, "economy-flags");
        MemoryDatabase.install(other, "economy-flags");
    }

    @Test
    @DisplayName("a pay toggle holds through a restart and on another server, and turns back off")
    void payTogglePersists() {
        UUID player = UUID.randomUUID();
        new PlayerFlags(plugin).set(player, PlayerFlags.PAY_OFF, true).join();

        assertTrue(new PlayerFlags(plugin).has(player, PlayerFlags.PAY_OFF).join());
        assertTrue(new PlayerFlags(other).has(player, PlayerFlags.PAY_OFF).join());
        assertFalse(new PlayerFlags(other).has(player, PlayerFlags.TOP_EXEMPT).join());

        new PlayerFlags(other).set(player, PlayerFlags.PAY_OFF, false).join();
        assertFalse(new PlayerFlags(plugin).has(player, PlayerFlags.PAY_OFF).join());
    }

    private static BalanceRow balance(UUID player, String name, String currency, String amount) {
        return new BalanceRow(player, name, currency, new BigDecimal(amount), "", 1L);
    }

    @Test
    @DisplayName("exempt players are left off the board and hold no place")
    void exemptHoldNoPlace() {
        UUID rich = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        List<StoredEconomy.TopEntry> board = StoredEconomy.ranked(List.of(
                balance(rich, "Staff", "c", "900"),
                balance(second, "Ann", "c", "500"),
                balance(third, "Bob", "c", "100")), Set.of(rich), 10);

        assertEquals(2, board.size());
        assertEquals(second, board.get(0).player());
        assertEquals(1, board.get(0).position());
        assertEquals(2, board.get(1).position());
        assertEquals(1, StoredEconomy.ranked(List.of(balance(second, "Ann", "c", "5"),
                balance(third, "Bob", "c", "1")), Set.of(), 1).size());
    }

    @Test
    @DisplayName("the board read from the database skips exempt players and sums every balance")
    void boardFromDatabase() throws Exception {
        Constructor<StoredEconomy> constructor = StoredEconomy.class.getDeclaredConstructor(Plugin.class, Runnable.class);
        constructor.setAccessible(true);
        StoredEconomy economy = constructor.newInstance(plugin, (Runnable) () -> { });
        TestServer.set(economy, "server", "S");
        CurrencyInfo info = CurrencyInfo.of("topc", "topc", "topc", "$");
        CurrencyFile.Stored settings = new CurrencyFile.Stored("topc", info, List.of(), BigDecimal.ZERO,
                BigDecimal.ZERO, "", true, BigDecimal.ZERO, 0, true, Map.of(), true, true, true);
        TestServer.set(economy, "stored", Map.of("topc", new StoredCurrency(settings, economy)));
        TestServer.set(economy, "ready", true);

        UUID staff = UUID.randomUUID();
        UUID ann = UUID.randomUUID();
        var balances = net.exylia.lib.database.Databases.of(plugin).repository(BalanceRow.class);
        balances.save(balance(staff, "Staff", "topc", "1000")).join();
        balances.save(balance(ann, "Ann", "topc", "250")).join();
        new PlayerFlags(plugin).set(staff, PlayerFlags.TOP_EXEMPT, true).join();

        TestServer.set(StoredEconomy.class, "instance", economy);
        try {
            List<StoredEconomy.TopEntry> board = StoredEconomy.topLater("topc", 10).join();
            assertEquals(1, board.size());
            assertEquals(ann, board.get(0).player());
            assertEquals(1, board.get(0).position());
            assertEquals(0, StoredEconomy.total("topc").compareTo(new BigDecimal("1250")));
        } finally {
            TestServer.set(StoredEconomy.class, "instance", null);
        }
    }
}
