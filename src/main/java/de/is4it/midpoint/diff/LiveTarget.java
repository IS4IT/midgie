/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

/**
 * The REST address of an object to fetch/patch: its collection type ({@code users}, {@code roles}, …) and
 * OID. Produced by {@link LiveLookup}, which owns the compile-time-class to REST-type mapping and the
 * {@code --oid}-else-object-OID precedence; this is only the pair they resolve to.
 */
public final class LiveTarget {

    final String restType;
    final String oid;

    private LiveTarget(String restType, String oid) {
        this.restType = restType;
        this.oid = oid;
    }

    /** A target from an already-resolved REST type and OID (the OID may be {@code null} for a create). */
    static LiveTarget of(String restType, String oid) {
        return new LiveTarget(restType, oid);
    }
}
