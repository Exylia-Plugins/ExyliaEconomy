package net.exylia.exyliaEconomy.manager;

import net.exylia.lib.economy.Transaction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class VaultBridgeReasonTest {

    @Test
    void keepsTheReasonAnExyliaLibCallerGave() {
        Transaction sale = Transaction.of("sell:wand");
        assertSame(sale, VaultBridge.reason(sale, () -> {
            throw new AssertionError("no stack walk when the reason is known");
        }));
    }

    @Test
    void namesThePluginWhenNoReasonCameThrough() {
        assertEquals("vault:jobs", VaultBridge.reason(null, () -> "jobs").reason());
        assertEquals("vault:jobs", VaultBridge.reason(Transaction.NONE, () -> "jobs").reason());
        assertEquals("vault", VaultBridge.reason(null, () -> "").reason());
    }
}
