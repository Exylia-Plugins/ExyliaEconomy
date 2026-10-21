package net.exylia.exyliaEconomy.bootstrap;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tells the owner how to install ExyliaLib when the server does not have it.
 *
 * <p>Nothing is downloaded: installing a jar is the owner's decision. Plain JDK and Bukkit on
 * purpose: it runs exactly when ExyliaLib is missing, so it cannot touch a single ExyliaLib type.
 */
public final class ExyliaLibMissing {

    static final String MODRINTH_URL = "https://modrinth.com/plugin/exylialib";
    static final String RELEASES_URL = "https://github.com/DiGround-s/ExyliaLib/releases";

    private ExyliaLibMissing() {
    }

    /** Says why the plugin stays off and where ExyliaLib comes from. */
    public static void report(JavaPlugin plugin) {
        String name = plugin.getName();
        Path plugins = plugin.getDataFolder().toPath().toAbsolutePath().getParent();
        try {
            if (hasLibraryJar(plugins)) {
                plugin.getLogger().severe("ExyliaLib is in plugins/ but did not load. Check the console for its errors"
                        + " above, fix them and restart the server to start " + name + ".");
                return;
            }
        } catch (IOException unreadable) {
            // Reported as missing below.
        }
        String version;
        try {
            version = requiredVersion(plugin) + " or newer";
        } catch (RuntimeException unreadable) {
            version = "";
        }
        plugin.getLogger().severe(name + " needs ExyliaLib" + (version.isEmpty() ? "" : " " + version)
                + ", which is not installed. Download it from " + MODRINTH_URL + " or " + RELEASES_URL
                + ", put it in plugins/ and restart the server.");
    }

    /** The {@code exylia-lib} version this jar's plugin.yml was built with. */
    static String requiredVersion(JavaPlugin plugin) {
        try (InputStream in = plugin.getResource("plugin.yml")) {
            if (in != null) {
                String version = topLevel(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)), "exylia-lib");
                if (version != null) return version;
            }
        } catch (IOException ignored) {
            // Falls through to the failure below.
        }
        throw new IllegalStateException("plugin.yml names no exylia-lib version");
    }

    static boolean hasLibraryJar(Path plugins) throws IOException {
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(plugins, "ExyliaLib*.jar")) {
            return jars.iterator().hasNext();
        }
    }

    /** A top-level key's value; an indented one belongs to a nested section. */
    private static String topLevel(BufferedReader reader, String key) {
        return reader.lines()
                .filter(line -> line.startsWith(key + ":"))
                .findFirst()
                .map(line -> line.substring(key.length() + 1).strip().replace("'", "").replace("\"", ""))
                .orElse(null);
    }
}
