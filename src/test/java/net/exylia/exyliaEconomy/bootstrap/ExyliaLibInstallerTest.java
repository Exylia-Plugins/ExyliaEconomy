package net.exylia.exyliaEconomy.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExyliaLibInstallerTest {

    @TempDir
    Path dir;

    @Test
    void acceptsOnlyAJarWhosePluginYmlNamesExyliaLibAtTheRequiredVersion() throws IOException {
        assertTrue(ExyliaLibInstaller.isExyliaLibJar(jar("lib.jar", "name: ExyliaLib\nversion: '1.236.0'\n"), "1.236.0"));
        assertTrue(ExyliaLibInstaller.isExyliaLibJar(jar("newer.jar", "version: 1.240.2\nname: 'ExyliaLib'\n"), "1.236.0"));
        assertFalse(ExyliaLibInstaller.isExyliaLibJar(jar("older.jar", "name: ExyliaLib\nversion: '1.99.0'\n"), "1.236.0"));
        assertFalse(ExyliaLibInstaller.isExyliaLibJar(jar("unversioned.jar", "name: ExyliaLib\n"), "1.236.0"));
        assertFalse(ExyliaLibInstaller.isExyliaLibJar(jar("other.jar", "name: SomethingElse\nversion: 9\n"), "1.0"));
        assertFalse(ExyliaLibInstaller.isExyliaLibJar(jar("nested.jar", "name: Other\nx:\n  name: ExyliaLib\n"), "1.0"));
        assertFalse(ExyliaLibInstaller.isExyliaLibJar(jar("empty.jar", null), "1.0"));
        Path html = Files.writeString(dir.resolve("page.jar"), "<html>not found</html>");
        assertFalse(ExyliaLibInstaller.isExyliaLibJar(html, "1.0"));
    }

    @Test
    void anyExyliaLibJarCountsAsInstalled() throws IOException {
        assertFalse(ExyliaLibInstaller.hasLibraryJar(dir));
        Files.createFile(dir.resolve("ExyliaLib-1.230.0.jar"));
        assertTrue(ExyliaLibInstaller.hasLibraryJar(dir));
    }

    private Path jar(String name, String pluginYml) throws IOException {
        Path file = dir.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry(pluginYml == null ? "readme.txt" : "plugin.yml"));
            out.write((pluginYml == null ? "x" : pluginYml).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return file;
    }
}
