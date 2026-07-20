/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.util.List;

import org.w3c.dom.Element;

/**
 * One discovered extension-schema file. Either a raw {@code .xsd} (local schema input, like
 * {@code --schema-dir} — registered so objects parse, but not itself deployable) or an exported
 * {@code SchemaType} {@code .xml} (both schema input <em>and</em> a deployable object apply unit). The
 * {@code targetNamespace} and the namespaces pulled in via {@code <xsd:import>} drive the schema-tier
 * dependency sort.
 */
final class SchemaFile {

    private final File sourceFile;
    private final Element schemaElement;
    private final String targetNamespace;
    private final List<String> importedNamespaces;
    private final Fragment fragment; // non-null for a SchemaType object (a deployable apply unit)

    SchemaFile(File sourceFile, Element schemaElement, String targetNamespace,
            List<String> importedNamespaces, Fragment fragment) {
        this.sourceFile = sourceFile;
        this.schemaElement = schemaElement;
        this.targetNamespace = targetNamespace;
        this.importedNamespaces = importedNamespaces;
        this.fragment = fragment;
    }

    File sourceFile() {
        return sourceFile;
    }

    Element schemaElement() {
        return schemaElement;
    }

    String targetNamespace() {
        return targetNamespace;
    }

    List<String> importedNamespaces() {
        return importedNamespaces;
    }

    /** True for a {@code SchemaType} object (deployable); false for a raw {@code .xsd} (local input only). */
    boolean deployable() {
        return fragment != null;
    }

    Fragment fragment() {
        return fragment;
    }

    /** Map key used when registering this schema's {@code <xsd:schema>} with the context. */
    String description() {
        return "deploy schema " + sourceFile.getName() + " (" + targetNamespace + ")";
    }
}
