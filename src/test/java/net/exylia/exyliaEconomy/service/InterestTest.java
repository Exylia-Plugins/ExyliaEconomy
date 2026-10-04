package net.exylia.exyliaEconomy.service;

import net.exylia.exyliaEconomy.manager.Claims;
import net.exylia.exyliaEconomy.testing.TestServer;
import net.exylia.lib.database.MemoryDatabase;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Interest: what a payout is worth, and that one slot pays a player once across servers. */
class InterestTest {

    private static Plugin first;
    private static Plugin second;

    @BeforeAll
    static void servers() {
        TestServer.install();
        first = TestServer.plugin("EconomyInterestA");
        second = TestServer.plugin("EconomyInterestB");
        MemoryDatabase.install(first, "economy-interest");
        MemoryDatabase.install(second, "economy-interest");
    }

    @Test
    @DisplayName("a slot is claimed once per player, however many servers ask at once")
    void slotPaysOnce() {
        long hour = 3_600_000L;
        long slot = Interest.slot(System.currentTimeMillis(), hour);
        UUID player = UUID.randomUUID();
        String id = Interest.claimId("coins", slot, player);
        List<CompletableFuture<Boolean>> claims = new ArrayList<>();
        for (int server = 0; server < 12; server++) {
            Claims on = new Claims(server % 2 == 0 ? first : second);
            claims.add(CompletableFuture.supplyAsync(() -> on.claim(id, 2)).thenCompose(won -> won));
        }
        assertEquals(1, claims.stream().map(CompletableFuture::join).filter(Boolean::booleanValue).count());
        // A restart, or the next check, finds it taken; the next slot is a new payout.
        assertFalse(new Claims(first).claim(id, 2).join());
        assertTrue(new Claims(second).claim(Interest.claimId("coins", slot + 1, player), 2).join());
        assertEquals(slot + 1, Interest.slot(System.currentTimeMillis() + hour, hour));
    }

    @Test
    @DisplayName("a payout is the rate, cut to the decimals and capped")
    void earned() {
        assertEquals(0, new BigDecimal("12.34").compareTo(Interest.earned(new BigDecimal("1234.56"), 1.0, null, 2)));
        assertEquals(0, new BigDecimal("50").compareTo(Interest.earned(new BigDecimal("100000"), 1.0, new BigDecimal("50"), 2)));
        assertEquals(0, BigDecimal.ZERO.compareTo(Interest.earned(BigDecimal.ZERO, 5.0, null, 2)));
        assertEquals(0, new BigDecimal("12").compareTo(Interest.earned(new BigDecimal("1299"), 1.0, BigDecimal.ZERO, 0)));
    }
}
