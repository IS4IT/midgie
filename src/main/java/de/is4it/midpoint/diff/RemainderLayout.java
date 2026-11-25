/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where {@code pull} keeps each object's remainder: {@code <remainder>/<Type>/<name>.<oid>.xml}, one folder per
 * object type, one file per object, named as the GUI shows the object and made unique by its OID.
 *
 * <p>An object is found by its OID alone ({@code *.<oid>.xml}), never by the name part: a rename on the server
 * then turns into a file rename rather than into a second file beside the first.
 */
final class RemainderLayout {

    /** Long enough to tell names apart in a listing, short enough for every file system once the OID is added. */
    private static final int MAX_NAME_LENGTH = 80;

    /** {@code <name>.<oid>.xml}, with the OID as midPoint writes it. */
    private static final Pattern FILE_NAME =
            Pattern.compile("(.*)\\.([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\.xml");

    private final File root;

    RemainderLayout(File root) {
        this.root = root;
    }

    File root() {
        return root;
    }

    /** The file an object of {@code typeName} ({@code RoleType}) is written to. */
    File fileFor(String typeName, String name, String oid) {
        return new File(new File(root, typeName), safeName(name) + "." + oid + ".xml");
    }

    /** Whether {@code file} lies in the remainder folder. */
    boolean contains(File file) {
        Path folder = canonical(root);
        return canonical(file).startsWith(folder);
    }

    /**
     * Every remainder file there is, by OID. A file in the folder that does not follow the naming scheme is not
     * pull's, and is left out.
     *
     * @throws IOException when a type folder cannot be listed — pull would then miss a file it should delete
     */
    Map<String, File> existing() throws IOException {
        Map<String, File> files = new LinkedHashMap<>();
        if (!root.isDirectory()) {
            return files;
        }
        File[] types = root.listFiles(File::isDirectory);
        if (types == null) {
            throw new IOException("cannot list " + root);
        }
        java.util.Arrays.sort(types);
        for (File type : types) {
            File[] objects = type.listFiles(File::isFile);
            if (objects == null) {
                throw new IOException("cannot list " + type);
            }
            java.util.Arrays.sort(objects);
            for (File file : objects) {
                String oid = oidOf(file);
                if (oid != null) {
                    files.put(oid, file);
                }
            }
        }
        return files;
    }

    /** The OID a remainder file's name carries, or {@code null} when it does not follow the scheme. */
    static String oidOf(File file) {
        Matcher matcher = FILE_NAME.matcher(file.getName());
        return matcher.matches() ? matcher.group(2).toLowerCase() : null;
    }

    /**
     * {@code name} as part of a file name every common file system accepts: path separators, characters Windows
     * refuses and control characters become {@code _}, the text is NFC-normalized (macOS hands back decomposed
     * names otherwise, and git sees a rename), a trailing dot or space is dropped, and the length is capped.
     */
    static String safeName(String name) {
        String text = (name == null) ? "" : Normalizer.normalize(name, Normalizer.Form.NFC);
        StringBuilder safe = new StringBuilder();
        text.codePoints().forEach(c -> {
            boolean unsafe = c < 0x20 || c == 0x7f || "/\\:*?\"<>|".indexOf(c) >= 0;
            safe.appendCodePoint(unsafe ? '_' : c);
        });
        String result = safe.toString().strip();
        if (result.codePointCount(0, result.length()) > MAX_NAME_LENGTH) {
            result = result.substring(0, result.offsetByCodePoints(0, MAX_NAME_LENGTH));
        }
        while (result.endsWith(".") || result.endsWith(" ")) {
            result = result.substring(0, result.length() - 1);
        }
        if (result.isEmpty() || result.startsWith(".")) {
            result = "_" + result;
        }
        return result;
    }

    private static Path canonical(File file) {
        try {
            return file.getCanonicalFile().toPath();
        } catch (IOException e) {
            return file.getAbsoluteFile().toPath().normalize();
        }
    }
}
