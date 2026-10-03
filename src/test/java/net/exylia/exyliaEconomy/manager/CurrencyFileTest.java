package net.exylia.exyliaEconomy.manager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code currencies.yml}: the defaults a fresh server gets, the owner's edits,
 * and the copy the survival core kept before the currencies moved here.
 */
class CurrencyFileTest {

    private static final Logger LOGGER = Logger.getLogger("test");

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
        CurrencyFile.Contents read = CurrencyFile.load(folder, LOGGER);

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
        CurrencyFile.load(folder, LOGGER);
        String yaml = Files.readString(file)
                .replace("    name: Shard\n", "    name: Buck\n")
                .replace("stored:\n", "stored:\n  bad id!:\n    name: Nope\n");
        Files.writeString(file, yaml);

        CurrencyFile.Contents read = CurrencyFile.load(folder, LOGGER);
        assertEquals("Buck", read.stored().get("shards").info().name());
        assertEquals(2, read.stored().size());
    }

    @Test
    @DisplayName("the survival core's file is copied over once, with the owner's edits, and left where it was")
    void adoptsTheSurvivalCoreFile() throws Exception {
        Path core = plugins.resolve(CurrencyFile.SURVIVAL_CORE);
        Files.createDirectories(core.getParent());
        Files.writeString(core, "stored:\n  coins:\n    name: Coin\nvault:\n  provide: coins\n");

        CurrencyFile.Contents read = CurrencyFile.load(folder, LOGGER);
        assertEquals(java.util.Set.of("coins"), read.stored().keySet());
        assertEquals("coins", read.vaultProvide());
        assertEquals(Files.readString(core), Files.readString(file));
        assertTrue(Files.exists(core), "the old file is the backup nobody had to take");

        // Copied once: what the owner edits here from now on is what is read.
        Files.writeString(core, "stored: {}\n");
        assertEquals(1, CurrencyFile.load(folder, LOGGER).stored().size());
    }
}
