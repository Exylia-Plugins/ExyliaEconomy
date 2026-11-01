package net.exylia.exyliaEconomy.database;

import net.exylia.lib.database.DataKind;
import net.exylia.lib.database.Column;
import net.exylia.lib.database.Id;
import net.exylia.lib.database.Table;

import java.util.UUID;

/**
 * One player whose balance an import already paid, so running it again never pays them twice.
 *
 * @param id         {@code from>into|player}
 * @param importedAt when it was claimed for paying; {@code 0} while nobody has claimed it
 */
@Table(value = "exylia_balance_imports", data = DataKind.SETUP)
public record ImportedRow(
        @Id(length = 160) String id,
        @Column("imported_at") long importedAt) {

    public static String id(String from, String into, UUID player) {
        return from + ">" + into + "|" + player;
    }
}
