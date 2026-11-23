package com.example.swiftsim.files;

import java.nio.file.Path;

/**
 * Folder layout from the <b>payment application's</b> point of view.
 * <ul>
 *   <li>{@code outgoing/mt} and {@code outgoing/mx} — messages the application sends. Simulator input.</li>
 *   <li>{@code incoming/mt} and {@code incoming/mx} — messages the application receives. Simulator output.
 *       The simulator writes files here and does not clean them up.</li>
 *   <li>{@code temp/outgoing/mt} and {@code temp/outgoing/mx} — simulator archive of originals it has
 *       successfully consumed. Cleanup is a move, not a delete. This is the only success archive.</li>
 *   <li>{@code temp/incoming} — owned by the payment application after it reads {@code incoming}.
 *       The simulator never opens it.</li>
 *   <li>{@code .swiftsim/inflight} and {@code .swiftsim/error} — simulator-owned claim and failure
 *       isolation. Not mixed with the success archive.</li>
 * </ul>
 * Path math only: this class does not touch the filesystem.
 */
public final class KenexLayout {
    public final Path root;
    public final Path outgoingMt;
    public final Path outgoingMx;
    public final Path incomingMt;
    public final Path incomingMx;
    /** Display path only. Callers must not create, list, read, or delete this directory. */
    public final Path appTempIncoming;
    /** Success archive of consumed outgoing files. Subdirectories keep MT and MX apart. */
    public final Path appTempOutgoing;
    public final Path archivedMt;
    public final Path archivedMx;
    public final Path stateRoot;
    public final Path inflightMt;
    public final Path inflightMx;
    public final Path errorMt;
    public final Path errorMx;

    private KenexLayout(Path root, Path stateRoot) {
        this.root = root;
        this.outgoingMt = root.resolve("outgoing").resolve("mt");
        this.outgoingMx = root.resolve("outgoing").resolve("mx");
        this.incomingMt = root.resolve("incoming").resolve("mt");
        this.incomingMx = root.resolve("incoming").resolve("mx");
        this.appTempIncoming = root.resolve("temp").resolve("incoming");
        this.appTempOutgoing = root.resolve("temp").resolve("outgoing");
        this.archivedMt = appTempOutgoing.resolve("mt");
        this.archivedMx = appTempOutgoing.resolve("mx");
        this.stateRoot = stateRoot;
        this.inflightMt = stateRoot.resolve("inflight").resolve("mt");
        this.inflightMx = stateRoot.resolve("inflight").resolve("mx");
        this.errorMt = stateRoot.resolve("error").resolve("mt");
        this.errorMx = stateRoot.resolve("error").resolve("mx");
    }

    public Path incoming(boolean xml) {
        return xml ? incomingMx : incomingMt;
    }

    public Path outgoing(String kind) {
        return "MX".equals(kind) ? outgoingMx : outgoingMt;
    }

    public Path inflight(String kind) {
        return "MX".equals(kind) ? inflightMx : inflightMt;
    }

    /** Successfully consumed original, moved under {@code temp/outgoing/mt} or {@code temp/outgoing/mx}. */
    public Path archived(String kind) {
        return "MX".equals(kind) ? archivedMx : archivedMt;
    }

    public Path error(String kind) {
        return "MX".equals(kind) ? errorMx : errorMt;
    }

    public static final class Result {
        public final KenexLayout layout;
        public final String error;

        private Result(KenexLayout layout, String error) {
            this.layout = layout;
            this.error = error;
        }

        public static Result ok(KenexLayout layout) {
            return new Result(layout, null);
        }

        public static Result error(String error) {
            return new Result(null, error);
        }
    }

    /**
     * @param rootRaw  payment-application root, for example {@code /path/to/kenex}. Relative values are resolved
     *                 against the process working directory and then checked as an absolute path.
     * @param stateRaw simulator-owned directory. Blank uses {@code <root>/.swiftsim}.
     */
    public static Result resolve(String rootRaw, String stateRaw) {
        if (rootRaw == null || rootRaw.isBlank()) return Result.error("file root is empty");
        Path root;
        try {
            root = Path.of(rootRaw.trim()).toAbsolutePath().normalize();
        } catch (Exception e) {
            return Result.error("file root is not a path: " + e.getMessage());
        }
        if (root.getNameCount() < 2) {
            return Result.error("refusing file root " + root + " (too close to the filesystem root; use a dedicated directory such as /path/to/kenex)");
        }
        Path state;
        if (stateRaw == null || stateRaw.isBlank()) {
            state = root.resolve(".swiftsim").normalize();
        } else {
            try {
                state = Path.of(stateRaw.trim()).toAbsolutePath().normalize();
            } catch (Exception e) {
                return Result.error("state dir is not a path: " + e.getMessage());
            }
            if (state.getNameCount() < 2) return Result.error("refusing state dir " + state);
        }
        KenexLayout layout = new KenexLayout(root, state);
        if (overlaps(state, layout.outgoingMt) || overlaps(state, layout.outgoingMx)
                || overlaps(state, layout.incomingMt) || overlaps(state, layout.incomingMx)
                || overlaps(state, layout.appTempIncoming) || overlaps(state, layout.appTempOutgoing)
                || overlaps(state, layout.archivedMt) || overlaps(state, layout.archivedMx)) {
            return Result.error("simulator state dir must not overlap outgoing, incoming, temp/outgoing, or temp/incoming: " + state);
        }
        if (state.equals(root)) return Result.error("simulator state dir must not be the kenex root itself");
        return Result.ok(layout);
    }

    /** Either path is the same as the other or one contains the other. */
    private static boolean overlaps(Path a, Path b) {
        Path left = a.normalize();
        Path right = b.normalize();
        return left.equals(right) || left.startsWith(right) || right.startsWith(left);
    }
}
