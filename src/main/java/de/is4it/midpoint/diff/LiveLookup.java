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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.evolveum.midpoint.prism.Containerable;
import com.evolveum.midpoint.prism.Objectable;
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
 * adds up fast when {@code apply}/{@code deploy} probe for existence before create-if-absent. Shared by
 * {@code diff --url}, {@code apply}, and {@code deploy}; all HTTP stays in {@link MidpointRestClient}.
 */
final class LiveLookup {

    private static final Logger LOG = LoggerFactory.getLogger(LiveLookup.class);

    private LiveLookup() {
    }

    /** The REST address and current state of a desired object, resolved by OID or by type+name. */
    static final class Target {
        final String restType;
        final String oid;                                 // addressed OID (patch), or null (create by name)
        final PrismObject<? extends Objectable> current;  // current state, or null if it does not exist

        private Target(String restType, String oid, PrismObject<? extends Objectable> current) {
            this.restType = restType;
            this.oid = oid;
            this.current = current;
        }
    }

    /**
     * Resolve the live object a desired object addresses, for the {@code diff}/{@code apply} single-object
     * paths: by OID ({@code --oid} or the object's own), or — when {@code matchByName} is set and there is
     * no OID — by type+name. Throws when an OID-less object is given without {@code --match-by-name}, when a
     * name match is ambiguous, or when the type has no REST endpoint. {@link Target#current} is {@code null}
     * when the object does not exist yet.
     */
    static Target resolveCurrent(PrismContext prismContext, MidpointRestClient client,
            PrismObject<? extends Objectable> desired, String oidOverride, boolean matchByName)
            throws SchemaException, IOException {
        Class<?> clazz = desired.getCompileTimeClass();
        if (clazz == null) {
            throw new SchemaException("Cannot determine the object type to address over REST "
                    + "(no compile-time class for the desired object).");
        }
        String restType;
        try {
            restType = ObjectTypes.getRestTypeFromClass(clazz);
        } catch (IllegalArgumentException e) {
            throw new SchemaException("No REST endpoint for object type " + clazz.getSimpleName() + ".");
        }

        String oid = (oidOverride != null && !oidOverride.isBlank()) ? oidOverride : desired.getOid();
        if (oid != null && !oid.isBlank()) {
            PrismObject<? extends Objectable> current = byOid(prismContext, client, clazz, restType, oid);
            LOG.debug("resolve {} by oid {} → {}", restType, oid, current != null ? "found" : "absent");
            return new Target(restType, oid, current);
        }
        if (!matchByName) {
            throw new SchemaException("No OID to address: pass --oid, include an oid in the object, "
                    + "or --match-by-name to resolve it by type+name.");
        }
        String name = nameOf(desired);
        List<PrismObject<? extends Objectable>> matches = byName(prismContext, client, clazz, restType, name);
        LOG.debug("resolve {} by name '{}' → {} match(es)", restType, name, matches.size());
        if (matches.size() > 1) {
            throw new SchemaException("--match-by-name found " + matches.size() + " " + restType
                    + " named '" + name + "'; refusing an ambiguous match.");
        }
        PrismObject<? extends Objectable> current = matches.isEmpty() ? null : matches.get(0);
        return new Target(restType, (current != null) ? current.getOid() : null, current);
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
        LOG.debug("search {} → {} object(s)", restType, matches.size());
        return matches;
    }

    @SuppressWarnings("unchecked") // an object's compile-time class is always a Containerable
    private static Class<? extends Containerable> containerable(Class<?> clazz) {
        return (Class<? extends Containerable>) clazz;
    }
}
