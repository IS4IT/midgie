/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.xml.namespace.QName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.evolveum.midpoint.prism.PrismConstants;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismReferenceValue;
import com.evolveum.midpoint.schema.constants.ObjectTypes;
import com.evolveum.midpoint.xml.ns._public.common.api_types_3.ObjectListType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.prism.xml.ns._public.query_3.QueryType;
import com.evolveum.prism.xml.ns._public.query_3.SearchFilterType;

/**
 * The live half of the smart-reference judgement: turn {@code --smart-refs} into the check
 * {@link DiffEngine} consults, including the {@code verify} variant that asks the server.
 *
 * <p>Kept out of {@link DiffEngine} on purpose — the diff core stays free of REST (see DESIGN.md), so the
 * engine only decides the structural half ("filter on one side, OID on the other") and calls the interface
 * for the rest.
 */
final class SmartReferences {

    private static final Logger LOG = LoggerFactory.getLogger(SmartReferences.class);

    private SmartReferences() {
    }

    /**
     * The check for {@code strategy}. {@code accept} and {@code resolve} are decided offline; {@code verify}
     * runs the desired filter against the server and requires the live OID to be its single match.
     *
     * @param client the live server, or {@code null} when there is none (then {@code verify} is not available
     *               and the offline judgement is used — the command layer rejects the combination up front)
     */
    static DiffEngine.SmartReferenceCheck checkFor(StrategyOptions.Resolved strategy, PrismContext prismContext,
            MidpointRestClient client) {
        if (strategy.smartRefs != StrategyOptions.SmartRefs.VERIFY || client == null) {
            return strategy.smartReferenceCheck();
        }
        Map<String, String> resolved = new ConcurrentHashMap<>(); // query → the one OID it selects, "" for none
        Set<String> said = ConcurrentHashMap.newKeySet();
        return (live, desired) -> {
            Search search;
            try {
                search = search(prismContext, live, desired);
            } catch (Exception e) {
                unverifiable(said, "this reference's filter could not be turned into a search", e);
                return false;
            }
            if (search == null) {
                unverifiable(said, "this reference names no target type to search", null);
                return false;
            }
            // Keyed on the query as sent, one search per filter: a filter's toString() is its identity, so equal
            // filters on two references never shared an answer, and two different ones could in principle have.
            String oid = resolved.computeIfAbsent(search.restType() + "|" + search.body(),
                    ignored -> resolve(prismContext, client, search, said));
            boolean same = !oid.isEmpty() && oid.equals(live.getOid());
            LOG.trace("verify smart reference on {}: filter resolves to {}, live holds {} → {}", search.restType(),
                    oid.isEmpty() ? "nothing" : oid, live.getOid(), same ? "in sync" : "changed");
            return same;
        };
    }

    /** The search a filter stands for: where to send it and the query body. */
    private record Search(String restType, String body) {
    }

    /** {@code null} when neither side names the type of object the filter selects. */
    private static Search search(PrismContext prismContext, PrismReferenceValue live, PrismReferenceValue desired)
            throws Exception {
        QName targetType = (desired.getTargetType() != null) ? desired.getTargetType() : live.getTargetType();
        if (targetType == null) {
            return null;
        }
        String restType = ObjectTypes.getRestTypeFromClass(
                ObjectTypes.getObjectTypeFromTypeQName(targetType).getClassDefinition());
        QueryType query = new QueryType();
        query.setFilter(desired.getFilter());
        String body = prismContext.xmlSerializer().serializeAnyData(query, new QName(PrismConstants.NS_QUERY, "query"));
        return new Search(restType, body);
    }

    /**
     * Say that a reference could not be verified and is treated as a change — once per reason and object. It
     * used to be logged at TRACE only, so a deploy account without search rights turned every smart reference
     * into a change with no word why.
     */
    private static void unverifiable(Set<String> said, String reason, Exception cause) {
        String detail = (cause == null) ? reason
                : reason + " (" + ((cause.getMessage() != null) ? cause.getMessage() : cause.toString()) + ")";
        if (said.add(detail)) {
            System.err.println("midgie: warning: --smart-refs verify: " + detail + "; it is treated as a change, "
                    + "and midPoint resolves the filter again when the change is applied.");
        }
        LOG.trace("could not verify a smart reference", cause);
    }

    /**
     * The OID {@code search} selects, or {@code ""} when it does not select exactly one object.
     *
     * <p>A mismatch is a real change, so the caller emits the delta and midPoint re-resolves the filter —
     * {@code verify} degrades to {@code resolve} for precisely the references that failed the check. A filter
     * that matches nothing, or several objects, is reported here and also treated as a change; midPoint then
     * refuses the modify with its own precise message rather than storing an unusable reference.
     */
    private static String resolve(PrismContext prismContext, MidpointRestClient client, Search search,
            Set<String> said) {
        String restType = search.restType();
        try {
            ObjectListType list = prismContext.parserFor(client.searchObjects(restType, search.body()))
                    .xml().parseRealValue(ObjectListType.class);
            List<ObjectType> matches = (list != null) ? list.getObject() : List.of();
            if (matches.size() != 1) {
                System.err.println("midgie: warning: the filter on this " + restType + " reference matches "
                        + matches.size() + " object(s), so it cannot resolve to one; midPoint will refuse "
                        + "the change until the filter selects exactly one.");
                return "";
            }
            String oid = matches.get(0).getOid();
            return (oid != null) ? oid : "";
        } catch (Exception e) {
            // Unverifiable is not "verified": treat it as a change. The delta that follows carries
            // reevaluateSearchFilters, so the server re-resolves rather than storing anything broken.
            unverifiable(said, "searching " + restType + " for a reference's filter failed", e);
            return "";
        }
    }
}
