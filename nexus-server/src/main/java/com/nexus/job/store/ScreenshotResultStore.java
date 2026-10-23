package com.nexus.job.store;

import com.nexus.job.config.ScreenshotJobProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

@Component
public class ScreenshotResultStore {
    private final Path resultDirectory;

    public ScreenshotResultStore(ScreenshotJobProperties properties) {
        // 先按普通配置字符串绑定，再在脱离 ServletContext 的文件系统边界解析路径。
        this.resultDirectory = Path.of(properties.resultDirectory()).toAbsolutePath().normalize();
    }

    public StoredScreenshot saveAtomically(String jobId, byte[] png) {
        Objects.requireNonNull(png, "png");
        if (png.length == 0) {
            throw new IllegalArgumentException("png must not be empty");
        }
        Path result = resultPath(jobId);
        Path temporary = null;
        try {
            Files.createDirectories(resultDirectory);
            temporary = Files.createTempFile(resultDirectory, jobId + ".", ".tmp");
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(png);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temporary, result,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            return new StoredScreenshot(png.length, sha256(png));
        } catch (IOException exception) {
            throw new ScreenshotResultStoreException();
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 临时文件名不含 URL 或凭据；后续目录运维可清理异常退出残留。
                }
            }
        }
    }

    public byte[] read(String jobId) {
        try {
            return Files.readAllBytes(resultPath(jobId));
        } catch (IOException exception) {
            throw new ScreenshotResultStoreException();
        }
    }

    public boolean deleteIfExists(String jobId) {
        try {
            return Files.deleteIfExists(resultPath(jobId));
        } catch (IOException exception) {
            throw new ScreenshotResultStoreException();
        }
    }

    private Path resultPath(String jobId) {
        requireCanonicalUuid(jobId);
        Path result = resultDirectory.resolve(jobId + ".png").normalize();
        if (!result.startsWith(resultDirectory)) {
            throw new IllegalArgumentException("jobId resolves outside result directory");
        }
        return result;
    }

    private static void requireCanonicalUuid(String value) {
        if (value == null) {
            throw new IllegalArgumentException("jobId must be a UUID");
        }
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException("jobId must be a canonical UUID");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("jobId must be a canonical UUID");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
