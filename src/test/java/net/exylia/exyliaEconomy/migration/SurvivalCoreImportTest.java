package net.exylia.exyliaEconomy.migration;

import net.exylia.exyliaEconomy.testing.TestServer;
import net.exylia.lib.debug.Debug;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The survival core's database.yml, copied so this plugin opens the very same database. */
class SurvivalCoreImportTest {

    private static final Debug DEBUG = TestServer.debug();

    @TempDir
    Path plugins;

    @Test
    @DisplayName("an embedded file is pointed back at the survival core's folder, and resolves to the same path")
    void embeddedFile() throws Exception {
        File source = plugins.resolve("ExyliaSurvivalCore").toFile();
        File folder = plugins.resolve("ExyliaEconomy").toFile();
        Files.createDirectories(source.toPath());
        Files.writeString(source.toPath().resolve("database.yml"),
                "database:\n  type: h2\n  h2:\n    file: data/core\n  redis:\n    server-id: lobby-1\n");

        SurvivalCoreImport.database(source, folder, DEBUG);

        YamlConfiguration copied = YamlConfiguration.loadConfiguration(new File(folder, "database.yml"));
        String file = copied.getString("database.h2.file");
        assertEquals("lobby-1", copied.getString("database.redis.server-id"));
        assertEquals(source.toPath().resolve("data/core").normalize(), folder.toPath().resolve(file).normalize());
    }

    @Test
    @DisplayName("with no H2 file to copy, the embedded path still points at the survival core's database")
    void defaultAndAbsolute() throws Exception {
        File source = plugins.resolve("ExyliaSurvivalCore").toFile();
        File folder = plugins.resolve("ExyliaEconomy").toFile();
        Files.createDirectories(source.toPath());
        Files.writeString(source.toPath().resolve("database.yml"), "database:\n  type: mysql\n");
        SurvivalCoreImport.database(source, folder, DEBUG);
        String file = YamlConfiguration.loadConfiguration(new File(folder, "database.yml")).getString("database.h2.file");
        assertEquals(source.toPath().resolve("database/h2").normalize(), folder.toPath().resolve(file).normalize());
        assertEquals("mysql", YamlConfiguration.loadConfiguration(new File(folder, "database.yml")).getString("database.type"));

        // Ours exists: never overwritten.
        Files.writeString(source.toPath().resolve("database.yml"), "database:\n  type: postgresql\n");
        SurvivalCoreImport.database(source, folder, DEBUG);
        assertFalse(Files.readString(folder.toPath().resolve("database.yml")).contains("postgresql"));
    }

    @Test
    @DisplayName("an embedded H2 file is copied here and opened here, so the survival core's folder can go")
    void embeddedFileIsCopied() throws Exception {
        File source = plugins.resolve("ExyliaSurvivalCore").toFile();
        File folder = plugins.resolve("ExyliaEconomy").toFile();
        Files.createDirectories(source.toPath().resolve("database"));
        Files.writeString(source.toPath().resolve("database.yml"), "database:\n  type: h2\n");
        Files.writeString(source.toPath().resolve("database/h2.mv.db"), "balances");

        SurvivalCoreImport.database(source, folder, DEBUG);

        String file = YamlConfiguration.loadConfiguration(new File(folder, "database.yml")).getString("database.h2.file");
        assertEquals(folder.toPath().resolve("database/h2").normalize(), folder.toPath().resolve(file).normalize());
        assertEquals("balances", Files.readString(folder.toPath().resolve("database/h2.mv.db")));
        assertTrue(Files.exists(source.toPath().resolve("database/h2.mv.db")), "the survival core's file was touched");
    }

    @Test
    @DisplayName("a database.yml an earlier version pointed at the survival core gets its own copy")
    void earlierImportIsRelocated() throws Exception {
        File source = plugins.resolve("ExyliaSurvivalCore").toFile();
        File folder = plugins.resolve("ExyliaEconomy").toFile();
        Files.createDirectories(source.toPath().resolve("database"));
        Files.createDirectories(folder.toPath());
        Files.writeString(source.toPath().resolve("database/h2.mv.db"), "balances");
        Files.writeString(folder.toPath().resolve("database.yml"),
                "database:\n  type: h2\n  h2:\n    file: ../ExyliaSurvivalCore/database/h2\n");

        SurvivalCoreImport.relocate(folder, DEBUG);

        assertEquals("database/h2", YamlConfiguration.loadConfiguration(new File(folder, "database.yml"))
                .getString("database.h2.file"));
        assertEquals("balances", Files.readString(folder.toPath().resolve("database/h2.mv.db")));

        // Already ours: nothing more to do, and nothing written over.
        Files.writeString(folder.toPath().resolve("database/h2.mv.db"), "newer");
        SurvivalCoreImport.relocate(folder, DEBUG);
        assertEquals("newer", Files.readString(folder.toPath().resolve("database/h2.mv.db")));
    }
}
