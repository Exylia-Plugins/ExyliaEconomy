package net.exylia.exyliaEconomy.migration;

import net.exylia.lib.debug.Debug;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Brings over the files the economy module of ExyliaSurvivalCore left behind, on the first start.
 *
 * <ul>
 *   <li><b>{@code database.yml}.</b> The balances, the pending rows and the ledger are already in
 *       the survival core's database, under the same tables: this plugin has to open that
 *       database, not a new empty one. Copied as it is, so the Redis server id is the same too.
 *       An embedded H2 file is copied into this plugin's folder under the same path, so deleting
 *       the survival core's folder never takes the balances with it.</li>
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
 * read again — except that a {@code database.yml} an earlier version pointed at the survival
 * core's H2 file gets the same copy on the next start. Never deletes or changes anything in the
 * survival core's folder.
 */
public final class SurvivalCoreImport {

    private static final String SOURCE = "ExyliaSurvivalCore";
    private static final String MARKER = ".imported-survivalcore";
    private static final String DEFAULT_H2_FILE = "database/h2";
    /** What H2 adds to {@code database.h2.file}: the current store, and the one before it. */
    private static final List<String> H2_SUFFIXES = List.of(".mv.db", ".h2.db");
    private static final List<String> MENUS = List.of("wallet", "top", "history");

    private SurvivalCoreImport() {
        throw new AssertionError("No instances.");
    }

    /** Copies the files where this plugin has none of its own, once. */
    public static void files(Plugin plugin, Debug debug) {
        File folder = plugin.getDataFolder();
        if (new File(folder, MARKER).exists()) {
            try {
                relocate(folder, debug);
            } catch (IOException | InvalidConfigurationException | RuntimeException failure) {
                debug.error("Could not copy the database of " + SOURCE + " here: " + failure + ". The balances"
                        + " are still read from plugins/" + SOURCE + "; do not delete that folder.");
            }
            return;
        }
        File source = new File(folder.getParentFile(), SOURCE);
        if (source.isDirectory()) {
            try {
                database(source, folder, debug);
                languages(source, folder, debug);
            } catch (IOException | InvalidConfigurationException | RuntimeException failure) {
                // Not marked: the next start tries again rather than settling on a new, empty database.
                debug.error("Could not import the economy files of " + SOURCE + ": " + failure
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
            debug.warn("Could not note that the files of " + SOURCE + " were imported: " + failure);
        }
    }

    /** The survival core's {@code database.yml}, with an embedded file still pointing at its folder. */
    static void database(File source, File folder, Debug debug) throws IOException, InvalidConfigurationException {
        File from = new File(source, "database.yml");
        File to = new File(folder, "database.yml");
        if (!from.isFile() || to.exists()) return;
        YamlConfiguration database = new YamlConfiguration();
        database.load(from);
        String file = database.getString("database.h2.file", DEFAULT_H2_FILE);
        if (file == null || file.isBlank()) file = DEFAULT_H2_FILE;
        // The library resolves the path against this plugin's folder: the same path here is the
        // copy. A file of this plugin's own already there is never written over, and the survival
        // core's stays the one read.
        if (!Path.of(file).isAbsolute()) {
            database.set("database.h2.file", copyH2(source, folder, file, debug) ? file : "../" + SOURCE + "/" + file);
        }
        Files.createDirectories(folder.toPath());
        database.save(to);
        debug.log("Using the database of " + SOURCE + ": its database.yml was copied here.");
    }

    /**
     * A {@code database.yml} an earlier version wrote, still opening the survival core's H2 file:
     * given a copy of its own, so that folder can go.
     */
    static void relocate(File folder, Debug debug) throws IOException, InvalidConfigurationException {
        File config = new File(folder, "database.yml");
        if (!config.isFile()) return;
        YamlConfiguration database = new YamlConfiguration();
        database.load(config);
        String file = database.getString("database.h2.file", "");
        String prefix = "../" + SOURCE + "/";
        if (file == null || !file.startsWith(prefix)) return;
        String own = file.substring(prefix.length());
        if (!copyH2(new File(folder.getParentFile(), SOURCE), folder, own, debug)) return;
        database.set("database.h2.file", own);
        database.save(config);
        debug.log("database.yml now opens this plugin's own copy of the database; the one in plugins/"
                + SOURCE + " is no longer read.");
    }

    /**
     * Copies the survival core's H2 file into this plugin's folder, under the same path.
     *
     * <p>Through a temporary file moved into place, so a copy cut short is never mistaken for a
     * database on the next start.
     *
     * @return whether the copy is in place; {@code false} when there is no file to copy, or this
     *         plugin already has one there
     */
    static boolean copyH2(File source, File folder, String file, Debug debug) throws IOException {
        for (String suffix : H2_SUFFIXES) {
            Path to = new File(folder, file + suffix).toPath();
            if (Files.exists(to)) {
                debug.warn("Not copying the database of " + SOURCE + ": " + to + " already exists, so the"
                        + " survival core's file stays the one read.");
                return false;
            }
        }
        boolean copied = false;
        for (String suffix : H2_SUFFIXES) {
            Path from = new File(source, file + suffix).toPath();
            Path to = new File(folder, file + suffix).toPath();
            if (!Files.isRegularFile(from)) continue;
            Files.createDirectories(to.getParent());
            Path partial = to.resolveSibling(to.getFileName() + ".importing");
            Files.copy(from, partial, StandardCopyOption.REPLACE_EXISTING);
            Files.move(partial, to);
            debug.log("Copied the database " + from + " to " + to + ".");
            copied = true;
        }
        return copied;
    }

    /** Each language's messages and players' menus, where this plugin has none. */
    private static void languages(File source, File folder, Debug debug) throws IOException {
        File[] languages = new File(source, "lang").listFiles(File::isDirectory);
        if (languages == null) return;
        for (File language : languages) {
            String code = language.getName();
            copy(new File(language, "modules/economy/messages.yml"),
                    new File(folder, "lang/" + code + "/messages.yml"), false, debug);
            for (String menu : MENUS) {
                copy(new File(language, "modules/economy/menus/" + menu + ".yml"),
                        new File(folder, "lang/" + code + "/menus/user/" + menu + ".yml"), true, debug);
            }
        }
    }

    private static void copy(File from, File to, boolean menu, Debug debug) throws IOException {
        if (!from.isFile() || to.exists()) return;
        Files.createDirectories(to.toPath().getParent());
        String text = Files.readString(from.toPath(), StandardCharsets.UTF_8);
        // The buttons name the plugin whose action they run.
        if (menu) text = text.replace("survivalcore:", "exyliaeconomy:");
        Files.writeString(to.toPath(), text, StandardCharsets.UTF_8);
        debug.log("Imported " + to.getPath() + " from " + SOURCE + ".");
    }
}
