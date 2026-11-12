package com.mobiletools.arrears.dataingestion.ledger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/** Stores immutable workbooks by content hash so attempts can share exact bytes. */
@Component
public class LedgerOriginalWorkbookStore {

    private final Path root;

    public LedgerOriginalWorkbookStore(
            @Value("${app.ledger.original-workbook-directory:data/original-workbooks}") String directory) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }

    public StoredWorkbook store(byte[] bytes) throws IOException {
        StoredWorkbook identified = identify(bytes);
        String sha256 = identified.sha256();
        String storageKey = identified.storageKey();
        Path target = resolve(storageKey);
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) {
            verify(target, bytes, sha256);
            return new StoredWorkbook(sha256, storageKey, bytes.length);
        }

        Path temporary = Files.createTempFile(target.getParent(), ".workbook-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target);
            } catch (FileAlreadyExistsException exception) {
                verify(target, bytes, sha256);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        verify(target, bytes, sha256);
        return identified;
    }

    /** Computes a safe content address without writing the workbook to disk. */
    public StoredWorkbook identify(byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("原始工作簿不能为空");
        String sha256 = sha256(bytes);
        return new StoredWorkbook(sha256, sha256.substring(0, 2) + "/" + sha256, bytes.length);
    }

    public byte[] retrieve(String storageKey) throws IOException {
        Path path = resolve(storageKey);
        byte[] bytes = Files.readAllBytes(path);
        String expectedHash = Path.of(storageKey).getFileName().toString();
        if (!sha256(bytes).equals(expectedHash)) throw new IOException("原始工作簿校验失败");
        return bytes;
    }

    /** Delete an immutable content-addressed workbook idempotently. */
    public boolean delete(String storageKey) throws IOException {
        Path path = resolve(storageKey);
        return Files.deleteIfExists(path);
    }

    private Path resolve(String storageKey) throws IOException {
        if (storageKey == null || !storageKey.matches("[0-9a-f]{2}/[0-9a-f]{64}")) {
            throw new IOException("原始工作簿存储键无效");
        }
        Path path = root.resolve(storageKey).normalize();
        if (!path.startsWith(root)) throw new IOException("原始工作簿存储键无效");
        return path;
    }

    private void verify(Path path, byte[] expected, String sha256) throws IOException {
        byte[] actual = Files.readAllBytes(path);
        if (actual.length != expected.length || !Arrays.equals(actual, expected)
                || !sha256(actual).equals(sha256)) {
            throw new IOException("已存在的原始工作簿与 SHA-256 内容不一致");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 运行环境不支持 SHA-256", exception);
        }
    }

    public record StoredWorkbook(String sha256, String storageKey, long byteSize) {}
}
