package net.exylia.exyliaEconomy.bootstrap;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
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

    static final String RELEASES_URL = "https://github.com/DiGround-s/ExyliaLib/releases";
    /** The release this plugin was built against, never {@code latest}: a known jar, not whatever was pushed last. */
    static final String DOWNLOAD_URL = RELEASES_URL + "/download/v%1$s/ExyliaLib-%1$s.jar";
    private static final String JAR_NAME = "ExyliaLib.jar";
    /** The whole download, not each read: a server that trickles bytes still gives up in time. */
    private static final long DEADLINE_MILLIS = 60_000L;
    private static final long MAX_BYTES = 64L * 1024 * 1024;

    private ExyliaLibInstaller() {
    }

    /**
     * Downloads ExyliaLib unless some ExyliaLib jar is already there and simply failed to load.
     *
     * <p>On a thread of its own, so a slow GitHub never holds up the server's start; it says when
     * it is done. The plugin itself stays off until the restart either way.
     */
    public static void install(JavaPlugin plugin) {
        Logger log = plugin.getLogger();
        String name = plugin.getName();
        Path plugins = plugin.getDataFolder().toPath().toAbsolutePath().getParent();
        String version;
        try {
            version = requiredVersion(plugin);
            if (hasLibraryJar(plugins)) {
                log.severe("ExyliaLib is in plugins/ but did not load. Check the console for its errors"
                        + " above, fix them and restart the server to start " + name + ".");
                return;
            }
        } catch (IOException | RuntimeException unreadable) {
            log.severe("Could not install ExyliaLib (" + unreadable.getMessage() + "). Download it from "
                    + RELEASES_URL + ", put it in plugins/ and restart the server.");
            return;
        }
        String url = String.format(DOWNLOAD_URL, version);
        log.warning("ExyliaLib " + version + " is required and not installed; downloading it from " + url + ".");
        Thread download = new Thread(() -> {
            try {
                if (download(plugins, name, url, version)) {
                    log.warning("Downloaded ExyliaLib " + version + " to plugins/" + JAR_NAME
                            + " - restart the server to finish installing " + name + ".");
                } else {
                    log.warning("Another plugin installed ExyliaLib " + version + " or newer meanwhile"
                            + " - restart the server to finish installing " + name + ".");
                }
            } catch (Exception failure) {
                log.log(Level.SEVERE, "ExyliaLib is required and could not be downloaded (" + failure.getMessage()
                        + "). Download ExyliaLib " + version + " or newer from " + RELEASES_URL
                        + ", put it in plugins/ and restart the server.");
            }
        }, name + "-ExyliaLib-download");
        download.setDaemon(true);
        download.start();
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

    /**
     * Downloads the jar and puts it in place.
     *
     * <p>Every Exylia plugin carries this installer, so several may run at
     * once. The jar is only linked in where none exists — atomically, so two
     * never overwrite each other — and only replaces one that is older than
     * this plugin needs.
     *
     * @return whether this download was installed; {@code false} when another
     *         plugin already put a recent enough jar there
     */
    private static boolean download(Path plugins, String pluginName, String url, String version) throws IOException {
        long deadline = System.currentTimeMillis() + DEADLINE_MILLIS;
        Path tmp = Files.createTempFile(plugins, "ExyliaLib-" + version + "-", ".download");
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(15_000);
            connection.setRequestProperty("User-Agent", pluginName + "-Bootstrap");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code + " from " + url);
            try (InputStream in = connection.getInputStream(); OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                for (int read; (read = in.read(buffer)) >= 0; ) {
                    total += read;
                    if (total > MAX_BYTES) throw new IOException("the download is larger than " + MAX_BYTES + " bytes");
                    if (System.currentTimeMillis() > deadline) {
                        throw new IOException("the download took longer than " + DEADLINE_MILLIS / 1000 + " seconds");
                    }
                    out.write(buffer, 0, read);
                }
            } finally {
                connection.disconnect();
            }
            if (!isExyliaLibJar(tmp, version)) {
                throw new IOException("the download is not ExyliaLib " + version + " or newer");
            }
            Path target = plugins.resolve(JAR_NAME);
            if (place(tmp, target)) return true;
            if (isExyliaLibJar(target, version)) return false;
            // ponytail: two installers both newer than the jar there may each
            // replace it, last one winning; ExyliaLib updates itself on the next start.
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Puts the download in place only where nothing is, and answers whether it did. */
    private static boolean place(Path tmp, Path target) throws IOException {
        try {
            // A hard link fails when the target exists, in one step: a move cannot promise that.
            Files.createLink(target, tmp);
            return true;
        } catch (FileAlreadyExistsException taken) {
            return false;
        } catch (UnsupportedOperationException | IOException noLinks) {
            try {
                Files.move(tmp, target);
                return true;
            } catch (FileAlreadyExistsException taken) {
                return false;
            }
        }
    }

    /**
     * Whether the file is a readable jar whose plugin.yml names ExyliaLib, at the required version or newer.
     *
     * <p>No checksum: the jar on GitHub is built by its release workflow, so no hash of it exists
     * when this plugin is built. The pinned URL and this check are what stands in for one.
     */
    static boolean isExyliaLibJar(Path file, String required) {
        try (JarFile jar = new JarFile(file.toFile())) {
            ZipEntry entry = jar.getEntry("plugin.yml");
            if (entry == null) return false;
            List<String> lines;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8))) {
                lines = reader.lines().toList();
            }
            String name = topLevel(lines, "name");
            String version = topLevel(lines, "version");
            return "ExyliaLib".equals(name) && version != null && compare(version, required) >= 0;
        } catch (IOException unreadable) {
            return false;
        }
    }

    /** A top-level key's value; an indented one belongs to a nested section. */
    private static String topLevel(BufferedReader reader, String key) {
        return topLevel(reader.lines().toList(), key);
    }

    private static String topLevel(List<String> lines, String key) {
        return lines.stream()
                .filter(line -> line.startsWith(key + ":"))
                .findFirst()
                .map(line -> line.substring(key.length() + 1).strip().replace("'", "").replace("\"", ""))
                .orElse(null);
    }

    /** Compares dotted versions number by number; a part that is not a number counts as zero. */
    static int compare(String left, String right) {
        String[] a = left.split("[.-]");
        String[] b = right.split("[.-]");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int difference = Long.compare(part(a, i), part(b, i));
            if (difference != 0) return difference;
        }
        return 0;
    }

    private static long part(String[] parts, int index) {
        try {
            return index < parts.length ? Long.parseLong(parts[index]) : 0L;
        } catch (NumberFormatException notANumber) {
            return 0L;
        }
    }
}
