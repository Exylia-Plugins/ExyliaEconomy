package net.exylia.exyliaEconomy.migration;

import net.exylia.exyliaEconomy.database.CurrencyRow;
import net.exylia.exyliaEconomy.database.EconomySettingsRow;
import net.exylia.exyliaEconomy.testing.TestServer;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.MemoryDatabase;
import net.exylia.lib.database.PluginDatabase;
import net.exylia.lib.database.Repository;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every setting lives in the database, and the old config.yml is brought into it once. */
class LegacyConfigTest {

    @TempDir
    Path folder;

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(30, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("the per-currency and global settings read back from the database as written")
    void roundTrip() throws Exception {
        TestServer.install();
        Plugin plugin = TestServer.plugin("SettingsRoundTrip");
        MemoryDatabase.install(plugin, "SettingsRoundTrip");
        PluginDatabase database = Databases.of(plugin);
        Repository<CurrencyRow> currencies = database.repository(CurrencyRow.class);
        Repository<EconomySettingsRow> settings = database.repository(EconomySettingsRow.class);

        CurrencyRow gems = CurrencyRow.blank("gems", CurrencyRow.Kind.STORED, 0).edit(draft -> {
            draft.payConfirmAbove = new BigDecimal("2500");
            draft.banknotes = true;
            draft.interestRate = 1.5;
            draft.interestInterval = 1800;
            draft.interestMax = new BigDecimal("500");
            draft.interestOffline = true;
        });
        EconomySettingsRow global = new EconomySettingsRow(false, true, "gems", false, true)
                .withLanguage("es").withDebug(true).withOfflinePayNotice(false);
        await(currencies.save(gems));
        await(settings.save(global));

        CurrencyRow read = await(currencies.find("gems")).orElseThrow();
        assertEquals(0, new BigDecimal("2500").compareTo(read.confirmAbove()));
        assertTrue(read.banknotes());
        assertEquals(1.5, read.interestRate());
        assertEquals(1800, read.interestEvery().toSeconds());
        assertEquals(0, new BigDecimal("500").compareTo(read.interestMax()));
        assertTrue(read.interestOffline());
        EconomySettingsRow back = await(settings.find(EconomySettingsRow.GLOBAL)).orElseThrow();
        assertEquals("es", back.languageOrDefault());
        assertTrue(back.debug());
        assertFalse(back.offlinePayNotice());
        assertFalse(back.fresh());
        assertEquals("pt", back.withNextLanguage().language());
        assertEquals("default", back.withNextLanguage().withNextLanguage().language());
    }

    @Test
    @DisplayName("the old file fills what the database never set, once, and is put aside")
    void migratesOnce() throws Exception {
        File config = folder.resolve(LegacyConfig.FILE).toFile();
        Files.writeString(config.toPath(), """
                language: es
                debug: true
                pay-confirm-above:
                  default: 10k
                  gems: '100'
                offline-pay-notice: false
                banknotes:
                - default
                - coin
                interest:
                  coin:
                    rate: 1.5
                    interval: 30m
                    max: '500'
                    online-only: false
                """);
        CurrencyRow dollars = CurrencyRow.blank("dollars", CurrencyRow.Kind.STORED, 0);
        CurrencyRow gems = CurrencyRow.blank("gems", CurrencyRow.Kind.STORED, 1)
                .edit(draft -> draft.payConfirmAbove = new BigDecimal("50"));
        CurrencyRow coins = CurrencyRow.blank("coins", CurrencyRow.Kind.STORED, 2)
                .edit(draft -> draft.aliases = List.of("coins", "coin"));

        LegacyConfig legacy = LegacyConfig.find(folder.toFile());
        assertNotNull(legacy);
        EconomySettingsRow fresh = new EconomySettingsRow(false, true, "dollars", false, true);
        assertTrue(fresh.fresh());
        EconomySettingsRow global = legacy.settings(fresh);
        assertEquals("es", global.language());
        assertTrue(global.debug());
        assertFalse(global.offlinePayNotice());

        Map<String, CurrencyRow> changed = legacy.currencies(List.of(dollars, gems, coins), "dollars").stream()
                .collect(Collectors.toMap(CurrencyRow::id, Function.identity()));
        assertEquals(0, new BigDecimal("10000").compareTo(changed.get("dollars").confirmAbove()));
        assertTrue(changed.get("dollars").banknotes(), "'default' is the default currency");
        assertFalse(changed.containsKey("gems"), "a threshold already in the database is kept");
        CurrencyRow coin = changed.get("coins");
        assertTrue(coin.banknotes(), "a command name finds its currency");
        assertEquals(1.5, coin.interestRate());
        assertEquals(1800, coin.interestEvery().toSeconds());
        assertEquals(0, new BigDecimal("500").compareTo(coin.interestMax()));
        assertTrue(coin.interestOffline());
        assertEquals(0, new BigDecimal("10000").compareTo(coin.confirmAbove()));

        legacy.setAside(Logger.getLogger("test"));
        assertFalse(config.exists());
        assertTrue(folder.resolve(LegacyConfig.FILE + ".migrated").toFile().isFile());
        assertNull(LegacyConfig.find(folder.toFile()));
    }

    @Test
    @DisplayName("a file holding only the language is imported and put aside too")
    void languageOnlyFile() throws Exception {
        Files.writeString(folder.resolve(LegacyConfig.FILE), "language: pt\n");
        LegacyConfig legacy = LegacyConfig.find(folder.toFile());
        assertNotNull(legacy);
        EconomySettingsRow global = legacy.settings(new EconomySettingsRow(false, true, "", false, true));
        assertEquals("pt", global.language());
        assertFalse(global.debug());
        assertTrue(global.offlinePayNotice());
        assertTrue(legacy.currencies(List.of(CurrencyRow.blank("dollars", CurrencyRow.Kind.STORED, 0)), "dollars")
                .isEmpty());
        legacy.setAside(Logger.getLogger("test"));
        assertNull(LegacyConfig.find(folder.toFile()));
    }
}
