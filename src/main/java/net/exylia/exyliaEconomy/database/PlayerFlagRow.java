package net.exylia.exyliaEconomy.database;

import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Indexed;
import net.exylia.lib.database.Table;

import java.util.UUID;

/**
 * One on/off setting of one player, kept in the database so every server reads the same one.
 *
 * <p>The row existing is the flag being on: turning it off deletes the row, so there is never a
 * stale {@code false} to read and the table only ever holds the exceptions.
 *
 * @param id     {@code player|flag}
 * @param player the player's id
 * @param flag   which setting, such as {@code pay_off}
 */
@Table("exylia_economy_flags")
public record PlayerFlagRow(
        @Id(length = 80) String id,
        @Indexed @Column(length = 36) String player,
        @Indexed @Column(length = 32) String flag) {

    public PlayerFlagRow(UUID player, String flag) {
        this(id(player, flag), player.toString(), flag);
    }

    public static String id(UUID player, String flag) {
        return player + "|" + flag;
    }

    public UUID uuid() {
        return UUID.fromString(player);
    }
}
