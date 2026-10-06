package net.exylia.exyliaEconomy.manager;

import net.exylia.exyliaEconomy.database.CurrencyRow;
import net.exylia.exyliaEconomy.database.EconomySettingsRow;
import net.exylia.exyliaEconomy.testing.TestServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The currencies as rows: what the import writes reads back as what the file said. */
class CurrencyStoreTest {

    @TempDir
    Path plugins;

    @Test
    @DisplayName("a file imported into rows registers exactly what the file did")
    void importRoundTrip() throws Exception {
        java.io.File folder = plugins.resolve("ExyliaEconomy").toFile();
        CurrencyFile.load(folder, TestServer.debug());
        Path file = folder.toPath().resolve(CurrencyFile.FILE);
        // An overlay on a currency the file also defines must not replace it.
        Files.writeString(file, Files.readString(file).replace("display:\n", "display:\n  dollars:\n    name: Nope\n"));
        CurrencyFile.Contents read = CurrencyFile.load(folder, TestServer.debug());

        List<CurrencyRow> rows = CurrencyStore.rows(read);
        EconomySettingsRow settings = new EconomySettingsRow(read.experienceLevels(), read.experiencePoints(),
                read.vaultProvide(), read.vaultForce(), read.ledger());
        CurrencyFile.Contents back = CurrencyStore.contents(rows, settings);

        assertEquals(read.stored(), back.stored());
        assertEquals(read.items(), back.items());
        assertEquals(List.copyOf(read.stored().keySet()), List.copyOf(back.stored().keySet()));
        assertEquals("Dollar", back.stored().get("dollars").info().name());
        assertFalse(back.display().containsKey("dollars"));
        assertEquals(read.overlay("vault"), back.overlay("vault"));
        assertEquals(read.vaultProvide(), back.vaultProvide());
        assertEquals(read.experiencePoints(), back.experiencePoints());
    }

    @Test
    @DisplayName("an item currency without its item is not registered")
    void itemWithoutItem() {
        CurrencyRow row = CurrencyRow.blank("tokens", CurrencyRow.Kind.ITEM, 0);
        EconomySettingsRow settings = new EconomySettingsRow(false, false, "", false, true);
        assertTrue(CurrencyStore.contents(List.of(row), settings).items().isEmpty());
        CurrencyRow set = row.edit(draft -> draft.item = "EMERALD");
        assertEquals("EMERALD", CurrencyStore.contents(List.of(set), settings).items().get("tokens").item());
    }

    @Test
    @DisplayName("a missing settings row gives Vault the first stored currency")
    void missingSettingsPublishVault() {
        List<CurrencyRow> rows = List.of(
                CurrencyRow.blank("vault", CurrencyRow.Kind.DISPLAY, 0),
                CurrencyRow.blank("shards", CurrencyRow.Kind.STORED, 2),
                CurrencyRow.blank("dollars", CurrencyRow.Kind.STORED, 1));
        assertEquals("dollars", CurrencyStore.defaultSettings(rows).vaultProvide());
        assertEquals("", CurrencyStore.defaultSettings(List.of()).vaultProvide());
    }

    @Test
    @DisplayName("rates survive the column and accept what an admin types")
    void rates() {
        Map<String, BigDecimal> typed = CurrencyRow.decodeRates(" Shards = 0.010 , gems=2;");
        assertEquals(0, new BigDecimal("0.01").compareTo(typed.get("shards")));
        assertEquals(0, new BigDecimal("2").compareTo(typed.get("gems")));
        assertEquals("shards=0.01;gems=2", CurrencyRow.encodeRates(typed));
        assertEquals("shards=0.01;gems=2",
                CurrencyRow.encodeRates(CurrencyRow.decodeRates(CurrencyRow.encodeRates(typed))));
        assertTrue(CurrencyRow.decodeRates("").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> CurrencyRow.decodeRates("shards"));
        assertThrows(IllegalArgumentException.class, () -> CurrencyRow.decodeRates("shards=-1"));
        assertThrows(IllegalArgumentException.class, () -> CurrencyRow.decodeRates("shards=abc"));
    }
}
