package net.exylia.exyliaEconomy.bootstrap;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;

/**
 * Puts ExyliaLib in {@code plugins/} when the server does not have it.
 *
 * <p>Plain JDK and Bukkit on purpose: it runs exactly when ExyliaLib is missing, so it cannot touch
 * a single ExyliaLib type. From the next start on, ExyliaLib keeps itself and this plugin updated.
 */
public final class ExyliaLibInstaller {

    static final String DOWNLOAD_URL = "https://github.com/DiGround-s/ExyliaLib/releases/latest/download/ExyliaLib.jar";
    static final String RELEASES_URL = "https://github.com/DiGround-s/ExyliaLib/releases/latest";
    private static final String JAR_NAME = "ExyliaLib.jar";

    private ExyliaLibInstaller() {
    }

    /**
     * Downloads ExyliaLib unless some ExyliaLib jar is already there and simply failed to load.
     *
     * <p>Synchronous: it is a one-time first install, bounded by the connection timeouts.
     */
    public static void install(JavaPlugin plugin) {
        Logger log = plugin.getLogger();
        String name = plugin.getName();
        Path plugins = plugin.getDataFolder().toPath().toAbsolutePath().getParent();
        try {
            if (hasLibraryJar(plugins)) {
                log.severe("ExyliaLib is in plugins/ but did not load. Check the console for its errors"
                        + " above, fix them and restart the server to start " + name + ".");
                return;
            }
            download(plugins, name);
            log.warning("ExyliaLib is required and was not installed. Downloaded it to plugins/" + JAR_NAME
                    + " - restart the server to finish installing " + name + ".");
        } catch (Exception failure) {
            log.log(Level.SEVERE, "ExyliaLib is required and could not be downloaded (" + failure.getMessage()
                    + "). Download ExyliaLib.jar from " + RELEASES_URL + ", put it in plugins/ and restart the server.");
        }
    }

    static boolean hasLibraryJar(Path plugins) throws IOException {
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(plugins, "ExyliaLib*.jar")) {
            return jars.iterator().hasNext();
        }
    }

    private static void download(Path plugins, String pluginName) throws IOException {
        Path tmp = Files.createTempFile(plugins, "ExyliaLib", ".download");
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(DOWNLOAD_URL).toURL().openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setRequestProperty("User-Agent", pluginName + "-Bootstrap");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code + " from " + DOWNLOAD_URL);
            try (InputStream in = connection.getInputStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!isExyliaLibJar(tmp)) throw new IOException("the download is not an ExyliaLib jar");
            Path target = plugins.resolve(JAR_NAME);
            // An ExyliaLib jar that appeared meanwhile is never overwritten.
            if (Files.exists(target)) throw new IOException(JAR_NAME + " appeared during the download");
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(tmp, target);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Whether the file is a readable jar whose plugin.yml names ExyliaLib. */
    static boolean isExyliaLibJar(Path file) {
        try (JarFile jar = new JarFile(file.toFile())) {
            ZipEntry entry = jar.getEntry("plugin.yml");
            if (entry == null) return false;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8))) {
                // Top-level key only: an indented name: belongs to a nested section.
                return reader.lines()
                        .filter(line -> line.startsWith("name:"))
                        .findFirst()
                        .map(line -> line.substring(5).strip().replace("'", "").replace("\"", ""))
                        .filter("ExyliaLib"::equals)
                        .isPresent();
            }
        } catch (IOException unreadable) {
            return false;
        }
    }
}
