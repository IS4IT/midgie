/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.util.DOMUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OidComments}: naming the objects a payload refers to by OID, in comments that never change what the
 * payload says, and taking them out again exactly.
 */
class OidCommentsTest {

    private static final String SELF = "11111111-1111-1111-1111-111111111111";
    private static final String SUPERUSER = "00000000-0000-0000-0000-000000000004";
    private static final String MISSING = "deadbeef-0000-0000-0000-000000000000";

    private static final String MODIFICATION = """
            <apti:objectModification
                    xmlns:apti="http://midpoint.evolveum.com/xml/ns/public/common/api-types-3"
                    xmlns:c="http://midpoint.evolveum.com/xml/ns/public/common/common-3"
                    xmlns:t="http://prism.evolveum.com/xml/ns/public/types-3"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                <apti:oid>11111111-1111-1111-1111-111111111111</apti:oid>
                <apti:itemDelta>
                    <t:modificationType>add</t:modificationType>
                    <t:path>c:inducement</t:path>
                    <t:value xsi:type="c:AssignmentType">
                        <c:targetRef oid="00000000-0000-0000-0000-000000000004" type="c:RoleType"/>
                    </t:value>
                    <t:value xsi:type="c:AssignmentType">
                        <c:targetRef oid="deadbeef-0000-0000-0000-000000000000" type="c:RoleType"/>
                    </t:value>
                </apti:itemDelta>
            </apti:objectModification>
            """;

    @Test
    void eachReferenceAndTheObjectItselfAreNamedAboveTheirLine() {
        List<String> asked = new ArrayList<>();
        OidComments names = new OidComments((type, oids) -> {
            asked.add(type + " " + oids);
            return Map.of(SUPERUSER, "Superuser", SELF, "Manager on the server");
        });
        names.know(SELF, "RoleType", "Manager");

        String annotated = names.annotate(MODIFICATION, SELF, "RoleType");

        assertTrue(annotated.contains("    <!-- RoleType \"Manager\" -->\n    <apti:oid>"),
                "a tree object is named from the tree:\n" + annotated);
        assertTrue(annotated.contains("            <!-- RoleType \"Superuser\" -->\n            <c:targetRef oid=\""
                + SUPERUSER), "indented like the element it names:\n" + annotated);
        assertTrue(annotated.contains("<!-- RoleType not found on the server -->"),
                "a dangling reference is said to be one, without repeating its OID:\n" + annotated);
        assertEquals(1, asked.size(), "one lookup per type: " + asked);
    }

    @Test
    void aTreeObjectNotOnTheServerYetIsOneThisDeployCreatesNotADanglingReference() {
        OidComments names = new OidComments((type, oids) -> Map.of(SUPERUSER, "Superuser"));
        names.know(MISSING, "RoleType", "New Role");

        String annotated = names.annotate(MODIFICATION, null, null);

        assertTrue(annotated.contains("<!-- RoleType \"New Role\" (not on the server yet, deployed with this tree) -->"),
                annotated);
        assertFalse(annotated.contains("not found"), annotated);

        // Once the tree is applied — the rollback plan is written after it — the object is simply named.
        names.treeDeployed();
        String afterwards = names.annotate(MODIFICATION, null, null);
        assertTrue(afterwards.contains("<!-- RoleType \"New Role\" -->"), afterwards);
    }

    @Test
    void aTreeObjectIsStillNamedWhenTheLookupFails() {
        OidComments names = new OidComments((type, oids) -> {
            throw new IllegalStateException("no read permission");
        });
        names.know(SELF, "RoleType", "Manager");

        String annotated = names.annotate(MODIFICATION, SELF, "RoleType");

        assertTrue(annotated.contains("<!-- RoleType \"Manager\" -->"), annotated);
        assertEquals(1, annotated.split("<!--").length - 1, "the rest stays bare:\n" + annotated);
    }

    @Test
    void strippingAnAnnotatedPayloadGivesBackTheOriginalByteForByte() {
        OidComments names = new OidComments((type, oids) -> Map.of(SUPERUSER, "Superuser"));
        names.know(SELF, "RoleType", "Manager");

        String annotated = names.annotate(MODIFICATION, SELF, "RoleType");

        assertFalse(annotated.equals(MODIFICATION), "something was annotated");
        assertEquals(MODIFICATION, OidComments.strip(annotated));
    }

    @Test
    void aRootOnTheDeclarationsLineComesBackByteForByteToo() {
        // A hand-written file with '<?xml …?><role oid=…>' on one line got a line break inserted there, which
        // stripping did not take out again.
        OidComments names = new OidComments((type, oids) -> Map.of(SUPERUSER, "Superuser"));
        names.know(SELF, "RoleType", "Manager");
        String oneLine = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><role xmlns=\"http://midpoint.evolveum.com/"
                + "xml/ns/public/common/common-3\" oid=\"" + SELF + "\">\n  <name>Manager</name>\n  <inducement>\n"
                + "    <targetRef oid=\"" + SUPERUSER + "\" type=\"RoleType\"/>\n  </inducement>\n</role>\n";

        String annotated = names.annotate(oneLine, SELF, "RoleType");

        assertFalse(annotated.equals(oneLine), "something was annotated");
        assertEquals(oneLine, OidComments.strip(annotated));
    }

