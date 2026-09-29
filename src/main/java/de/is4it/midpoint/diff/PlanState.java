/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.evolveum.midpoint.util.DOMUtil;

/**
 * The {@code midgie-state} rows a {@code deploy --plan} would have written, kept in the plan directory as
 * {@value #FILE} so that {@code --apply-plan} can write them for what it applies.
 *
 * <p>A plan-and-apply pipeline never runs a plain deploy, so without this its rows never moved: every later
 * plan compared the tree against an ever older last-applied, and a value added by one plan and dropped by the
 * next was never seen as removed. The rows are computed where the tree and its git history are — at plan time
 * — per object, each with the value it replaces, so the replay can tell whether another run has moved a row
 * since and leave that row alone.
 */
final class PlanState {

    static final String FILE = "state.xml";

    /**
     * One object's rows.
     *
     * @param entry the plan file that applies it, or {@code null} for an object already in sync, whose rows
     *              are written whatever else happens
     * @param oid   its OID, when known, for the rollback plan
     */
    record Unit(String entry, String oid, List<RollbackPlan.RowChange> rows) {
    }

    /**
     * What a plan records about tracking.
     *
     * @param deployment the deployment key, which also names the default rollback-plan directory
     * @param revision   the commit planned
     * @param deployKeys the {@code deploy:} row per deployed path
     * @param runRows    rows no one object claims, written only when every entry applied
     */
    record Contents(String deployment, String revision, List<String> deployKeys, List<Unit> units,
            List<RollbackPlan.RowChange> runRows) {
    }

    private PlanState() {
    }

    static void write(File dir, Contents contents) throws IOException {
        StringBuilder xml = new StringBuilder();
        xml.append(PlanStore.markGenerated("<planState>")).append(System.lineSeparator());
        element(xml, "deployment", contents.deployment());
        element(xml, "revision", contents.revision());
        for (String key : contents.deployKeys()) {
            element(xml, "deployKey", key);
        }
        for (Unit unit : contents.units()) {
            xml.append("  <unit");
            if (unit.entry() != null) {
                xml.append(" entry=\"").append(escape(unit.entry())).append('"');
            }
            if (unit.oid() != null) {
                xml.append(" oid=\"").append(escape(unit.oid())).append('"');
            }
            xml.append('>').append(System.lineSeparator());
            RollbackPlan.appendRows(xml, unit.rows(), "    ");
            xml.append("  </unit>").append(System.lineSeparator());
        }
        RollbackPlan.appendRows(xml, contents.runRows(), "  ");
        xml.append("</planState>").append(System.lineSeparator());
        java.nio.file.Path target = new File(dir, FILE).toPath();
        Io.restrictToOwner(target);
        Files.writeString(target, xml.toString(), StandardCharsets.UTF_8);
    }

    /** The plan's tracking record, or {@code null} when it has none (an untracked tree, an older midgie). */
    static Contents read(File dir) throws IOException {
        File file = new File(dir, FILE);
        if (!file.isFile()) {
            return null;
        }
        Element root;
        try {
            root = DOMUtil.parseFile(file).getDocumentElement();
        } catch (Exception e) {
            throw new IOException("cannot read " + file + ": " + e.getMessage(), e);
        }
        List<String> deployKeys = new ArrayList<>();
        List<Unit> units = new ArrayList<>();
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element child)) {
                continue;
            }
            if ("deployKey".equals(child.getTagName())) {
                deployKeys.add(child.getTextContent().trim());
            } else if ("unit".equals(child.getTagName())) {
                units.add(new Unit(attribute(child, "entry"), attribute(child, "oid"),
                        RollbackPlan.Manifest.rowsIn(child)));
            }
        }
        return new Contents(text(root, "deployment"), text(root, "revision"), deployKeys, units,
                RollbackPlan.Manifest.rowsIn(root));
    }

    private static void element(StringBuilder xml, String name, String value) {
        if (value != null && !value.isBlank()) {
            xml.append("  <").append(name).append('>').append(escape(value)).append("</").append(name).append('>')
                    .append(System.lineSeparator());
        }
    }

    private static String text(Element root, String name) {
        NodeList nodes = root.getElementsByTagName(name);
        if (nodes.getLength() == 0) {
            return null;
        }
        String value = nodes.item(0).getTextContent();
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static String attribute(Element element, String name) {
        return element.hasAttribute(name) ? element.getAttribute(name) : null;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
