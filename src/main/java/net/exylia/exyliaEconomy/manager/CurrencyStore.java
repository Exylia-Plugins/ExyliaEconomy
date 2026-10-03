package net.exylia.exyliaEconomy.manager;

import net.exylia.exyliaEconomy.database.CurrencyRow;
import net.exylia.exyliaEconomy.database.EconomySettingsRow;
import net.exylia.exyliaEconomy.migration.LegacyTables;
import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Repository;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Where the currencies live: {@code exylia_currencies} and {@code exylia_economy_settings}.
 *
 * <p>Administrators edit them in game through {@code /economyadmin}; nothing
 * reads {@code currencies.yml} any more except the one import that brings a
 * server's file into the tables the first time they are empty.
 *
 * <p>The rows are cached here, so the admin screens draw without a query and
 * {@link #contents()} answers what the runtime should register right now.
 */
public final class CurrencyStore {

    private final Plugin plugin;
    private final Logger logger;
    private final Repository<CurrencyRow> rows;
    private final Repository<EconomySettingsRow> settingsRows;
    private final Map<String, CurrencyRow> cache = new ConcurrentHashMap<>();
    private volatile EconomySettingsRow settings = defaultSettings();

    CurrencyStore(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.rows = Databases.of(plugin).repository(CurrencyRow.class);
        this.settingsRows = Databases.of(plugin).repository(EconomySettingsRow.class);
    }

    /**
     * Reads the tables and imports the file when they are empty.
     *
     * <p>Only called while the plugin enables. Never waited for there: the
     * library's queries run on the Bukkit async scheduler, which starts nothing
     * before the server's first tick, so a join during enable never returns.
     */
    @NotNull CompletableFuture<CurrencyFile.Contents> load() {
        // First, so a server moving from ExyliaSurvivalCore finds its own currencies here rather
        // than an empty table it would fill with the defaults.
        // Only asked where ExyliaSurvivalCore has run: a fresh install never creates its old tables.
        CompletableFuture<Integer> legacy = new File(plugin.getDataFolder().getParentFile(), "ExyliaSurvivalCore").isDirectory()
                ? LegacyTables.copy(Databases.of(plugin), rows, settingsRows, logger)
                : CompletableFuture.completedFuture(0);
        return legacy
                .thenCompose(copied -> rows.findAll())
                .thenCompose(found -> settingsRows.find(EconomySettingsRow.GLOBAL).thenCompose(stored -> {
            if (found.isEmpty() && stored.isEmpty()) {
                return importFile();
            }
            File file = new File(plugin.getDataFolder(), CurrencyFile.FILE);
            if (file.isFile()) {
                logger.warning("Economy: " + file.getPath() + " is no longer read. The currencies live in the"
                        + " database; edit them in game with /economyadmin.");
            }
            remember(found, stored.orElse(null));
            if (stored.isEmpty()) {
                // Written back so the admin screens and the other servers agree on it.
                return settingsRows.save(settings).thenApply(saved -> contents());
            }
            return CompletableFuture.completedFuture(contents());
        }));
    }

    /** Reads the tables again without waiting: a reload, or another server's edit. */
    @NotNull CompletableFuture<CurrencyFile.Contents> fetch() {
        CompletableFuture<List<CurrencyRow>> found = rows.findAll();
        CompletableFuture<Optional<EconomySettingsRow>> stored = settingsRows.find(EconomySettingsRow.GLOBAL);
        return found.thenCombine(stored, (list, global) -> {
            remember(list, global.orElse(null));
            return contents();
        });
    }

    /**
     * Writes {@code currencies.yml} into the tables, then puts the file aside.
     *
     * <p>{@link CurrencyFile#load} already knows every way the file can be
     * found — the owner's, the one the survival core kept, or the defaults — so a server
     * keeps exactly the currencies it had.
     */
    private CompletableFuture<CurrencyFile.Contents> importFile() {
        CurrencyFile.Contents file = CurrencyFile.load(plugin.getDataFolder(), logger);
        List<CurrencyRow> imported = rows(file);
        EconomySettingsRow global = new EconomySettingsRow(file.experienceLevels(), file.experiencePoints(),
                file.vaultProvide(), file.vaultForce(), file.ledger());
        return rows.saveAll(imported)
                .thenCompose(saved -> settingsRows.save(global))
                .thenApply(saved -> {
                    remember(imported, global);
                    setAside(imported.size());
                    return contents();
                });
    }

    private void setAside(int imported) {
        File source = new File(plugin.getDataFolder(), CurrencyFile.FILE);
        if (source.isFile()) {
            File aside = new File(source.getPath() + ".imported");
            try {
                Files.move(source.toPath(), aside.toPath(), StandardCopyOption.REPLACE_EXISTING);
                logger.info("Economy: imported " + imported + " currencies from " + source.getPath()
                        + " into the database and renamed it to " + aside.getName()
                        + ". Edit them in game with /economyadmin.");
            } catch (IOException failure) {
                logger.warning("Economy: imported the currencies but could not rename " + source.getPath()
                        + " (" + failure.getMessage() + "); it is no longer read either way.");
            }
        }
    }

    private void remember(Collection<CurrencyRow> found, @Nullable EconomySettingsRow global) {
        Map<String, CurrencyRow> fresh = new LinkedHashMap<>();
        for (CurrencyRow row : found) fresh.put(row.id(), row);
        cache.keySet().retainAll(fresh.keySet());
        cache.putAll(fresh);
        settings = global != null ? global : defaultSettings(found);
    }

    // ------------------------------------------------------------------ edits

    /** Every currency, in the order they are shown. */
    public @NotNull List<CurrencyRow> all() {
        List<CurrencyRow> list = new ArrayList<>(cache.values());
        list.sort(ORDER);
        return list;
    }

    public @NotNull Optional<CurrencyRow> get(@NotNull String id) {
        return Optional.ofNullable(cache.get(id.toLowerCase(Locale.ROOT)));
    }

    public @NotNull EconomySettingsRow settings() {
        return settings;
    }

    /** Keeps a row. The cache changes now; the future completes once it is written. */
    public @NotNull CompletableFuture<Void> save(@NotNull CurrencyRow row) {
        cache.put(row.id(), row);
        return rows.save(row);
    }

    public @NotNull CompletableFuture<Void> save(@NotNull EconomySettingsRow row) {
        settings = row;
        return settingsRows.save(row);
    }

    public @NotNull CompletableFuture<Boolean> delete(@NotNull String id) {
        cache.remove(id);
        return rows.delete(id);
    }

    /** What the runtime should register, from the rows as they are now. */
    public @NotNull CurrencyFile.Contents contents() {
        return contents(all(), settings);
    }

    // ---------------------------------------------------------------- mapping

    private static final Comparator<CurrencyRow> ORDER =
            Comparator.comparingInt(CurrencyRow::sortOrder).thenComparing(CurrencyRow::id);

    private static EconomySettingsRow defaultSettings() {
        return defaultSettings(List.of());
    }

    /**
     * The settings of a server whose settings row is missing.
     *
     * <p>Vault is given the first stored currency, as the shipped file does:
     * blank here would leave the 'vault' currency, ExyliaLib's default, with no
     * economy behind it. An admin who turns Vault off writes the row, so this
     * never overrides that.
     */
    static EconomySettingsRow defaultSettings(@NotNull Collection<CurrencyRow> rows) {
        String provide = rows.stream().filter(row -> row.kind() == CurrencyRow.Kind.STORED)
                .min(ORDER).map(CurrencyRow::id).orElse("");
        return new EconomySettingsRow(false, true, provide, false, true);
    }

    /**
     * The rows as the contents the runtime has always registered from.
     *
     * <p>An item currency with no item yet is left out, the same as the file
     * skipped one: registered, it would count nothing and warn on every read.
     */
    static @NotNull CurrencyFile.Contents contents(@NotNull List<CurrencyRow> rows,
                                                   @NotNull EconomySettingsRow settings) {
        Map<String, CurrencyFile.Stored> stored = new LinkedHashMap<>();
        Map<String, CurrencyFile.Item> items = new LinkedHashMap<>();
        Map<String, net.exylia.lib.economy.CurrencyInfo> display = new LinkedHashMap<>();
        List<CurrencyRow> ordered = new ArrayList<>(rows);
        ordered.sort(ORDER);
        for (CurrencyRow row : ordered) {
            switch (row.kind()) {
                case STORED -> stored.put(row.id(), row.stored());
                case ITEM -> {
                    if (row.item() != null && !row.item().isBlank()) items.put(row.id(), row.itemCurrency());
                }
                case DISPLAY -> display.put(row.id(), row.info());
            }
        }
        return new CurrencyFile.Contents(stored, items, display, settings.experienceLevels(),
                settings.experiencePoints(), settings.vaultProvide(), settings.vaultForce(), settings.ledger());
    }

    /** The file's contents as rows, in the order the file lists them. */
    static @NotNull List<CurrencyRow> rows(@NotNull CurrencyFile.Contents file) {
        List<CurrencyRow> out = new ArrayList<>();
        file.stored().values().forEach(stored -> out.add(CurrencyRow.of(stored, out.size())));
        file.items().values().forEach(item -> out.add(CurrencyRow.of(item, out.size())));
        // One row per id: an overlay on a currency this file also defines
        // said nothing the currency's own block did not.
        java.util.Set<String> taken = new java.util.HashSet<>();
        out.forEach(row -> taken.add(row.id()));
        file.display().values().stream().filter(overlay -> taken.add(overlay.id()))
                .forEach(overlay -> out.add(CurrencyRow.of(overlay, out.size())));
        return out;
    }
}
