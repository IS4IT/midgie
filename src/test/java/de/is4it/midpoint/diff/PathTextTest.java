/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import javax.xml.namespace.QName;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.prism.path.ItemPath;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The path text midgie prints, which must read the same whichever midPoint line it was built against. */
class PathTextTest {

    private static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";

    @Test
    void aContainerIdIsASegmentOfItsOwn() {
        // prism 4.10 renders this path the same; prism 4.11 renders objectDetailsPage[5]/panel[9]/description.
        ItemPath path = ItemPath.create(new QName(NS, "adminGuiConfiguration"), new QName(NS, "objectDetails"),
                new QName(NS, "objectDetailsPage"), 5L, new QName(NS, "panel"), 9L, new QName(NS, "description"));

        assertEquals("adminGuiConfiguration/objectDetails/objectDetailsPage/5/panel/9/description",
                PathText.of(path));
    }

    @Test
    void aPathWithoutIdsIsItsNames() {
        assertEquals("activation/administrativeStatus",
                PathText.of(ItemPath.create(new QName(NS, "activation"), new QName(NS, "administrativeStatus"))));
        assertEquals("", PathText.of(ItemPath.EMPTY_PATH));
    }
}
