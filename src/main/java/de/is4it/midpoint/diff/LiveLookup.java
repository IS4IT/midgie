/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.namespace.QName;

import com.evolveum.midpoint.prism.Containerable;
import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismConstants;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.query.ObjectQuery;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.xml.ns._public.common.api_types_3.ObjectListType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.prism.xml.ns._public.query_3.QueryType;

/**
 * Looks up live objects with a {@code POST {type}/search} instead of a {@code GET {type}/{oid}}. A search
 * for something that does not exist is a normal empty result, whereas a {@code GET} on a missing OID
 * returns 404 <em>and</em> makes midPoint log an {@code ObjectNotFoundException} stacktrace — noise that
 * adds up fast when {@code apply}/{@code deploy} probe for existence before create-if-absent. Shared by
 * {@code diff --host}, {@code apply}, and {@code deploy}; all HTTP stays in {@link MidpointRestClient}.
 */
final class LiveLookup {

    private LiveLookup() {
    }

    /** The current state of {@code oid}, or {@code null} if no such object exists. */
    static PrismObject<? extends Objectable> byOid(PrismContext prismContext, MidpointRestClient client,
            Class<?> clazz, String restType, String oid) throws SchemaException, IOException {
        List<PrismObject<? extends Objectable>> matches = search(prismContext, client, restType,
                prismContext.queryFor(containerable(clazz)).id(oid).build());
        return matches.isEmpty() ? null : matches.get(0);
    }

    /** Every object of {@code restType} whose name matches {@code name} (exact, by orig). */
    static List<PrismObject<? extends Objectable>> byName(PrismContext prismContext, MidpointRestClient client,
            Class<?> clazz, String restType, String name) throws SchemaException, IOException {
        return search(prismContext, client, restType, prismContext.queryFor(containerable(clazz))
                .item(ObjectType.F_NAME).eqPoly(name).matchingOrig().build());
    }

    private static List<PrismObject<? extends Objectable>> search(PrismContext prismContext,
            MidpointRestClient client, String restType, ObjectQuery query)
            throws SchemaException, IOException {
        QueryType queryType = prismContext.getQueryConverter().createQueryType(query);
        String body = prismContext.xmlSerializer()
                .serializeAnyData(queryType, new QName(PrismConstants.NS_QUERY, "query"));
        ObjectListType list = prismContext.parserFor(client.searchObjects(restType, body))
                .xml().parseRealValue(ObjectListType.class);
        List<PrismObject<? extends Objectable>> matches = new ArrayList<>();
        if (list != null) {
            for (ObjectType object : list.getObject()) {
                matches.add(object.asPrismObject());
            }
        }
        return matches;
    }

    @SuppressWarnings("unchecked") // an object's compile-time class is always a Containerable
    private static Class<? extends Containerable> containerable(Class<?> clazz) {
        return (Class<? extends Containerable>) clazz;
    }
}
