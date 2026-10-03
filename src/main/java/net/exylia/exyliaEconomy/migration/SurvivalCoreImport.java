package net.exylia.exyliaEconomy.migration;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

/**
 * Brings over the files the economy module of ExyliaSurvivalCore left behind, on the first start.
 *
 * <ul>
 *   <li><b>{@code database.yml}.</b> The balances, the pending rows and the ledger are already in
 *       the survival core's database, under the same tables: this plugin has to open that
 *       database, not a new empty one. Copied as it is, so the Redis server id is the same too,
 *       and an embedded H2 file is pointed back at the survival core's folder, because its path
 *       is read relative to the plugin that owns the file.</li>
 *   <li><b>Messages.</b> Each language's {@code modules/economy/messages.yml} has the keys this
 *       plugin's {@code messages.yml} has, so it is copied where this plugin has none.</li>
 *   <li><b>Players' menus.</b> The wallet, the leaderboard and the history, which the owner may
 *       have restyled, with their buttons moved from {@code survivalcore:} to this plugin's
 *       actions. The admin screens are rewritten from the jar on every start anyway.</li>
 * </ul>
 *
 * <p>Runs before anything opens the database: the library reads {@code database.yml} when this
 * plugin first asks for its database or its Redis channels, and a file written afterwards would
 * only apply on the next start. Done once: a marker file is written, and from then on nothing is
 * read again. Never deletes or changes anything in the survival core's folder.
 */
public final class SurvivalCoreImport {

    private static final String SOURCE = "ExyliaSurvivalCore";
    private static final String MARKER = ".imported-survivalcore";
    private static final String DEFAULT_H2_FILE = "database/h2";
    private static final List<String> MENUS = List.of("wallet", "top", "history");

    private SurvivalCoreImport() {
        throw new AssertionError("No instances.");
    }

    /** Copies the files where this plugin has none of its own, once. */
    public static void files(Plugin plugin) {
        File folder = plugin.getDataFolder();
        if (new File(folder, MARKER).exists()) return;
        Logger logger = plugin.getLogger();
        File source = new File(folder.getParentFile(), SOURCE);
        if (source.isDirectory()) {
            try {
                database(source, folder, logger);
                languages(source, folder, logger);
            } catch (IOException | InvalidConfigurationException | RuntimeException failure) {
                // Not marked: the next start tries again rather than settling on a new, empty database.
                logger.severe("Could not import the economy files of " + SOURCE + ": " + failure
                        + ". Copy plugins/" + SOURCE + "/database.yml here by hand, or the balances"
                        + " are read from a new, empty database.");
                return;
            }
        }
        try {
            Files.createDirectories(folder.toPath());
            Files.writeString(new File(folder, MARKER).toPath(),
                    "Imported from " + SOURCE + ". Delete this file to import again.\n", StandardCharsets.UTF_8);
        } catch (IOException failure) {
            logger.warning("Could not note that the files of " + SOURCE + " were imported: " + failure);
        }
    }

    /** The survival core's {@code database.yml}, with an embedded file still pointing at its folder. */
    static void database(File source, File folder, Logger logger) throws IOException, InvalidConfigurationException {
        File from = new File(source, "database.yml");
        File to = new File(folder, "database.yml");
        if (!from.isFile() || to.exists()) return;
        YamlConfiguration database = new YamlConfiguration();
        database.load(from);
        String file = database.getString("database.h2.file", DEFAULT_H2_FILE);
        if (file == null || file.isBlank()) file = DEFAULT_H2_FILE;
        // The library resolves the path against this plugin's folder, and opens the survival
        // core's file as the same database only when both resolve to the same place.
        if (!Path.of(file).isAbsolute()) database.set("database.h2.file", "../" + SOURCE + "/" + file);
        Files.createDirectories(folder.toPath());
        database.save(to);
        logger.info("Using the database of " + SOURCE + ": its database.yml was copied here.");
    }

    /** Each language's messages and players' menus, where this plugin has none. */
    private static void languages(File source, File folder, Logger logger) throws IOException {
        File[] languages = new File(source, "lang").listFiles(File::isDirectory);
        if (languages == null) return;
        for (File language : languages) {
            String code = language.getName();
            copy(new File(language, "modules/economy/messages.yml"),
                    new File(folder, "lang/" + code + "/messages.yml"), false, logger);
            for (String menu : MENUS) {
                copy(new File(language, "modules/economy/menus/" + menu + ".yml"),
                        new File(folder, "lang/" + code + "/menus/user/" + menu + ".yml"), true, logger);
            }
        }
    }

    private static void copy(File from, File to, boolean menu, Logger logger) throws IOException {
        if (!from.isFile() || to.exists()) return;
        Files.createDirectories(to.toPath().getParent());
        String text = Files.readString(from.toPath(), StandardCharsets.UTF_8);
        // The buttons name the plugin whose action they run.
        if (menu) text = text.replace("survivalcore:", "exyliaeconomy:");
        Files.writeString(to.toPath(), text, StandardCharsets.UTF_8);
        logger.info("Imported " + to.getPath() + " from " + SOURCE + ".");
    }
}
