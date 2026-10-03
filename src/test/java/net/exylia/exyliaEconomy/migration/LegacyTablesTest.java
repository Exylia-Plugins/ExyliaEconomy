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

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The currencies ExyliaSurvivalCore kept under its own table names, brought over once. */
class LegacyTablesTest {

    private static final Logger LOGGER = Logger.getLogger("test");

    private static PluginDatabase database(String name) {
        TestServer.install();
        Plugin plugin = TestServer.plugin(name);
        MemoryDatabase.install(plugin, name);
        return Databases.of(plugin);
    }

    private static <T> T await(java.util.concurrent.CompletableFuture<T> future) throws Exception {
        return future.get(30, TimeUnit.SECONDS);
    }

    private static LegacyCurrencyRow legacy(CurrencyRow row) {
        return new LegacyCurrencyRow(row.id(), row.kind(), row.sortOrder(), row.name(), row.plural(), row.symbol(),
                row.icon(), row.decimals(), row.format(), row.compactFormat(), row.aliases(), row.item(), row.start(),
                row.max(), row.permission(), row.transferable(), row.minimumTransfer(), row.transferTaxPercent(),
                row.exchangeable(), row.rates(), row.leaderboard(), row.networked(), row.commands(), row.createdAt(),
                row.updatedAt());
    }

    @Test
    @DisplayName("an empty server copies the old tables column for column, once, and leaves them as they were")
    void copiesOnce() throws Exception {
        PluginDatabase database = database("LegacyCopy");
        Repository<LegacyCurrencyRow> oldCurrencies = database.repository(LegacyCurrencyRow.class);
        Repository<LegacyEconomySettingsRow> oldSettings = database.repository(LegacyEconomySettingsRow.class);
        CurrencyRow gems = CurrencyRow.blank("gems", CurrencyRow.Kind.STORED, 3)
                .edit(draft -> {
                    draft.start = new BigDecimal("25.5");
                    draft.permission = "server.gems";
                    draft.aliases = List.of("gems", "gem");
                    draft.rates = "dollars=2";
                });
        CurrencyRow tokens = CurrencyRow.blank("tokens", CurrencyRow.Kind.ITEM, 1).edit(draft -> draft.item = "EMERALD");
        await(oldCurrencies.saveAll(List.of(legacy(gems), legacy(tokens))));
        await(oldSettings.save(new LegacyEconomySettingsRow(EconomySettingsRow.GLOBAL, true, false, "gems", true,
                true, 1L, 180, "vault>gems")));

        Repository<CurrencyRow> currencies = database.repository(CurrencyRow.class);
        Repository<EconomySettingsRow> settings = database.repository(EconomySettingsRow.class);
        assertEquals(2, await(LegacyTables.copy(database, currencies, settings, LOGGER)));

        // Compared with the old row as the database hands it back, which is what was copied.
        assertEquals(await(oldCurrencies.find("gems")).orElseThrow().toRow(), await(currencies.find("gems")).orElseThrow());
        assertEquals(await(oldCurrencies.find("tokens")).orElseThrow().toRow(), await(currencies.find("tokens")).orElseThrow());
        CurrencyRow copied = await(currencies.find("gems")).orElseThrow();
        assertEquals(0, gems.start().compareTo(copied.start()));
        assertEquals(gems.aliases(), copied.aliases());
        assertEquals("server.gems", copied.permission());
        EconomySettingsRow global = await(settings.find(EconomySettingsRow.GLOBAL)).orElseThrow();
        assertEquals("gems", global.vaultProvide());
        assertEquals(180, global.ledgerDays());
        assertTrue(global.imported("vault", "gems"));

        // An admin deletes a currency here: it is not brought back on the next start.
        await(currencies.delete("tokens"));
        assertEquals(0, await(LegacyTables.copy(database, currencies, settings, LOGGER)));
        assertTrue(await(currencies.find("tokens")).isEmpty());
        assertEquals(2L, await(oldCurrencies.count()), "the old table is never touched");
    }

    @Test
    @DisplayName("a server that already has currencies of its own copies nothing")
    void ownRowsWin() throws Exception {
        PluginDatabase database = database("LegacyOwn");
        await(database.repository(LegacyCurrencyRow.class)
                .save(legacy(CurrencyRow.blank("old", CurrencyRow.Kind.STORED, 0))));
        Repository<CurrencyRow> currencies = database.repository(CurrencyRow.class);
        Repository<EconomySettingsRow> settings = database.repository(EconomySettingsRow.class);
        await(settings.save(new EconomySettingsRow(false, true, "", false, true)));

        assertEquals(0, await(LegacyTables.copy(database, currencies, settings, LOGGER)));
        assertTrue(await(currencies.findAll()).isEmpty());
    }
}
