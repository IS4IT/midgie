/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.xml.ns._public.common.common_3.SchemaType;
import com.evolveum.prism.xml.ns._public.types_3.SchemaDefinitionType;

/**
 * Registers runtime extension schema with a {@link PrismContext} so custom {@code extension} attributes
 * (and custom object types) resolve during parse/diff. This mirrors what midPoint's own
 * {@code repo-common/SchemaCache} does at startup: collect the {@code <xsd:schema>} of each
 * {@code SchemaType} object and {@code registerDynamicSchemaExtensions} + {@code reload}.
 */
final class SchemaSupport {

    /** The XSD namespace of an {@code <xsd:schema>} document element. */
    static final String XSD_NAMESPACE = "http://www.w3.org/2001/XMLSchema";

    private SchemaSupport() {
    }

    /** True if {@code root} is an {@code <xsd:schema>} element (raw XSD document element). */
    static boolean isXsdSchema(Element root) {
        return root != null
                && "schema".equals(root.getLocalName())
                && XSD_NAMESPACE.equals(root.getNamespaceURI());
    }

    /** The {@code targetNamespace} declared by an {@code <xsd:schema>} element, or empty if none. */
    static String targetNamespace(Element xsdSchema) {
        return (xsdSchema == null) ? "" : xsdSchema.getAttribute("targetNamespace");
    }

    /** The namespaces this {@code <xsd:schema>} pulls in via top-level {@code <xsd:import>} (dependencies). */
    static List<String> importedNamespaces(Element xsdSchema) {
        List<String> namespaces = new ArrayList<>();
        if (xsdSchema == null) {
            return namespaces;
        }
        NodeList children = xsdSchema.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element
                    && "import".equals(element.getLocalName())
                    && XSD_NAMESPACE.equals(element.getNamespaceURI())) {
                String namespace = element.getAttribute("namespace");
                if (namespace != null && !namespace.isBlank()) {
                    namespaces.add(namespace);
                }
            }
        }
        return namespaces;
    }

    /** Register the given {@code description -> <xsd:schema> element} map and reload. No-op if empty. */
    static void register(PrismContext prismContext, Map<String, Element> schemas) throws SchemaException {
        if (schemas.isEmpty()) {
            return;
        }
        prismContext.getSchemaRegistry().registerDynamicSchemaExtensions(schemas);
        prismContext.reload();
    }

    /** Pull the {@code <xsd:schema>} element out of a {@code SchemaType} object. */
    static Element schemaElement(String description, Objectable objectable) throws SchemaException {
        if (!(objectable instanceof SchemaType)) {
            throw new SchemaException(description + " is not a SchemaType object (got "
                    + objectable.getClass().getSimpleName() + ").");
        }
        SchemaDefinitionType definition = ((SchemaType) objectable).getDefinition();
        Element schema = (definition != null) ? definition.getSchema() : null;
        if (schema == null) {
            throw new SchemaException(description + " has no <definition><schema> content.");
        }
        return schema;
    }
}
