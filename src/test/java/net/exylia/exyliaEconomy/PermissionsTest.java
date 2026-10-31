package net.exylia.exyliaEconomy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every node the plugin checks is declared, or the server defaults it to op and locks players out. */
class PermissionsTest {

    @Test
    @DisplayName("every permission node is declared in plugin.yml")
    void declared() throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/plugin.yml"));
        for (Field field : Permissions.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
            String node = (String) field.get(null);
            if (node.endsWith(".")) continue;
            assertTrue(yaml.contains("\n  " + node + ":\n"), node + " is missing from plugin.yml");
        }
    }
}
