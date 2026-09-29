/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.namespace.QName;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContainer;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A keyed entry that {@code --equivalence auto} cannot pair with its live copy, because something inside it
 * leaves its own natural key empty.
 *
 * <p>The case that surfaced it: a resource's {@code schemaHandling/objectType}, keyed by {@code kind + intent}
 * and filled in, holding a synchronization reaction whose {@code <addFocus/>} has no {@code <name>}. Auto
 * compares the whole object type by content, so an edit inside it diffs to "add the new form, delete the
 * old". On a tracked tree the list is on merge, which dropped the delete as a value beyond the tree — and the
 * server ended up with two object types of one kind and intent, which midPoint refuses to load.
 */
class KeyedEntryReplacementTest {

    private static final String NS = "http://midpoint.evolveum.com/xml/ns/public/common/common-3";
    private static final String OID = "a17e57e5-0000-0000-0000-0000000000f2";
    private static final ItemPath OBJECT_TYPES = ItemPath.create(
            new QName(NS, "schemaHandling"), new QName(NS, "objectType"));

    private static PrismContext prismContext;

    private Level rootLevel;
    private Level ourLevel;

    @BeforeAll
    static void bootstrapPrism() throws Exception {
        prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
    }

    @BeforeEach
    void rememberLevels() {
        rootLevel = root().getLevel();
        ourLevel = ours().getLevel();
    }

    @AfterEach
    void restoreLevels() {
        // 'diff -v' switches DEBUG on for the whole JVM; left on, every later test's stderr carries log lines.
        root().setLevel(rootLevel);
        ours().setLevel(ourLevel);
    }

