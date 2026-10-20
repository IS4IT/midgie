/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.prism.path.ItemPathSegment;

/**
 * An item path as midgie prints it: {@code mapping[4]/lifecycleState}, a container id in brackets after its
 * item's name.
 *
 * <p>That is the form prism's {@code ItemPath.toString()} writes since midPoint 4.11; up to 4.10 it wrote the id
 * as a segment of its own, {@code mapping/4/lifecycleState}. midgie renders paths itself so that the text
 * reaching the operator, the {@code summary} format and {@link ApplyRules#modeOf(String)} does not depend on the
 * midPoint line midgie was built against.
 */
final class PathText {

    private PathText() {
    }

    static String of(ItemPath path) {
        StringBuilder text = new StringBuilder();
        for (Object segment : path.getSegments()) {
            if (ItemPath.isId(segment)) {
                // Bracketed here: up to 4.10, ItemPathSegment.toString prints a bare Long id as "4". An id
                // segment without a number, the wildcard, prints itself as [*].
                Long id = ItemPath.toIdOrNull(segment);
                text.append((id != null) ? "[" + id + "]" : ItemPathSegment.toString(segment));
            } else {
                if (!text.isEmpty()) {
                    text.append('/');
                }
                text.append(ItemPathSegment.toString(segment));
            }
        }
        return text.toString();
    }
}