    @Test
    void theServerIsAskedOnlyOncePerObjectForTheWholeRun() {
        List<String> asked = new ArrayList<>();
        OidComments names = new OidComments((type, oids) -> {
            asked.add(type + " " + oids);
            return Map.of(SUPERUSER, "Superuser");
        });

        names.annotate(MODIFICATION, null, null);
        names.annotate(MODIFICATION, null, null);

        assertEquals(1, asked.size(), "found and missing alike are cached: " + asked);
    }

    @Test
    void withoutAServerOnlyWhatIsKnownIsNamed() {
        OidComments names = new OidComments();
        names.know(SELF, "RoleType", "Manager");

        String annotated = names.annotate(MODIFICATION, SELF, "RoleType");

        assertTrue(annotated.contains("<!-- RoleType \"Manager\" -->"), annotated);
        assertEquals(1, annotated.split("<!--").length - 1, "the unknown references stay bare:\n" + annotated);
    }

    @Test
    void aFailingLookupLeavesAPayloadOfForeignReferencesUnnamedButIntact() {
        OidComments names = new OidComments((type, oids) -> {
            throw new IllegalStateException("no read permission");
        });

        assertEquals(MODIFICATION, names.annotate(MODIFICATION, null, null));
    }

    @Test
    void anOidInsideAScriptIsNotAReferenceAndIsLeftAlone() throws Exception {
        // A comment inserted into a CDATA script would change the script midPoint runs.
        String withScript = """
                <role xmlns="http://midpoint.evolveum.com/xml/ns/public/common/common-3" oid="11111111-1111-1111-1111-111111111111">
                    <name>Scripted</name>
                    <description><![CDATA[
                    <targetRef oid="00000000-0000-0000-0000-000000000004" type="c:RoleType"/>
                    ]]></description>
                </role>
                """;
        OidComments names = new OidComments((type, oids) -> Map.of(SUPERUSER, "Superuser"));

        String annotated = names.annotate(withScript, SELF, "RoleType");

        assertFalse(annotated.contains("Superuser"), "nothing inside CDATA is named:\n" + annotated);
        assertEquals(withScript, OidComments.strip(annotated));
    }

    @Test
    void aCreateBodyGetsItsObjectNamedAboveTheRootAndStillParses() throws Exception {
        String create = """
                <role xmlns="http://midpoint.evolveum.com/xml/ns/public/common/common-3"
                        oid="11111111-1111-1111-1111-111111111111">
                    <name>Manager</name>
                    <inducement>
                        <targetRef oid="00000000-0000-0000-0000-000000000004" type="RoleType"/>
                    </inducement>
                </role>
                """;
        OidComments names = new OidComments((type, oids) -> Map.of(SUPERUSER, "Superuser"));
        names.know(SELF, "RoleType", "Manager");

        String annotated = names.annotate(create, SELF, "RoleType");

        assertTrue(annotated.startsWith(
                        "<!-- RoleType \"Manager\" (not on the server yet, deployed with this tree) -->\n<role"),
                "the root tag's attributes span two lines, and the comment still goes above it:\n" + annotated);
        DOMUtil.parseDocument(annotated);
        assertEquals(create, OidComments.strip(annotated));
    }

    @Test
    void aNameThatCouldEndTheCommentIsDefused() throws Exception {
        OidComments names = new OidComments((type, oids) -> Map.of(SUPERUSER, "evil -- name-"));

        String annotated = names.annotate(MODIFICATION, null, null);

        assertTrue(annotated.contains("<!-- RoleType \"evil - - name-\" -->"), annotated);
        DOMUtil.parseDocument(annotated);
    }

    @Test
    void anXmlDeclarationStaysFirst() throws Exception {
        String declared = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + MODIFICATION;
        OidComments names = new OidComments();
        names.know(SELF, "RoleType", "Manager");

        String annotated = names.annotate(declared, SELF, "RoleType");

        assertTrue(annotated.startsWith("<?xml"), annotated);
        DOMUtil.parseDocument(annotated);
    }

    @Test
    void strippingLeavesTheChangeAndRemovesAHandWrittenCommentToo() {
        String reviewed = "<!-- generated by midgie -->\n<a>\n  <!-- looked at this, fine -->\n  <b/> <!-- inline -->\n"
                + "  <c><![CDATA[ <!-- part of a script --> ]]></c>\n</a>\n";

        assertEquals("<a>\n  <b/> \n  <c><![CDATA[ <!-- part of a script --> ]]></c>\n</a>\n",
                OidComments.strip(reviewed));
    }
}
