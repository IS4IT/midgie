/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.Map;

import org.w3c.dom.Element;

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

    private SchemaSupport() {
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