    private static Logger root() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }

    private static Logger ours() {
        return (Logger) LoggerFactory.getLogger(Logging.class.getPackageName());
    }

    @Test
    void aChangePrismCannotApplyLocallySaysSoInsteadOfPassingTheDuplicateCheck() throws Exception {
        // Two values for a single-valued item: the projection fails, and the duplicate check with it.
        PrismObject<? extends Objectable> live = prismContext.parseObject("<role xmlns=\"" + NS + "\" oid=\"" + OID
                + "\"><name>r</name></role>");
        com.evolveum.midpoint.prism.delta.ObjectDelta<com.evolveum.midpoint.xml.ns._public.common.common_3.RoleType>
                delta = prismContext.deltaFor(com.evolveum.midpoint.xml.ns._public.common.common_3.RoleType.class)
                .item(com.evolveum.midpoint.xml.ns._public.common.common_3.RoleType.F_DESCRIPTION)
                .replace("one", "two").asObjectDelta(OID);

        DiffEngine.Projection projection = DiffEngine.project(live, delta);

        assertEquals(null, projection.after());
        assertTrue(projection.failure() != null && !projection.failure().isBlank(), String.valueOf(projection));
        assertTrue(DiffEngine.unprojectedNote(projection.failure()).contains("not checked"));
    }

    @Test
    void mergeReplacesAKeyedEntryItCouldNotPairInsteadOfAddingASecondCopy() throws Exception {
        PrismObject<? extends Objectable> live = parse(resource(
                objectType(2L, "task", "Old"), objectType(10L, "other", "Someone else's")));
        PrismObject<? extends Objectable> desired = parse(resource(objectType(null, "task", "New")));

        DiffEngine.DiffResult result = new DiffEngine(prismContext).diff(desired, live,
                DiffEngine.EquivalencePlan.auto(), DiffEngine.Scope.PRESENT,
                DiffEngine.MultiValueStrategy.of(true, List.of()), DiffEngine.SmartReferenceCheck.NEVER);

        assertTrue(result.keyPairingDeclined().stream()
                        .anyMatch(m -> m.startsWith("schemaHandling/objectType ") && m.contains("addFocus")),
                "auto must say it compared the object types by content, and name the unnamed action: "
                        + result.keyPairingDeclined());
        PrismObject<? extends Objectable> after = DiffEngine.project(live, result.delta()).after();
        // The edited entry is replaced, not duplicated; the entry the tree never declared stays, as merge
        // promises.
        assertEquals(List.of("other=Someone else's", "task=New"), objectTypes(after),
                "the PATCH must leave one task object type, the new one: " + result.delta());
        assertEquals(List.of(), NaturalKeys.introducedDuplicates(live, after));
    }

    @Test
    void aStrayCopyOfADeclaredEntryIsRemovedUnderMerge() throws Exception {
        // What a server damaged by the bug above holds: the new form beside the old. The tree declares the entry
        // once, so the copy that is not it is that entry's old form, not someone else's value, and goes.
        PrismObject<? extends Objectable> live = parse(resource(
                objectType(2L, "task", "Old"), objectType(10L, "task", "New")));
        PrismObject<? extends Objectable> desired = parse(resource(objectType(null, "task", "New")));

        DiffEngine.DiffResult result = new DiffEngine(prismContext).diff(desired, live,
                DiffEngine.EquivalencePlan.auto(), DiffEngine.Scope.PRESENT,
                DiffEngine.MultiValueStrategy.of(true, List.of()), DiffEngine.SmartReferenceCheck.NEVER);

        assertEquals(List.of("task=New"), objectTypes(DiffEngine.project(live, result.delta()).after()),
                "the stray copy must be deleted: " + result.delta());
    }

    @Test
    void aPatchThatWouldLeaveOneKeyTwiceIsRefused() throws Exception {
        // The tree declares the entry twice, so no pairing can save it: the second copy would be added beside
        // the first.
        PrismObject<? extends Objectable> live = parse(resource(objectType(2L, "task", "A")));
        PrismObject<? extends Objectable> desired = parse(resource(
                objectType(null, "task", "A"), objectType(null, "task", "B")));

        Reconciler.Outcome outcome = new Reconciler(prismContext, null, true, true)
                .planAgainst(desired, live, mergeStrategy());

        assertEquals(Reconciler.Action.FAIL, outcome.action, outcome.detail);
        assertTrue(outcome.detail.contains("schemaHandling/objectType[kind=GENERIC, intent=task] ×2"),
                outcome.detail);
        assertTrue(outcome.keyPairingDeclined.stream().anyMatch(m -> m.contains("addFocus")),
                "a refusal must still carry what -v explains it with: " + outcome.keyPairingDeclined);
    }

    @Test
    void aDuplicateTheServerAlreadyHeldDoesNotBlockAnUnrelatedChange() throws Exception {
        PrismObject<? extends Objectable> live = parse("<resource xmlns=\"" + NS + "\" oid=\"" + OID + "\">"
                + "<name>r</name><description>old</description><schemaHandling>"
                + objectType(2L, "task", "A") + objectType(10L, "task", "B") + "</schemaHandling></resource>");
        PrismObject<? extends Objectable> desired = parse("<resource xmlns=\"" + NS + "\" oid=\"" + OID + "\">"
                + "<name>r</name><description>new</description></resource>");

        Reconciler.Outcome outcome = new Reconciler(prismContext, null, true, true)
                .planAgainst(desired, live, mergeStrategy());

        assertEquals(Reconciler.Action.PATCH, outcome.action, outcome.detail);
    }

    @Test
    void offlineDiffNamesTheFallbackUnderVerboseAndRefusesADuplicate(@TempDir Path dir) throws Exception {
        Path live = write(dir, "live.xml", resource(objectType(2L, "task", "Old")));
        Path desired = write(dir, "desired.xml", resource(objectType(null, "task", "New")));
        Path twice = write(dir, "twice.xml", resource(
                objectType(null, "task", "Old"), objectType(null, "task", "New")));

        Result quiet = run("diff", desired.toString(), live.toString());
        assertEquals(1, quiet.exit, quiet.err);
        assertFalse(quiet.err.contains("compared as whole values"), "the note is for -v only: " + quiet.err);

        Result verbose = run("diff", desired.toString(), live.toString(), "-v");
        assertEquals(1, verbose.exit, verbose.err);
        assertTrue(verbose.err.contains("midgie: note: schemaHandling/objectType is compared as whole values, "
                + "not paired by natural key: schemaHandling/objectType/synchronization/reaction/actions/addFocus "
                + "does not fill in its key (name)"), verbose.err);

        Result refused = run("diff", twice.toString(), live.toString());
        assertEquals(2, refused.exit, refused.err);
        assertTrue(refused.err.contains("refusing a change that would leave "
                + "schemaHandling/objectType[kind=GENERIC, intent=task] ×2"), refused.err);
    }

    // --- fixtures ---

    private static StrategyOptions.Resolved mergeStrategy() {
        return new StrategyOptions.Resolved(DiffEngine.Scope.PRESENT, DiffEngine.EquivalencePlan.auto(),
                DiffEngine.MultiValueStrategy.of(true, List.of()), false);
    }

    private static String resource(String... objectTypes) {
        return "<resource xmlns=\"" + NS + "\" oid=\"" + OID + "\"><name>r</name><schemaHandling>"
                + String.join("", objectTypes) + "</schemaHandling></resource>";
    }

    /**
     * A {@code generic} object type with one reaction whose {@code <addFocus/>} carries no {@code <name>} — its
     * natural key — so auto compares the whole object type by content. A live one ({@code id} set) has ids on
     * the values inside it too, as the server hands them out.
     */
    private static String objectType(Long id, String intent, String displayName) {
        return "<objectType" + idAttribute(id, 0) + "><kind>generic</kind><intent>" + intent + "</intent>"
                + "<displayName>" + displayName + "</displayName>"
                + "<synchronization><reaction" + idAttribute(id, 1) + "><name>add</name>"
                + "<situation>unmatched</situation><actions><addFocus" + idAttribute(id, 2) + "/></actions>"
                + "</reaction></synchronization></objectType>";
    }

    private static String idAttribute(Long base, int offset) {
        return (base != null) ? " id=\"" + (base + offset) + "\"" : "";
    }

    /** The object types as {@code intent=displayName}, sorted. */
    private static List<String> objectTypes(PrismObject<? extends Objectable> object) {
        List<String> found = new ArrayList<>();
        Item<?, ?> item = object.findItem(OBJECT_TYPES);
        if (item instanceof PrismContainer<?> container) {
            for (PrismContainerValue<?> value : container.getValues()) {
                found.add(value.findProperty(ItemPath.create(new QName(NS, "intent")))
                        .getRealValue() + "=" + value.findProperty(ItemPath.create(
                                new QName(NS, "displayName"))).getRealValue());
            }
        }
        found.sort(null);
        return found;
    }

    private static PrismObject<? extends Objectable> parse(String xml) throws Exception {
        return prismContext.parseObject(xml);
    }

    private static Path write(Path dir, String name, String content) throws Exception {
        return Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    private static Result run(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int exit = Main.commandLine().execute(args);
            return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record Result(int exit, String out, String err) {
    }
}
