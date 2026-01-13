package com.metl_group.smart_item_deleter_v2.util;

import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class LogFiles {
    private static final String LOG_DIR_NAME = "sidV2";
    private static final DateTimeFormatter ARCHIVE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private LogFiles() {}

    public static Path logDir() {
        return FMLPaths.GAMEDIR.get().resolve("logs").resolve(LOG_DIR_NAME);
    }

    public static Path cleanupLogPath() {
        return logDir().resolve("cleanup.log");
    }

    public static Path statsLogPath() {
        return logDir().resolve("stats.log");
    }

    public static void rotateExistingLogs() {
        rotateLog(cleanupLogPath());
        rotateLog(statsLogPath());
    }

    private static void rotateLog(Path logPath) {
        if (!Files.exists(logPath)) {
            return;
        }

        try {
            if (Files.size(logPath) == 0L) {
                return;
            }
        } catch (IOException ignored) {
            return;
        }

        String baseName = logPath.getFileName().toString();
        String stem = baseName.endsWith(".log")
                ? baseName.substring(0, baseName.length() - 4)
                : baseName;
        String timestamp = LocalDateTime.now().format(ARCHIVE_FORMAT);
        Path zipPath = logPath.getParent().resolve(stem + "-" + timestamp + ".zip");

        try {
            Files.createDirectories(logPath.getParent());
            try (ZipOutputStream out = new ZipOutputStream(
                    Files.newOutputStream(zipPath, StandardOpenOption.CREATE_NEW))) {
                out.putNextEntry(new ZipEntry(baseName));
                Files.copy(logPath, out);
                out.closeEntry();
            }
            Files.deleteIfExists(logPath);
        } catch (IOException ignored) {
            // Avoid console spam if log rotation fails.
        }
    }
}
