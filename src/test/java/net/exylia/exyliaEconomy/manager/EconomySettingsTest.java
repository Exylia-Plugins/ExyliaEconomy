package net.exylia.exyliaEconomy.manager;

import net.exylia.exyliaEconomy.config.EconomyMessages;
import net.exylia.exyliaEconomy.config.EconomyMessages;
import net.exylia.exyliaEconomy.database.EconomySettingsRow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomySettingsTest {

    @Test
    void aRowFromBeforeTheColumnKeepsTheDefaultAndTheCycleEndsInForever() {
        EconomySettingsRow row = new EconomySettingsRow(false, true, "dollars", false, true);
        assertEquals(EconomySettingsRow.DEFAULT_LEDGER_DAYS, row.keptLedgerDays());
        row = row.withNextLedgerDays();
        assertEquals(180, row.keptLedgerDays());
        row = row.withNextLedgerDays().withNextLedgerDays();
        assertEquals(-1, row.keptLedgerDays());
        assertEquals(30, row.withNextLedgerDays().keptLedgerDays());
        assertEquals(-1, row.withLedger(false).keptLedgerDays(), "other switches keep the retention");
    }

    @Test
    void vaultIsOnTopUntilAnAdminTurnsItOff() {
        EconomySettingsRow row = new EconomySettingsRow(false, true, "dollars", false, true);
        assertTrue(row.vaultForce(), "a new row, or one from before the column, is on top");
        EconomySettingsRow off = row.withVaultForce(!row.vaultForce());
        assertFalse(off.vaultForce());
        assertFalse(off.withLedger(false).withNextLanguage().vaultForce(), "other switches keep the choice");
        assertTrue(off.withVaultForce(true).vaultForce());
    }

    @Test
    void anImportIsRememberedPerPair() {
        EconomySettingsRow row = new EconomySettingsRow(false, true, "dollars", false, true);
        assertFalse(row.imported("vault", "dollars"));
        EconomySettingsRow marked = row.withImport("vault", "dollars").withImport("playerpoints", "gems");
        assertTrue(marked.imported("vault", "dollars"));
        assertTrue(marked.imported("PlayerPoints", "gems"));
        assertFalse(marked.imported("vault", "gems"));
        assertSame(marked, marked.withImport("vault", "dollars"));
        assertTrue(marked.withVaultForce(true).imported("vault", "dollars"));
    }

    @Test
    void reasonsReadAsWordsWhateverModuleWroteThem() {
        EconomyMessages text = new EconomyMessages();
        assertEquals("Shop sale", text.label(text.reasons(), "shop:sell"));
        assertEquals("Auctions", text.label(text.reasons(), "auctions:outbid"));
        assertEquals("Exchange", text.label(text.reasons(), "exchange:gems>dollars"));
        assertEquals("Kill rewards", text.label(text.reasons(), "kill-rewards"));
        assertEquals("Server currency", text.label(text.kinds(), "stored"));
    }
}
