/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.schema.constants.ObjectTypes;
import com.evolveum.midpoint.util.exception.SchemaException;

/**
 * The REST address of an object to fetch/patch: its collection type ({@code users}, {@code roles}, …)
 * and OID, resolved from the desired object (plus an optional {@code --oid} override). Shared by the
 * {@code diff --host} and {@code apply} live paths.
 */
public final class LiveTarget {

    final String restType;
    final String oid;

    private LiveTarget(String restType, String oid) {
        this.restType = restType;
        this.oid = oid;
    }

    /**
     * Resolve the REST type from the desired object's compile-time class and the OID from
     * {@code oidOverride} (if given) or the object itself.
     *
     * @throws SchemaException if the type cannot be mapped or no OID is available
     */
    public static LiveTarget resolve(PrismObject<? extends Objectable> desired, String oidOverride)
            throws SchemaException {
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
        if (oid == null || oid.isBlank()) {
            throw new SchemaException("No OID to address: pass --oid or include an oid in the desired object.");
        }
        return new LiveTarget(restType, oid);
    }
}
