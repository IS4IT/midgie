/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.util.DOMUtil;
import com.evolveum.midpoint.util.exception.SchemaException;

/** Small I/O helpers: read an object from a file or stdin ({@code -}), and write a payload to a file or stdout. */
final class Io {

    private static final Logger LOG = LoggerFactory.getLogger(Io.class);

    private Io() {
    }

    static boolean isStdin(File file) {
        return file != null && "-".equals(file.getPath());
    }

    /** {@code -} as an output path: the same convention as {@link #isStdin}, read the other way. */
    static boolean isStdout(File file) {
        return isStdin(file);
    }

    /** Parse an object from {@code file}, or from stdin when {@code file} is {@code -}. */
    static PrismObject<? extends Objectable> parseObject(PrismContext prismContext, File file)
            throws SchemaException, IOException {
        LOG.debug("parse object from {}", isStdin(file) ? "stdin" : file);
        if (isStdin(file)) {
            return prismContext.parseObject(new String(System.in.readAllBytes(), StandardCharsets.UTF_8));
        }
        try {
            return prismContext.parseObject(file);
        } catch (IOException e) {
            throw cannotRead(file, e);
        }
    }

    /**
     * Fail now if {@code file} cannot be read or is not well-formed XML.
     *
     * <p>For a live run this is the cheap half of the work done before the expensive half: parsing an object
     * properly needs the extension schema, which needs the server, so a typo'd path or an unreadable mount
     * was only discovered after two round trips — and against a host that is down, surfaced as a connection
     * error instead of the local mistake it was. Opening the file rather than asking whether it is readable
     * is what produces a real denial to report; {@code deploy} gets the same two checks per file from
     * {@link Discovery}, which already DOM-parses everything it walks.
     *
     * <p>A no-op for stdin, which cannot be checked without consuming the one read it has.
     */
    static void checkInputFile(File file) throws IOException {
        if (file == null || isStdin(file)) {
            return;
        }
        try (java.io.InputStream ignored = Files.newInputStream(file.toPath())) {
            LOG.trace("input file is readable: {}", file);
        } catch (IOException e) {
            throw cannotRead(file, e);
        }
        try {
            DOMUtil.parseFile(file);
        } catch (Exception e) {
            throw new IOException("cannot read " + file + ": not well-formed XML (" + e.getMessage() + ")", e);
        }
    }

