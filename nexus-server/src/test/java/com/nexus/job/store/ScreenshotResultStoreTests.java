package com.nexus.job.store;

import com.nexus.job.config.ScreenshotJobProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ScreenshotResultStoreTests {
    Path temporaryDirectory;

    @BeforeEach
    void createTemporaryDirectory() throws Exception {
        temporaryDirectory = Path.of("target", "test-data", "screenshot-result-store",
                UUID.randomUUID().toString()).toAbsolutePath();
        Files.createDirectories(temporaryDirectory);
    }

    @AfterEach
    void deleteTemporaryDirectory() throws Exception {
        if (!Files.exists(temporaryDirectory)) {
            return;
        }
        try (var paths = Files.walk(temporaryDirectory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void atomicallySavedResultCanBeReadAndDeletedIdempotently() throws Exception {
        ScreenshotResultStore store = new ScreenshotResultStore(
                new ScreenshotJobProperties(temporaryDirectory.resolve("results").toString()));
        String jobId = UUID.randomUUID().toString();
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

        StoredScreenshot stored = store.saveAtomically(jobId, png);
        long fileCount;
        try (var files = Files.list(temporaryDirectory.resolve("results"))) {
            fileCount = files.count();
        }

        assertAll(
                () -> assertEquals(png.length, stored.sizeBytes()),
                () -> assertEquals(HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(png)), stored.sha256()),
                () -> assertArrayEquals(png, store.read(jobId)),
                () -> assertEquals(1, fileCount)
        );
        assertTrue(store.deleteIfExists(jobId));
        assertFalse(store.deleteIfExists(jobId));
    }

    @Test
    void nonCanonicalJobIdCannotAddressAFile() {
        ScreenshotResultStore store = new ScreenshotResultStore(
                new ScreenshotJobProperties(temporaryDirectory.toString()));

        assertThrows(IllegalArgumentException.class,
                () -> store.saveAtomically("../outside", new byte[]{1}));
        assertThrows(IllegalArgumentException.class,
                () -> store.read("1-1-1-1-1"));
        assertThrows(IllegalArgumentException.class,
                () -> store.deleteIfExists(UUID.randomUUID().toString().toUpperCase()));
    }
}
