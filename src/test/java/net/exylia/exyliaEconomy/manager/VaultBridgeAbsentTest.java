package net.exylia.exyliaEconomy.manager;

import net.exylia.exyliaEconomy.testing.TestServer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** A server on this plugin alone: no Vault to publish to, yet 'vault', the library's default, must still mean a currency. */
class VaultBridgeAbsentTest {

    @Test
    void theCurrencySetForVaultAnswersWithoutVault() throws Exception {
        TestServer.install();
        Plugin plugin = TestServer.plugin("EconomyNoVault");
        StoredCurrency[] holder = new StoredCurrency[1];
        LedgerKeyTest.economy(plugin, "A", holder);
        VaultBridge bridge = new VaultBridge(plugin);

        bridge.publish(holder[0], false);
        assertSame(holder[0], bridge.serving());
        bridge.refresh();
        assertSame(holder[0], bridge.serving());

        bridge.publish(null, false);
        assertNull(bridge.serving());
    }
}
