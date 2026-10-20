/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.StringJoiner;

import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.prism.path.ItemPathSegment;

/**
 * An item path as midgie prints it: {@code mapping/4/lifecycleState}, a container id a segment of its own.
 *
 * <p>midgie renders paths itself rather than through {@code ItemPath.toString()}, whose form belongs to prism
 * and changed with midPoint 4.11 to {@code mapping[4]/lifecycleState}. The text reaches the operator, the
 * {@code summary} format and {@link ApplyRules#modeOf(String)}, so it must not depend on the midPoint line
 * midgie was built against.
 */
final class PathText {

    private PathText() {
    }

    static String of(ItemPath path) {
        StringJoiner text = new StringJoiner("/");
        for (Object segment : path.getSegments()) {
            Long id = ItemPath.isId(segment) ? ItemPath.toIdOrNull(segment) : null;
            text.add((id != null) ? id.toString() : ItemPathSegment.toString(segment));
        }
        return text.toString();
    }
}
