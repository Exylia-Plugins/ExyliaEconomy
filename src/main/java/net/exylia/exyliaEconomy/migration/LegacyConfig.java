package net.exylia.exyliaEconomy.migration;

import net.exylia.exyliaEconomy.database.CurrencyRow;
import net.exylia.exyliaEconomy.database.EconomySettingsRow;
import net.exylia.lib.config.Languages;
import net.exylia.lib.economy.Economy;
import net.exylia.lib.input.FormField;
import net.exylia.lib.input.FormKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * The {@code config.yml} earlier versions wrote, imported into the database once.
 *
 * <p>Every setting it held now lives in the database and is edited in game. The
 * file is read on the first start after the upgrade, its values written where
 * the database has none, and it is renamed to {@code config.yml.migrated}.
 *
 * <p>A file holding nothing but {@code language} is imported the same way: the
 * language goes into the database, which tells ExyliaLib through
 * {@code Languages.use}, so the plugin never writes a {@code config.yml}.
 */
public final class LegacyConfig {

    public static final String FILE = "config.yml";

    private static final FormField<Duration> INTERVAL =
            FormField.duration(FormKey.duration("interval"), "interval");

    private final File file;
    private final YamlConfiguration yaml;

    private LegacyConfig(File file, YamlConfiguration yaml) {
        this.file = file;
        this.yaml = yaml;
    }

    /** The old file in a data folder, or {@code null} when there is none to import. */
    public static @Nullable LegacyConfig find(@NotNull File folder) {
        File file = new File(folder, FILE);
        if (!file.isFile()) return null;
        return new LegacyConfig(file, YamlConfiguration.loadConfiguration(file));
    }

    /** The settings row with the file's language, debug and offline notice. */
    public @NotNull EconomySettingsRow settings(@NotNull EconomySettingsRow row) {
        String language = yaml.getString(Languages.KEY, Languages.DEFAULT).trim().toLowerCase(Locale.ROOT);
        return row.withLanguage(language.isEmpty() ? Languages.DEFAULT : language)
                .withDebug(yaml.getBoolean("debug", false))
                .withOfflinePayNotice(yaml.getBoolean("offline-pay-notice", true));
    }

    /**
     * The rows the file changes, each only in what the database never set:
     * a confirmation threshold, banknotes and interest.
     *
     * @param defaultId the currency the file's {@code default} means
     */
    public @NotNull List<CurrencyRow> currencies(@NotNull Collection<CurrencyRow> rows, @NotNull String defaultId) {
        ConfigurationSection confirm = yaml.getConfigurationSection("pay-confirm-above");
        List<String> notes = yaml.getStringList("banknotes").stream()
                .map(id -> id.trim().toLowerCase(Locale.ROOT))
                .map(id -> id.equals("default") ? defaultId : id)
                .toList();
        ConfigurationSection interest = yaml.getConfigurationSection("interest");
        List<CurrencyRow> changed = new ArrayList<>();
        for (CurrencyRow row : rows) {
            BigDecimal above = row.confirmAbove() != null || confirm == null ? null
                    : Economy.parseAmount(confirm.getString(key(confirm, row), confirm.getString("default")));
            boolean banknotes = !row.banknotes() && notes.stream().anyMatch(id -> names(row, id));
            ConfigurationSection paid = row.interestRate() > 0 || interest == null ? null
                    : interest.getConfigurationSection(key(interest, row));
            double rate = paid == null ? 0 : paid.getDouble("rate", 0);
            if (above == null && !banknotes && rate <= 0) continue;
            changed.add(row.edit(draft -> {
                if (above != null) draft.payConfirmAbove = above;
                if (banknotes) draft.banknotes = true;
                if (rate > 0) {
                    draft.interestRate = rate;
                    Duration every = INTERVAL.parse(paid.getString("interval", "1h")).value();
                    if (every != null) draft.interestInterval = every.toSeconds();
                    BigDecimal max = Economy.parseAmount(paid.getString("max", "0"));
                    draft.interestMax = max == null ? BigDecimal.ZERO : max;
                    draft.interestOffline = !paid.getBoolean("online-only", true);
                }
            }));
        }
        return changed;
    }

    /** Renames the file so it is never imported again. */
    public void setAside(@NotNull Logger logger) {
        File aside = new File(file.getPath() + ".migrated");
        try {
            Files.move(file.toPath(), aside.toPath(), StandardCopyOption.REPLACE_EXISTING);
            logger.info("Economy: imported " + file.getPath() + " into the database and renamed it to "
                    + aside.getName() + ". Every setting is edited in game with /economyadmin.");
        } catch (IOException failure) {
            logger.warning("Economy: imported " + file.getPath() + " but could not rename it ("
                    + failure.getMessage() + "); it is imported again, where nothing is set, on the next start.");
        }
    }

    /** The key a section lists a currency under: its id or one of its command names. */
    private static String key(ConfigurationSection section, CurrencyRow row) {
        if (section.contains(row.id())) return row.id();
        if (row.aliases() != null) {
            for (String alias : row.aliases()) if (section.contains(alias)) return alias;
        }
        return row.id();
    }

    private static boolean names(CurrencyRow row, String id) {
        return row.id().equals(id) || (row.aliases() != null && row.aliases().contains(id));
    }
}
