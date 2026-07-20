/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.util.exception.SchemaException;

/**
 * One discovered object file — a possibly-partial <em>fragment</em> of a midPoint object. Several files
 * may describe the same object (same OID, or same type+name); each is a fragment and, later, its own
 * apply unit. Identity is the OID when present, else {@code type/name} (see {@link #identityKey()}).
 */
final class Fragment {

    private final PrismObject<? extends Objectable> object;
    private final File sourceFile;
    private final String oid;
    private final Class<?> compileTimeClass;
    private final String name;

    private Fragment(PrismObject<? extends Objectable> object, File sourceFile) {
        this.object = object;
        this.sourceFile = sourceFile;
        this.oid = object.getOid();
        this.compileTimeClass = object.getCompileTimeClass();
        this.name = (object.getName() != null) ? object.getName().getOrig() : null;
    }

    static Fragment of(PrismObject<? extends Objectable> object, File sourceFile) {
        return new Fragment(object, sourceFile);
    }

    PrismObject<? extends Objectable> object() {
        return object;
    }

    File sourceFile() {
        return sourceFile;
    }

    String oid() {
        return oid;
    }

    Class<?> compileTimeClass() {
        return compileTimeClass;
    }

    String typeName() {
        return (compileTimeClass != null) ? compileTimeClass.getSimpleName() : "?";
    }

    String name() {
        return name;
    }

    /** Identity key: the OID if present, else {@code type/name}. Error if the object has neither. */
    String identityKey() throws SchemaException {
        if (oid != null && !oid.isBlank()) {
            return oid;
        }
        if (name != null && !name.isBlank() && compileTimeClass != null) {
            return typeName() + "/" + name;
        }
        throw new SchemaException("Cannot identify the object in " + sourceFile
                + ": it has neither an oid nor a name.");
    }

    /** Label for the identity column: the OID, or {@code (type/name)} for an OID-less object. */
    String identityLabel() {
        if (oid != null && !oid.isBlank()) {
            return oid;
        }
        return "(" + typeName() + "/" + (name != null ? name : "?") + ")";
    }
}
