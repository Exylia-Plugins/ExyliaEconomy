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
    void acceptsOnlyAJarWhosePluginYmlNamesExyliaLib() throws IOException {
        assertTrue(ExyliaLibInstaller.isExyliaLibJar(jar("lib.jar", "name: ExyliaLib\nversion: '1.0'\n")));
        assertTrue(ExyliaLibInstaller.isExyliaLibJar(jar("quoted.jar", "version: 1\nname: 'ExyliaLib'\n")));
        assertFalse(ExyliaLibInstaller.isExyliaLibJar(jar("other.jar", "name: SomethingElse\n")));
        assertFalse(ExyliaLibInstaller.isExyliaLibJar(jar("nested.jar", "name: Other\nx:\n  name: ExyliaLib\n")));
        assertFalse(ExyliaLibInstaller.isExyliaLibJar(jar("empty.jar", null)));
        Path html = Files.writeString(dir.resolve("page.jar"), "<html>not found</html>");
        assertFalse(ExyliaLibInstaller.isExyliaLibJar(html));
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
