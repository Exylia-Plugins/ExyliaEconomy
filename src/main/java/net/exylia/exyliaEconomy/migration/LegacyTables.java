package net.exylia.exyliaEconomy.migration;

import net.exylia.exyliaEconomy.database.CurrencyRow;
import net.exylia.exyliaEconomy.database.EconomySettingsRow;
import net.exylia.lib.database.PluginDatabase;
import net.exylia.lib.database.Repository;
import net.exylia.lib.debug.Debug;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Copies the currencies and the economy settings from the tables ExyliaSurvivalCore named
 * ({@code sc_currencies}, {@code sc_economy_settings}) into this plugin's
 * ({@code exylia_currencies}, {@code exylia_economy_settings}).
 *
 * <p>The balances, the pending rows and the ledger already had plugin-neutral names and are read
 * where they are. These two did not.
 *
 * <p>Only while both new tables are empty, which is a server whose currencies were never set up
 * here. Run before the currencies are first read: otherwise that read finds the tables empty,
 * writes the default currencies, and the copy would never run. After the first read the settings
 * row always exists, so the copy happens once, and a currency an admin deletes later is never
 * brought back. The old tables are left exactly as they were.
 */
public final class LegacyTables {

    private LegacyTables() {
        throw new AssertionError("No instances.");
    }

    /**
     * @return how many currencies were copied; completes once they are written
     */
    public static CompletableFuture<Integer> copy(PluginDatabase database, Repository<CurrencyRow> currencies,
                                                  Repository<EconomySettingsRow> settings, Debug debug) {
        return currencies.count().thenCombine(settings.count(), (rows, global) -> rows + global)
                .thenCompose(existing -> {
                    if (existing > 0) return CompletableFuture.completedFuture(0);
                    Repository<LegacyCurrencyRow> oldCurrencies = database.repository(LegacyCurrencyRow.class);
                    Repository<LegacyEconomySettingsRow> oldSettings = database.repository(LegacyEconomySettingsRow.class);
                    return oldCurrencies.findAll().thenCombine(oldSettings.findAll(), (rows, global) -> {
                        List<CurrencyRow> copied = rows.stream().map(LegacyCurrencyRow::toRow).toList();
                        List<EconomySettingsRow> copiedSettings = global.stream().map(LegacyEconomySettingsRow::toRow).toList();
                        return currencies.saveAll(copied)
                                .thenCompose(ignored -> settings.saveAll(copiedSettings))
                                .thenApply(ignored -> {
                                    if (!copied.isEmpty() || !copiedSettings.isEmpty()) {
                                        debug.log("Economy: copied " + copied.size() + " currencies and the settings"
                                                + " from the tables of ExyliaSurvivalCore. Those tables are left as they were.");
                                    }
                                    return copied.size();
                                });
                    }).thenCompose(written -> written);
                });
    }
}
