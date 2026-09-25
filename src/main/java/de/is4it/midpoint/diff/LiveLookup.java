/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.namespace.QName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.evolveum.midpoint.prism.Containerable;
import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContainer;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismConstants;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.query.ObjectQuery;
import com.evolveum.midpoint.schema.constants.ObjectTypes;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.xml.ns._public.common.api_types_3.ObjectListType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.prism.xml.ns._public.query_3.QueryType;

/**
 * Looks up live objects with a {@code POST {type}/search} instead of a {@code GET {type}/{oid}}. A search
 * for something that does not exist is a normal empty result, whereas a {@code GET} on a missing OID
 * returns 404 <em>and</em> makes midPoint log an {@code ObjectNotFoundException} stacktrace — noise that
 * adds up fast when {@code deploy} probes for existence before create-if-absent. Used by {@link Reconciler}
 * (and so by {@code diff --url}, which runs through it) and deploy's prune; all HTTP stays in
 * {@link MidpointRestClient}.
 */
final class LiveLookup {

    private static final Logger LOG = LoggerFactory.getLogger(LiveLookup.class);

    private LiveLookup() {
    }

    static String nameOf(PrismObject<? extends Objectable> object) {
        return (object.getName() != null) ? object.getName().getOrig() : "";
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

    /**
     * The names of the objects of {@code restType} among {@code oids}, by OID, in one search. An OID missing
     * from the result is not on the server (or not visible to this account). Used only to name OIDs in
     * comments, so the objects are not re-read for items the server withheld.
     */
    static Map<String, String> namesByOid(PrismContext prismContext, MidpointRestClient client, Class<?> clazz,
            String restType, Collection<String> oids) throws SchemaException, IOException {
        Map<String, String> names = new LinkedHashMap<>();
        if (oids.isEmpty()) {
            return names;
        }
        ObjectQuery query = prismContext.queryFor(containerable(clazz)).id(oids.toArray(new String[0])).build();
        for (PrismObject<? extends Objectable> object : search(prismContext, client, restType, query, false)) {
            names.put(object.getOid(), nameOf(object));
        }
        return names;
    }

    private static List<PrismObject<? extends Objectable>> search(PrismContext prismContext,
            MidpointRestClient client, String restType, ObjectQuery query)
            throws SchemaException, IOException {
        return search(prismContext, client, restType, query, true);
    }

    private static List<PrismObject<? extends Objectable>> search(PrismContext prismContext,
            MidpointRestClient client, String restType, ObjectQuery query, boolean complete)
            throws SchemaException, IOException {
        QueryType queryType = prismContext.getQueryConverter().createQueryType(query);
        String body = prismContext.xmlSerializer()
                .serializeAnyData(queryType, new QName(PrismConstants.NS_QUERY, "query"));
        ObjectListType list = prismContext.parserFor(client.searchObjects(restType, body))
                .xml().parseRealValue(ObjectListType.class);
        List<PrismObject<? extends Objectable>> matches = new ArrayList<>();
        if (list != null) {
            for (ObjectType object : list.getObject()) {
                matches.add(complete ? complete(prismContext, client, restType, object.asPrismObject())
                        : object.asPrismObject());
            }
        }
        LOG.trace("search {} → {} object(s)", restType, matches.size());
        return matches;
    }

    /**
     * Re-read {@code object} asking for the items midPoint declined to send.
     *
     * <p>Some items are not returned by default and come back as {@code <row incomplete="true"/>} — a
     * {@code LookupTableType}'s {@code row} is the everyday case, a {@code jpegPhoto} another. Diffing that
     * as written would read "the server has no rows" from "the server did not send the rows": every run
     * would emit the whole table as an addition and never converge, and against a table an admin had added
     * to, a replace would destroy the rows midgie could not see.
     *
     * <p>Which items those are is not in the bundled schema — midPoint decides it in its repository layer —
     * so this follows the server's own {@code incomplete} marker rather than a list to keep in step. The
     * second request is made only for an object that actually carries one.
     */
    private static PrismObject<? extends Objectable> complete(PrismContext prismContext,
            MidpointRestClient client, String restType, PrismObject<? extends Objectable> object)
            throws SchemaException, IOException {
        List<String> incomplete = incompletePaths(object);
        if (incomplete.isEmpty() || object.getOid() == null) {
            return object;
        }
        LOG.debug("{}/{} came back without {}; re-reading with include", restType, object.getOid(), incomplete);
        return prismContext.parseObject(
                client.getObject(restType, object.getOid(), incomplete.toArray(new String[0])));
    }

    /** The id-free paths of every item the server marked {@code incomplete}, deepest container first. */
    static List<String> incompletePaths(PrismObject<? extends Objectable> object) {
        List<String> paths = new ArrayList<>();
        collectIncomplete(object.getValue(), "", paths);
        return paths;
    }

    private static void collectIncomplete(PrismContainerValue<?> container, String prefix, List<String> paths) {
        for (Item<?, ?> item : container.getItems()) {
            String path = prefix.isEmpty()
                    ? item.getElementName().getLocalPart()
                    : prefix + "/" + item.getElementName().getLocalPart();
            if (item.isIncomplete()) {
                if (!paths.contains(path)) {
                    paths.add(path);
                }
                continue; // nothing below it was sent either
            }
            if (item instanceof PrismContainer<?> nested) {
                for (PrismContainerValue<?> value : nested.getValues()) {
                    collectIncomplete(value, path, paths);
                }
            }
        }
    }

    @SuppressWarnings("unchecked") // an object's compile-time class is always a Containerable
    private static Class<? extends Containerable> containerable(Class<?> clazz) {
        return (Class<? extends Containerable>) clazz;
    }
}
