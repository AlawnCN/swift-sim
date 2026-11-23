package com.example.swiftsim.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides whether a directory entry in the payment application's outgoing folder is safe to read.
 * Temporary names and symlinks are left in place. This class does not delete or move anything.
 */
public final class InboundPolicy {
    private static final Pattern TEMP_NAME = Pattern.compile(
            "(?i)(^\\..*|.*\\.(tmp|temp|partial|filepart|crdownload|swp|download|part)$|.*~$)");

    private InboundPolicy() {}

    public enum Skip {
        OK, TEMP, EXTENSION, SYMLINK, NOT_FILE, ESCAPE, EMPTY, TOO_LARGE, UNREADABLE
    }

    public static boolean temporaryName(String name) {
        if (name == null || name.isBlank() || ".".equals(name) || "..".equals(name)) return true;
        return TEMP_NAME.matcher(name).matches();
    }

    /** MT files are {@code .txt}. MX files are {@code .xml}. Matching is case-insensitive. */
    public static boolean extensionOk(String kind, String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        if ("MX".equals(kind)) return lower.endsWith(".xml");
        if ("MT".equals(kind)) return lower.endsWith(".txt");
        return false;
    }

    /**
     * Classify one top-level entry. {@code dir} is the outgoing directory being scanned.
     * Symlinks are never followed. A regular file whose real path is outside {@code dir} is refused.
     */
    public static Skip classify(Path dir, Path file, String kind, long maxBytes) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        if (temporaryName(name)) return Skip.TEMP;
        if (!extensionOk(kind, name)) return Skip.EXTENSION;
        try {
            if (Files.isSymbolicLink(file) || Files.isSymbolicLink(dir)) return Skip.SYMLINK;
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Skip.NOT_FILE;
            Path realDir = dir.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path realFile = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!realFile.startsWith(realDir) || realFile.equals(realDir)) return Skip.ESCAPE;
            long size = Files.size(file);
            if (size <= 0) return Skip.EMPTY;
            if (maxBytes > 0 && size > maxBytes) return Skip.TOO_LARGE;
            return Skip.OK;
        } catch (IOException e) {
            return Skip.UNREADABLE;
        }
    }
}
