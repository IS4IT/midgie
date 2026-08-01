/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.List;
import java.util.Map;
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
        Map<String, Boolean> cache = new ConcurrentHashMap<>();
        return (live, desired) -> {
            String key = desired.getFilter() + "|" + desired.getTargetType() + "|" + live.getOid();
            return cache.computeIfAbsent(key, ignored -> resolves(prismContext, client, live, desired));
        };
    }

    /**
     * Whether {@code desired}'s filter selects exactly the object {@code live} points at.
     *
     * <p>A mismatch is a real change, so the caller emits the delta and midPoint re-resolves the filter —
     * {@code verify} degrades to {@code resolve} for precisely the references that failed the check. A filter
     * that matches nothing, or several objects, is reported here and also treated as a change; midPoint then
     * refuses the modify with its own precise message rather than storing an unusable reference.
     */
    private static boolean resolves(PrismContext prismContext, MidpointRestClient client,
            PrismReferenceValue live, PrismReferenceValue desired) {
        String restType = null;
        try {
            QName targetType = (desired.getTargetType() != null) ? desired.getTargetType() : live.getTargetType();
            if (targetType == null) {
                LOG.trace("cannot verify a smart reference with no target type; treating it as a change");
                return false;
            }
            restType = ObjectTypes.getRestTypeFromClass(
                    ObjectTypes.getObjectTypeFromTypeQName(targetType).getClassDefinition());

            QueryType query = new QueryType();
            query.setFilter(desired.getFilter());
            String body = prismContext.xmlSerializer()
                    .serializeAnyData(query, new QName(PrismConstants.NS_QUERY, "query"));
            ObjectListType list = prismContext.parserFor(client.searchObjects(restType, body))
                    .xml().parseRealValue(ObjectListType.class);
            List<ObjectType> matches = (list != null) ? list.getObject() : List.of();

            if (matches.size() != 1) {
                System.err.println("midgie: warning: the filter on this " + restType + " reference matches "
                        + matches.size() + " object(s), so it cannot resolve to one; midPoint will refuse "
                        + "the change until the filter selects exactly one.");
                return false;
            }
            boolean same = matches.get(0).getOid() != null && matches.get(0).getOid().equals(live.getOid());
            LOG.trace("verify smart reference on {}: filter resolves to {}, live holds {} → {}", restType,
                    matches.get(0).getOid(), live.getOid(), same ? "in sync" : "changed");
            return same;
        } catch (Exception e) {
            // Unverifiable is not "verified": treat it as a change. The delta that follows carries
            // reevaluateSearchFilters, so the server re-resolves rather than storing anything broken.
            LOG.trace("could not verify a smart reference against {}", restType, e);
            return false;
        }
    }
}
