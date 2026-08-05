/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
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
        return prismContext.parseObject(file);
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
            // NoSuchFileException's message is the bare path, so without this the operator sees only
            // "midgie: /tmp/nope/out.xml" and nothing about what went wrong.
            throw new IOException("cannot write " + output + ": " + e.getMessage(), e);
        }
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