    /**
     * Write {@code content} to {@code output} (trailing newline), or to stdout when {@code output} is null.
     *
     * <p>Created owner-readable where the filesystem supports it: a delta, and especially a create body, is
     * the desired object verbatim and may carry a {@code clearValue} password, so the default umask would
     * leave it readable to every local user of a shared build host.
     */
    static void writePayload(File output, String content) throws IOException {
        // null and '-' both mean stdout. Handled here rather than at each call site, the same way parseObject
        // owns the stdin end of the convention — a caller that forgot the test wrote a file named '-'.
        if (output == null || isStdout(output)) {
            System.out.println(content);
            return;
        }
        try {
            restrictToOwner(output.toPath());
            Files.writeString(output.toPath(), content + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw cannotWrite(output, e);
        }
    }

    /**
     * Wrap a failed read of {@code path} in a message that says what actually went wrong.
     *
     * <p>The exceptions the file system throws here carry the bare path as their message and nothing else —
     * {@code AccessDeniedException.getMessage()} is {@code "/work"} — and midgie prints an exception's
     * message verbatim, so the operator saw {@code "midgie: /work"} and had to guess. A permission denial
     * additionally reports both halves of the mismatch, because neither is visible from where it is read:
     * inside a container the UID is the image's, and the path's owner comes from the host.
     */
    static IOException cannotRead(File path, IOException cause) {
        return new IOException(cannotReadMessage(path.toString(), path, cause), cause);
    }

    /** As {@link #cannotRead}, for a write. */
    static IOException cannotWrite(File path, IOException cause) {
        return new IOException(describe("write", path.toString(), path, cause), cause);
    }

    /**
     * The message {@link #cannotRead} would carry, for a call site that reports rather than throws, with
     * {@code subject} naming the path the way that call site already does — a flag and its value, say.
     */
    static String cannotReadMessage(String subject, File path, IOException cause) {
        return describe("read", subject, path, cause);
    }

    private static String describe(String verb, String subject, File path, IOException cause) {
        boolean denied = denied(path, cause);
        StringBuilder message = new StringBuilder("cannot ").append(verb).append(' ').append(subject)
                .append(": ").append(denied ? "permission denied" : reason(path, cause));
        if (denied) {
            String mismatch = mismatch(path);
            if (mismatch != null) {
                message.append(" — ").append(mismatch);
            }
            message.append('.');
            if (inContainer()) {
                message.append(" A bind-mounted directory keeps its host ownership: run the container with"
                        + " -u \"$(id -u):0\", or set securityContext.runAsUser and fsGroup in Kubernetes.");
            }
        }
        return message.toString();
    }

    /**
     * Whether this failure is a permission denial.
     *
     * <p>Only the NIO path says so in the exception type. The classic {@code java.io} one — which is what
     * prism's object reader throws — reports a denial as a {@link java.io.FileNotFoundException} carrying
     * the C library's wording, so that case is settled by asking the file system rather than by matching on
     * message text, which would be at the mercy of the platform's phrasing.
     */
    private static boolean denied(File path, IOException cause) {
        return cause instanceof AccessDeniedException || (path.exists() && !path.canRead());
    }

    /**
     * The two halves of a permission mismatch — who midgie is, and who owns the path — or null when
     * neither can be read. Both are best-effort, so either half may be missing on its own.
     */
    private static String mismatch(File path) {
        String identity = runningAs();
        String ownership = ownershipOf(path.toPath());
        String owner = (ownership != null) ? "it is owned by " + ownership : null;
        if (identity != null && owner != null) {
            return "midgie runs as " + identity + ", and " + owner;
        }
        return (identity != null) ? "midgie runs as " + identity : owner;
    }

    /**
     * The failure in words, with the path left out — it is already the subject of the sentence.
     *
     * <p>Neither family of exception gives this away readily: on Unix a {@link FileSystemException} carries
     * the path and no reason at all, while {@code java.io} formats its message as {@code "<path> (<why>)"}.
     */
    private static String reason(File path, IOException cause) {
        if (cause instanceof NoSuchFileException) {
            return "no such file or directory";
        }
        if (cause instanceof NotDirectoryException) {
            return "not a directory";
        }
        if (cause instanceof FileSystemException && ((FileSystemException) cause).getReason() != null) {
            return ((FileSystemException) cause).getReason().trim();
        }
        String message = cause.getMessage();
        if (message == null) {
            return cause.getClass().getSimpleName();
        }
        String prefix = path + " (";
        if (message.startsWith(prefix) && message.endsWith(")") && message.length() > prefix.length() + 1) {
            String why = message.substring(prefix.length(), message.length() - 1);
            return Character.toLowerCase(why.charAt(0)) + why.substring(1);
        }
        return message;
    }

    /**
     * Who this process is, as {@code uid 1001 gid 0} — or the user name where that cannot be had.
     *
     * <p>{@code UnixSystem} is reflected rather than imported: it is the only JDK route to the running
     * UID, it lives in {@code jdk.security.auth} (present in the image's JRE, not guaranteed in every
     * runtime), and a diagnostic must not be the thing that fails while explaining a failure.
     */
    private static String runningAs() {
        try {
            Class<?> unixSystem = Class.forName("com.sun.security.auth.module.UnixSystem");
            Object self = unixSystem.getDeclaredConstructor().newInstance();
            return "uid " + unixSystem.getMethod("getUid").invoke(self)
                    + " gid " + unixSystem.getMethod("getGid").invoke(self);
        } catch (Exception | LinkageError e) {
            LOG.trace("could not read the running uid", e);
            String name = System.getProperty("user.name");
            return (name != null && !name.isBlank()) ? "user " + name : null;
        }
    }

    /**
     * Owner and mode of {@code path}, as {@code uid 31655 gid 100, mode 0750}, or null off Unix.
     *
     * <p>Readable exactly when it is needed: {@code stat} on an unreadable directory needs search
     * permission on the <em>parent</em>, not read permission on the directory itself.
     */
    private static String ownershipOf(java.nio.file.Path path) {
        try {
            if (!path.getFileSystem().supportedFileAttributeViews().contains("unix")) {
                return null;
            }
            int mode = (Integer) Files.getAttribute(path, "unix:mode");
            return "uid " + Files.getAttribute(path, "unix:uid")
                    + " gid " + Files.getAttribute(path, "unix:gid")
                    + String.format(", mode %04o", mode & 07777);
        } catch (Exception e) {
            LOG.trace("could not read the ownership of {}", path, e);
            return null;
        }
    }

    /** Whether this process is in a container, and the hint about mount ownership therefore applies. */
    static boolean inContainer() {
        return Files.exists(java.nio.file.Path.of("/.dockerenv"))
                || Files.exists(java.nio.file.Path.of("/run/.containerenv"));
    }

    /**
     * Create {@code dir} (and its parents) with owner-only permissions, and tighten it if it already exists.
     * A no-op on a non-POSIX filesystem.
     *
     * <p>Unlike {@link #restrictToOwner}, which creates a <em>file</em>, this is for a directory a run
     * writes several files into — a rollback plan holds object state and belongs to the operator alone.
     */
    static void restrictDirToOwner(java.nio.file.Path dir) throws IOException {
        Files.createDirectories(dir);
        if (!dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        Files.setPosixFilePermissions(dir,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    }

    /** Create {@code path} (if absent) with owner-only permissions; a no-op on a non-POSIX filesystem. */
    static void restrictToOwner(java.nio.file.Path path) throws IOException {
        if (Files.exists(path) || !path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        Files.createFile(path, java.nio.file.attribute.PosixFilePermissions
                .asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
    }
}
