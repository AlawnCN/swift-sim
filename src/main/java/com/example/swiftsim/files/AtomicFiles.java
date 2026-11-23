package com.example.swiftsim.files;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * Publish a complete file under a new name without replacing anything already in the directory.
 * Bytes are written to a dot-file (ignored by the inbound scanner) and fsynced, then hard-linked
 * to the final name. The link fails if the name exists, so an existing file is never overwritten.
 * The temporary name is removed afterwards; the final directory entry appears only once the content is complete.
 */
public final class AtomicFiles {
    private AtomicFiles() {}

    public static Path publishNew(Path dir, String finalName, byte[] bytes) throws IOException {
        if (finalName == null || finalName.isBlank() || finalName.indexOf('/') >= 0 || finalName.indexOf('\\') >= 0
                || ".".equals(finalName) || "..".equals(finalName) || finalName.startsWith(".")) {
            throw new IOException("refusing unsafe output name " + finalName);
        }
        Path target = dir.resolve(finalName);
        if (target.getParent() == null || !target.getParent().equals(dir)) {
            throw new IOException("output name escapes directory: " + finalName);
        }
        if (Files.isSymbolicLink(target) || Files.exists(target)) throw new FileAlreadyExistsException(target.toString());
        Path tmp = dir.resolve(".partial-" + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ch.write(ByteBuffer.wrap(bytes));
                ch.force(true);
            }
            try {
                Files.createLink(target, tmp);
            } catch (FileAlreadyExistsException e) {
                throw e;
            } catch (UnsupportedOperationException | IOException e) {
                if (Files.isSymbolicLink(target) || Files.exists(target)) throw new FileAlreadyExistsException(target.toString());
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            }
            return target;
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // the final name already holds the bytes when the link or move succeeded
            }
        }
    }
}
