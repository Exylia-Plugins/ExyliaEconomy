package net.exylia.exyliaEconomy.service;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Two senders that are not players never share an open question. */
class ConfirmKeyTest {

    private static CommandSender named(String name) {
        return (CommandSender) Proxy.newProxyInstance(ConfirmKeyTest.class.getClassLoader(),
                new Class<?>[]{CommandSender.class}, (self, method, args) -> "getName".equals(method.getName()) ? name : null);
    }

    @Test
    @DisplayName("non-player senders are keyed by their name")
    void distinctSenders() {
        assertNotEquals(EconomyActions.confirmKey(named("Rcon")), EconomyActions.confirmKey(named("Discord")));
        assertNotEquals("console", EconomyActions.confirmKey(named("Rcon")));
    }
}
