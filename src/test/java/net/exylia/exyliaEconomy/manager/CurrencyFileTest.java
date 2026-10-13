package net.exylia.exyliaEconomy.manager;

import net.exylia.exyliaEconomy.testing.TestServer;
import net.exylia.lib.debug.Debug;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code currencies.yml}: the defaults a fresh server gets and the owner's edits.
 */
class CurrencyFileTest {

    private static final Debug DEBUG = TestServer.debug();

    @TempDir
    Path plugins;

    private File folder;
    private Path file;

    @BeforeEach
    void setUp() {
        folder = plugins.resolve("ExyliaEconomy").toFile();
        file = folder.toPath().resolve(CurrencyFile.FILE);
    }

    @Test
    @DisplayName("a fresh server gets the defaults, and they read back")
    void defaults() {
        CurrencyFile.Contents read = CurrencyFile.load(folder, DEBUG);

        assertTrue(Files.exists(file));
        assertEquals(2, read.stored().size());
        CurrencyFile.Stored dollars = read.stored().get("dollars");
        assertNotNull(dollars);
        assertEquals(2, dollars.info().decimals());
        assertEquals("$", dollars.info().symbol());
        CurrencyFile.Stored shards = read.stored().get("shards");
        assertNotNull(shards);
        assertEquals("Shards", shards.info().namePlural());
        assertEquals("✦", shards.info().symbol());
        assertEquals(0, shards.info().decimals());
        assertTrue(shards.aliases().contains("shards"));
        assertTrue(shards.transferable());
        assertFalse(shards.exchangeable());
        assertTrue(shards.rates().isEmpty());
        assertEquals(1, read.items().size());
        assertEquals("NETHERITE_INGOT", read.items().get("netherite_ingots").item());
        assertFalse(read.experienceLevels());
        assertTrue(read.experiencePoints());
        assertEquals("dollars", read.vaultProvide());
        assertFalse(read.vaultForce());
        assertNotNull(read.overlay("vault"));
        assertEquals("$", read.overlay("vault").symbol());
        assertNotNull(read.overlay("points"));
    }

    @Test
    @DisplayName("the file is the owner's: an edit survives, a bad block is skipped")
    void edits() throws Exception {
        CurrencyFile.load(folder, DEBUG);
        String yaml = Files.readString(file)
                .replace("    name: Shard\n", "    name: Buck\n")
                .replace("stored:\n", "stored:\n  bad id!:\n    name: Nope\n");
        Files.writeString(file, yaml);

        CurrencyFile.Contents read = CurrencyFile.load(folder, DEBUG);
        assertEquals("Buck", read.stored().get("shards").info().name());
        assertEquals(2, read.stored().size());
    }
}
