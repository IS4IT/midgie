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
    private final Class<?> compileTimeClass;
    private final String name;

    private Fragment(PrismObject<? extends Objectable> object, File sourceFile) {
        this.object = object;
        this.sourceFile = sourceFile;
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

    /** Read from the object rather than cached, so an OID adopted from a sibling fragment is visible here. */
    String oid() {
        return object.getOid();
    }

    /**
     * Take the OID of the object this fragment turned out to describe.
     *
     * <p>A fragment that names an object without repeating its OID is a normal layout, and until it has the
     * OID it addresses nothing: it becomes its own graph node, nothing referencing the object orders itself
     * before it, and at apply time it is skipped as "no oid". The OID is identity, not content — it is an
     * attribute of the object element, never an item — so adopting it changes what is addressed and nothing
     * about the delta.
     */
    void adoptOid(String adopted) {
        if (adopted != null && !adopted.isBlank() && (object.getOid() == null || object.getOid().isBlank())) {
            object.setOid(adopted);
        }
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
        String oid = oid();
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
        String oid = oid();
        if (oid != null && !oid.isBlank()) {
            return oid;
        }
        return "(" + typeName() + "/" + (name != null ? name : "?") + ")";
    }

    /** The object's {@code Type/Name} path — the identity form the {@code --policy} selectors also use. */
    String objectPath() {
        return typeName() + "/" + (name != null && !name.isBlank() ? name : "?");
    }

    /** The OID for the {@code --debug} identity column, or {@code "-"} when the object has none. */
    String oidLabel() {
        String oid = oid();
        return (oid != null && !oid.isBlank()) ? oid : "-";
    }
}
