/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code pull} bringing a managed file in line with the server: the items it declares take the server's values,
 * and every other character of the file stays where it was. Each case is the whole file before and after.
 */
class ManagedFileEditorTest {

    private static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
    private static final String OID = "77777777-0000-0000-0000-00000000cccc";

    private static PrismContext prismContext;

    @BeforeAll
    static void bootstrapPrism() throws Exception {
        prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    @Test
    void aChangedValueIsTheOnlyChange() throws Exception {
        String file = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!-- the help desk role -->
                <c:role xmlns:c="%s" oid="%s">
                    <c:name>Help Desk</c:name>
                    <!-- why the description matters -->
                    <c:description>old text</c:description>
                </c:role>
                """.formatted(NS, OID);

        String edited = pull(file, role("<description>new &amp; better</description>"));

        assertEquals(file.replace("old text", "new &amp; better"), edited);
    }

    @Test
    void aChangedScriptKeepsItsElementsAndItsCdata() throws Exception {
        String file = """
                <objectTemplate xmlns="%s" oid="%s">
                    <name>Template</name>
                    <mapping>
                        <name>full name</name>
                        <strength>strong</strength>
                        <expression>
                            <script>
                                <code><![CDATA[
                                    return givenName + ' ' + familyName
                                ]]></code>
                            </script>
                        </expression>
                        <target>
                            <path>fullName</path>
                        </target>
                    </mapping>
                </objectTemplate>
                """.formatted(NS, OID);
        String live = file.replace("return givenName + ' ' + familyName", "return familyName + ', ' + givenName");

        String edited = pull(file, live);

        assertEquals(live, edited, "only the code changes, still in its CDATA section");
    }

    @Test
    void aValueTheServerDroppedGoesWithItsLine() throws Exception {
        String file = """
                <role xmlns="%s" oid="%s">
                    <name>Help Desk</name>
                    <subtype>first</subtype>
                    <subtype>second</subtype>
                    <subtype>third</subtype>
                </role>
                """.formatted(NS, OID);

        String edited = pull(file, role("<subtype>first</subtype><subtype>third</subtype>"));

        assertEquals(file.replace("    <subtype>second</subtype>\n", ""), edited,
                "the second element is the second value: the one the server dropped");
    }

    @Test
    void anEntryTheServerHasNoCounterpartForIsRemoved() throws Exception {
        String file = """
                <role xmlns="%s" oid="%s">
                    <name>Help Desk</name>
                    <inducement>
                        <targetRef oid="11111111-0000-0000-0000-000000000001" type="RoleType"/>
                    </inducement>
                    <inducement>
                        <targetRef oid="11111111-0000-0000-0000-000000000002" type="RoleType"/>
                    </inducement>
                </role>
                """.formatted(NS, OID);

        String edited = pull(file, role("<inducement><targetRef oid=\"11111111-0000-0000-0000-000000000001\" "
                + "type=\"RoleType\"/></inducement><inducement><targetRef oid=\"11111111-0000-0000-0000-000000000003\" "
                + "type=\"RoleType\"/></inducement>"));

        assertEquals(file.replace("""
                    <inducement>
                        <targetRef oid="11111111-0000-0000-0000-000000000002" type="RoleType"/>
                    </inducement>
                """, ""), edited, "the server's other entry goes to the remainder, not into this file");
    }

    @Test
    void aContainerLeftEmptyGoesWhole() throws Exception {
        String file = """
                <systemConfiguration xmlns="%s" oid="00000000-0000-0000-0000-000000000001">
                    <name>SystemConfiguration</name>
                    <logging>
                        <rootLoggerLevel>DEBUG</rootLoggerLevel>
                    </logging>
                </systemConfiguration>
                """.formatted(NS);

        String edited = pull(file, """
                <systemConfiguration xmlns="%s" oid="00000000-0000-0000-0000-000000000001">
                    <name>SystemConfiguration</name>
                    <logging><rootLoggerAppender>A</rootLoggerAppender></logging>
                </systemConfiguration>
                """.formatted(NS));

        assertEquals("""
                <systemConfiguration xmlns="%s" oid="00000000-0000-0000-0000-000000000001">
                    <name>SystemConfiguration</name>
                </systemConfiguration>
                """.formatted(NS), edited);
    }

    @Test
    void anEntryPairedByIdAloneIsWrittenWholeWhereTheOldOneStood() throws Exception {
        String file = """
                <role xmlns="%s" oid="%s">
                    <name>Help Desk</name>
                    <inducement id="5">
                        <description>old</description>
                        <targetRef oid="11111111-0000-0000-0000-000000000001" type="RoleType"/>
                    </inducement>
                </role>
                """.formatted(NS, OID);

        String edited = pull(file, role("<inducement id=\"5\"><description>new</description><order>2</order>"
                + "<targetRef oid=\"11111111-0000-0000-0000-000000000001\" type=\"RoleType\"/></inducement>"));

        // The server's targetRef names its type with the c: prefix, which this file does not bind: the element brings
        // its own declaration rather than guess at another prefix.
        assertTrue(edited.contains("""
                    <inducement xmlns:c="%s">
                        <description>new</description>
                        <targetRef oid="11111111-0000-0000-0000-000000000001" type="c:RoleType"/>
                        <order>2</order>
                    </inducement>
                """.formatted(NS)), edited);
        assertTrue(edited.startsWith(file.substring(0, file.indexOf("    <inducement"))), edited);
        assertTrue(edited.endsWith("    </inducement>\n</role>\n"), edited);
    }

    @Test
    void lineEndingsAndTheByteOrderMarkStay() throws Exception {
        String file = ("﻿<role xmlns=\"" + NS + "\" oid=\"" + OID + "\">\r\n"
                + "    <name>Help Desk</name>\r\n"
                + "    <description>old</description>\r\n"
                + "    <subtype>gone</subtype>\r\n"
                + "</role>\r\n");

        String edited = pull(file, role("<description>new</description>"));

        assertEquals("﻿<role xmlns=\"" + NS + "\" oid=\"" + OID + "\">\r\n"
                + "    <name>Help Desk</name>\r\n"
                + "    <description>new</description>\r\n"
                + "</role>\r\n", edited);
    }

    @Test
    void anOnCreateItemKeepsTheValueTheObjectIsCreatedWith() throws Exception {
        String file = "<task xmlns=\"" + NS + "\" oid=\"" + OID + "\"><name>Nightly</name>"
                + "<executionState>suspended</executionState></task>";
        PrismObject<? extends Objectable> live = prismContext.parseObject(file.replace("suspended", "runnable"));
        ApplyRules builtIns = Policy.effective(null).resolveFor("TaskType", "Nightly", OID,
                new StrategyOptions.Resolved(DiffEngine.Scope.PRESENT, DiffEngine.EquivalencePlan.auto(),
                        DiffEngine.MultiValueStrategy.replaceAll(), false)).apply;

        List<ManagedProjection.Edit> edits = ManagedProjection.edits(prismContext.parseObject(file),
                Exclusions.strip(live, builtIns).object(), new ManagedProjection.Skip(builtIns, Set.of()));

        assertTrue(edits.isEmpty(), ManagedProjection.labels(edits).toString());
    }

    @Test
    void aFileInAnotherEncodingIsRefused() throws Exception {
        String file = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n<role xmlns=\"" + NS + "\" oid=\"" + OID
                + "\"><name>Help Desk</name><description>old</description></role>\n";

        ManagedFileEditor.Refused refused =
                assertThrows(ManagedFileEditor.Refused.class, () -> pull(file, role("<description>new</description>")));

        assertTrue(refused.getMessage().contains("ISO-8859-1"), refused.getMessage());
    }

    // --- helpers ---

    /** The file as pull leaves it, given the server's object. */
    private static String pull(String file, String live) throws Exception {
        String body = file.startsWith("﻿") ? file.substring(1) : file;
        PrismObject<? extends Objectable> parsed = prismContext.parseObject(body);
        PrismObject<? extends Objectable> server =
                Exclusions.strip(prismContext.parseObject(live), ApplyRules.none()).object();
        List<ManagedProjection.Edit> edits = ManagedProjection.edits(parsed, server,
                new ManagedProjection.Skip(ApplyRules.none(), Set.of()));
        return new ManagedFileEditor(prismContext).edit(file, parsed, edits).text();
    }

    private static String role(String body) {
        return "<role xmlns=\"" + NS + "\" oid=\"" + OID + "\"><name>Help Desk</name>" + body + "</role>";
    }
}
