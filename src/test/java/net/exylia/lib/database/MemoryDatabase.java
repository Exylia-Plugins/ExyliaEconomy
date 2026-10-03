package net.exylia.lib.database;

import net.exylia.lib.database.internal.SqlSettings;
import org.bukkit.plugin.Plugin;

/**
 * Points a test plugin at an in-memory H2 database.
 *
 * <p>Lives in the library's package because its installer is package-private.
 * Plugins given the same name share one database, which is how a test stands
 * up several servers on the same tables.
 */
public final class MemoryDatabase {

    private MemoryDatabase() {
    }

    public static void install(Plugin plugin, String name) {
        Databases.installForTests(plugin, SqlSettings.memory("h2", name));
    }
}
