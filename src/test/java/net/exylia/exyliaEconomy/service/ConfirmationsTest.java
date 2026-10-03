package net.exylia.exyliaEconomy.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A confirmation allows exactly the payment it was asked about, once, inside its window. */
class ConfirmationsTest {

    private static final String PAYER = "payer-" + System.nanoTime();
    private static final String ASKED = "pay|bob|coins|" + Confirmations.amount(new BigDecimal("2500"));

    @Test
    @DisplayName("the same payment confirms once, and only once")
    void confirmsOnce() {
        Confirmations.ask(PAYER, ASKED, 1_000L);
        assertTrue(Confirmations.confirm(PAYER, ASKED, 2_000L));
        assertFalse(Confirmations.confirm(PAYER, ASKED, 2_000L));
    }

    @Test
    @DisplayName("another receiver, currency, amount or payer confirms nothing")
    void boundToWhatWasAsked() {
        Confirmations.ask(PAYER, ASKED, 1_000L);
        assertFalse(Confirmations.confirm(PAYER, "pay|eve|coins|2500", 2_000L));
        assertFalse(Confirmations.confirm(PAYER, "pay|bob|gems|2500", 2_000L));
        assertFalse(Confirmations.confirm(PAYER, "pay|bob|coins|2501", 2_000L));
        assertFalse(Confirmations.confirm("someone-else", ASKED, 2_000L));
        // None of those used it up.
        assertTrue(Confirmations.confirm(PAYER, ASKED, 2_000L));
    }

    @Test
    @DisplayName("a confirmation past its window is refused")
    void expires() {
        Confirmations.ask(PAYER, ASKED, 1_000L);
        assertFalse(Confirmations.confirm(PAYER, ASKED, 1_000L + Confirmations.WINDOW_MILLIS + 1));
    }

    @Test
    @DisplayName("asking again replaces the open question")
    void latestWins() {
        Confirmations.ask(PAYER, ASKED, 1_000L);
        Confirmations.ask(PAYER, "pay|bob|coins|10", 1_500L);
        assertFalse(Confirmations.confirm(PAYER, ASKED, 2_000L));
        assertTrue(Confirmations.confirm(PAYER, "pay|bob|coins|10", 2_000L));
    }

    @Test
    @DisplayName("one amount written three ways binds the same")
    void amountSpellings() {
        assertEquals(Confirmations.amount(new BigDecimal("2500")), Confirmations.amount(new BigDecimal("2500.00")));
        assertEquals(Confirmations.amount(new BigDecimal("2500")), Confirmations.amount(new BigDecimal("2.5E3")));
    }
}
