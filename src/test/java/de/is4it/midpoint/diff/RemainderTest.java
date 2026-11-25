/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javax.xml.namespace.QName;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code pull} leaves out of a remainder file ({@link Exclusions}), how it writes one
 * ({@link RemainderWriter}), and where ({@link RemainderLayout}).
 */
class RemainderTest {

    private static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
    private static final String OID = "77777777-0000-0000-0000-00000000bbbb";

    private static PrismContext prismContext;

    @BeforeAll
    static void bootstrapPrism() throws Exception {
        prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    // --- Exclusions ---

    @Test
    void theServersBookkeepingIsLeftOut() throws Exception {
        PrismObject<? extends Objectable> live = prismContext.parseObject("<user xmlns=\"" + NS + "\" oid=\"" + OID
                + "\" version=\"7\"><metadata><createTimestamp>2026-01-01T00:00:00Z</createTimestamp></metadata>"
                + "<name>jdoe</name><assignment id=\"3\"><targetRef oid=\"11111111-0000-0000-0000-000000000001\" "
                + "type=\"RoleType\"/></assignment>"
                + "<credentials><password><value><t:clearValue xmlns:t=\"http://prism.evolveum.com/xml/ns/public/types-3\">"
                + "secret</t:clearValue></value></password></credentials></user>");

        Exclusions.Stripped stripped = Exclusions.strip(live, ApplyRules.none());
        PrismObject<? extends Objectable> object = stripped.object();
        Exclusions.dropIds(object);

        assertNull(object.getVersion());
        assertNull(object.findItem(ItemPath.create("metadata")), "operational");
        assertNull(object.findItem(ItemPath.create("credentials")), "protected, and nothing else was left in it");
        assertEquals(List.of("credentials/password/value"), stripped.protectedPaths());
        assertNotNull(object.findItem(ItemPath.create("assignment")));
        assertNull(object.findContainer(ItemPath.create("assignment")).getValues().get(0).getId(),
                "container ids are this server's numbering");
        assertNotNull(live.findItem(ItemPath.create("metadata")), "the live object itself is not touched");
    }

    @Test
    void anItemMarkedNeverIsLeftOut() throws Exception {
        PrismObject<? extends Objectable> live = prismContext.parseObject(sysconfig(
                "<logging><rootLoggerLevel>DEBUG</rootLoggerLevel></logging><description>kept</description>"));
        Policy policy = Policy.effective(null, "SystemConfigurationType/*/logging  apply=never\n");
        ApplyRules apply = policy.resolveFor("SystemConfigurationType", "SystemConfiguration", null,
                new StrategyOptions.Resolved(DiffEngine.Scope.PRESENT, DiffEngine.EquivalencePlan.auto(),
                        DiffEngine.MultiValueStrategy.replaceAll(), false)).apply;

        Exclusions.Stripped stripped = Exclusions.strip(live, apply);

        assertNull(stripped.object().findItem(ItemPath.create("logging")));
        assertEquals(1, stripped.neverPaths().size(), stripped.neverPaths().toString());
        assertNotNull(stripped.object().findItem(ItemPath.create("description")));
    }

    @Test
    void anEmptyContainerTheServerHoldsStays() throws Exception {
        // <cleanup/> is what says which work the task does; dropping it as "empty" changed the task.
        PrismObject<? extends Objectable> live = prismContext.parseObject("<task xmlns=\"" + NS + "\" oid=\"" + OID
                + "\"><name>Cleanup</name><activity><work><cleanup/></work></activity></task>");

        PrismObject<? extends Objectable> stripped = Exclusions.strip(live, ApplyRules.none()).object();

        assertNotNull(stripped.findItem(ItemPath.create("activity", "work", "cleanup")));
        String text = new RemainderWriter(prismContext).text(stripped);
        assertTrue(text.contains("<cleanup/>"), text);
    }

    // --- RemainderWriter ---

    @Test
    void theTextIsStableAndReadsBackAsTheObject() throws Exception {
        PrismObject<? extends Objectable> object = prismContext.parseObject(sysconfig(
                "<logging><rootLoggerLevel>INFO</rootLoggerLevel></logging>"));
        RemainderWriter writer = new RemainderWriter(prismContext);

        String text = writer.text(object);

        assertTrue(text.startsWith(RemainderWriter.HEADER), text);
        assertFalse(text.contains(PlanStore.GENERATED_MARKER), "discovery would skip the file: " + text);
        assertTrue(text.endsWith("</systemConfiguration>\n") && !text.contains("\r"), text);
        assertEquals(text, writer.text(prismContext.parseObject(text)), "a second pull writes the same bytes");
    }

    @Test
    void anObjectReadFromASearchIsWrittenUnderItsOwnElement() throws Exception {
        PrismObject<? extends Objectable> object = prismContext.parseObject(sysconfig(""));
        object.setElementName(new QName("http://midpoint.evolveum.com/xml/ns/public/common/api-types-3", "object"));

        String text = new RemainderWriter(prismContext).text(object);

        assertTrue(text.contains("<systemConfiguration"), text);
        assertFalse(text.contains("xsi:type"), text);
    }

    // --- RemainderLayout ---

    @Test
    void oneFolderPerTypeAndOneFilePerObject(@TempDir Path dir) {
        RemainderLayout layout = new RemainderLayout(dir.toFile());

        File file = layout.fileFor("RoleType", "Super User", OID);

        assertEquals(dir.resolve("RoleType").resolve("Super User." + OID + ".xml").toFile(), file);
        assertEquals(OID, RemainderLayout.oidOf(file));
        assertTrue(layout.contains(file));
        assertFalse(layout.contains(dir.resolveSibling("elsewhere.xml").toFile()));
    }

    @Test
    void aNameIsMadeSafeForEveryFileSystem() {
        assertEquals("ou=people_dc=example", RemainderLayout.safeName("ou=people/dc=example"));
        assertEquals("Simulation report_ Results", RemainderLayout.safeName("Simulation report: Results"));
        assertEquals("a_b_c", RemainderLayout.safeName("a\\b\tc"));
        assertEquals("trailing", RemainderLayout.safeName("trailing. "));
        assertEquals("_.hidden", RemainderLayout.safeName(".hidden"));
        assertEquals("_", RemainderLayout.safeName(""));
        assertEquals("é", RemainderLayout.safeName("é"), "NFC, as git sees it on every platform");
        assertEquals(80, RemainderLayout.safeName("x".repeat(200)).length());
    }

    @Test
    void existingFilesAreFoundByOidWhateverTheirName(@TempDir Path dir) throws Exception {
        Path roles = Files.createDirectories(dir.resolve("RoleType"));
        Files.writeString(roles.resolve("Old Name." + OID + ".xml"), "<role/>");
        Files.writeString(roles.resolve("README.md"), "not a remainder file");

        Map<String, File> existing = new RemainderLayout(dir.toFile()).existing();

        assertEquals(Map.of(OID, roles.resolve("Old Name." + OID + ".xml").toFile()), existing);
    }

    private static String sysconfig(String body) {
        return "<systemConfiguration xmlns=\"" + NS + "\" oid=\"00000000-0000-0000-0000-000000000001\">"
                + "<name>SystemConfiguration</name>" + body + "</systemConfiguration>";
    }
}
