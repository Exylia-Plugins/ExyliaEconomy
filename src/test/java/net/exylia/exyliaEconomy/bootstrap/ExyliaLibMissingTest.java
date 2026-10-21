package net.exylia.exyliaEconomy.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExyliaLibMissingTest {

    @TempDir
    Path dir;

    @Test
    void anyExyliaLibJarCountsAsInstalled() throws IOException {
        assertFalse(ExyliaLibMissing.hasLibraryJar(dir));
        Files.createFile(dir.resolve("ExyliaLib-1.230.0.jar"));
        assertTrue(ExyliaLibMissing.hasLibraryJar(dir));
    }
}
