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

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.util.exception.SchemaException;

/** Small I/O helpers: read an object from a file or stdin ({@code -}), and write a payload to a file or stdout. */
final class Io {

    private Io() {
    }

    static boolean isStdin(File file) {
        return file != null && "-".equals(file.getPath());
    }

    /** Parse an object from {@code file}, or from stdin when {@code file} is {@code -}. */
    static PrismObject<? extends Objectable> parseObject(PrismContext prismContext, File file)
            throws SchemaException, IOException {
        if (isStdin(file)) {
            return prismContext.parseObject(new String(System.in.readAllBytes(), StandardCharsets.UTF_8));
        }
        return prismContext.parseObject(file);
    }

    /** Write {@code content} to {@code output} (trailing newline), or to stdout when {@code output} is null. */
    static void writePayload(File output, String content) throws IOException {
        if (output == null) {
            System.out.println(content);
        } else {
            Files.writeString(output.toPath(), content + System.lineSeparator(), StandardCharsets.UTF_8);
        }
    }
}
