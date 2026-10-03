package net.exylia.exyliaEconomy.service;

import net.exylia.exyliaEconomy.database.PayNoticeRow;
import net.exylia.exyliaEconomy.testing.TestServer;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.MemoryDatabase;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a player is told on join about the payments they missed. */
class PayNoticesTest {

    private static Plugin plugin;

    @BeforeAll
    static void server() {
        TestServer.install();
        plugin = TestServer.plugin("EconomyNotices");
        MemoryDatabase.install(plugin, "economy-notices");
    }

    private static PayNoticeRow row(long id, String currency, String amount, UUID payer, String name) {
        return new PayNoticeRow(id, UUID.randomUUID().toString(), currency, new BigDecimal(amount),
                payer.toString(), name, 0L);
    }

    @Test
    @DisplayName("one line per currency, summed, counting each payer once")
    void summarises() {
        UUID ann = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        List<PayNotices.Summary> summaries = PayNotices.summarise(List.of(
                row(3, "coins", "50", bob, "Bob"),
                row(1, "coins", "100", ann, "Ann"),
                row(2, "gems", "5", ann, "Ann"),
                row(4, "coins", "25", ann, "Ann")));

        assertEquals(2, summaries.size());
        PayNotices.Summary coins = summaries.get(0);
        assertEquals("coins", coins.currency());
        assertEquals(0, coins.amount().compareTo(new BigDecimal("175")));
        assertEquals(2, coins.payers());
        PayNotices.Summary gems = summaries.get(1);
        assertEquals(1, gems.payers());
        assertEquals("Ann", gems.payerName());
    }

    @Test
    @DisplayName("a player's notices are taken once: a second join finds none")
    void takenOnce() {
        PayNotices notices = new PayNotices(plugin);
        UUID player = UUID.randomUUID();
        var rows = Databases.of(plugin).repository(PayNoticeRow.class);
        rows.insert(new PayNoticeRow(player, "coins", new BigDecimal("10"), UUID.randomUUID(), "Ann")).join();
        rows.insert(new PayNoticeRow(player, "coins", new BigDecimal("15"), UUID.randomUUID(), "Bob")).join();
        rows.insert(new PayNoticeRow(UUID.randomUUID(), "coins", new BigDecimal("99"), UUID.randomUUID(), "Eve")).join();

        List<PayNoticeRow> first = notices.take(player).join();
        assertEquals(2, first.size());
        assertEquals(0, PayNotices.summarise(first).get(0).amount().compareTo(new BigDecimal("25")));
        assertTrue(notices.take(player).join().isEmpty());
    }
}
