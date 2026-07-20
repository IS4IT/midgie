/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import org.w3c.dom.Element;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code deploy} subcommand — increment 1: discover a tree of desired-state object XML, sort it by
 * dependency, and print the resulting apply order. It is <b>read-only</b>: offline by default, and even
 * with {@code --host} it only <em>reads</em> the server's extension schema — no object is ever written.
 *
 * <p>The order is two-tier. First the extension <b>schemas</b> (discovered {@code .xsd} inputs and
 * deployable {@code SchemaType} objects), sorted among themselves by {@code <xsd:import>} dependency and
 * registered so objects that use extension attributes parse. Then the <b>objects</b>, grouped by identity
 * (OID, else type+name — several files may be fragments of one object) and sorted by their outbound
 * reference OIDs. Applying (create/patch), retry, and the plan/apply-plan modes are a later increment.
 *
 * <p>Exit codes: {@code 0} = discovered + sorted (warnings are still {@code 0}); {@code 2} = error (a
 * fragment contradiction, an unidentifiable object, an unreadable path, nothing to deploy, or a bad
 * {@code --host} connection). This increment never diffs, so there is no drift ({@code 1}) code.
 */
@Command(
        name = "deploy",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "Discover a folder tree of objects, sort by dependency, and print the apply order (read-only)."
)
public class DeployCommand implements Callable<Integer> {

    private static final int EXIT_ERROR = 2;

    @Parameters(index = "0..*", arity = "1..*", paramLabel = "PATH",
            description = "Files or directories of desired-state object XML, walked recursively.")
    List<File> paths;

    @Mixin
    SchemaOptions schemaOptions;

    @Mixin
    ConnectionOptions connectionOptions;

    @Option(names = {"--quiet", "-q"},
            description = "Suppress warnings and notes; keep the apply-order output and the exit code.")
    boolean quiet;

    @Option(names = {"--debug", "-v"},
            description = "Raise the log level to DEBUG (framework logs on stderr) and print the dependency edges.")
    boolean debug;

    @Override
    public Integer call() {
        if (debug) {
            Logging.enableDebug();
        }
        MidpointRestClient client = null;
        if (connectionOptions.isLive()) {
            client = connectionOptions.newClient();
            if (client == null) {
                return EXIT_ERROR;
            }
        }

        try {
            PrismContext prismContext = SchemaOptions.newBaseContext();
            if (client != null && !quiet) {
                VersionSupport.warnIfMismatch(prismContext, client);
            }

            // Collect every extension schema — CLI flags, live server, and the tree — and register in one
            // batch (prism replaces the dynamic set on each call, so it must be a single registration).
            Map<String, Element> schemaMap = schemaOptions.collectSchemas(prismContext, client);
            Discovery.ScanResult scan = Discovery.scan(prismContext, paths);
            for (SchemaFile schema : scan.schemas()) {
                schemaMap.put(schema.description(), schema.schemaElement());
            }
            SchemaSupport.register(prismContext, schemaMap);

            List<String> warnings = new ArrayList<>(scan.warnings());
            List<DeployObject> objects = DeployObject.group(parseObjects(prismContext, scan, warnings));

            List<SchemaFile> deployableSchemas = scan.schemas().stream()
                    .filter(SchemaFile::deployable).collect(Collectors.toList());
            List<SchemaFile> localSchemas = scan.schemas().stream()
                    .filter(schema -> !schema.deployable()).collect(Collectors.toList());

            if (deployableSchemas.isEmpty() && objects.isEmpty()) {
                System.err.println("midpoint-diff: no midPoint objects found under the given path(s).");
                return EXIT_ERROR;
            }

            TopoSort.Result<SchemaFile> schemaOrder = sortSchemas(deployableSchemas);
            TopoSort.Result<DeployObject> objectOrder = sortObjects(objects);

            printPlan(schemaOrder, objectOrder, localSchemas, warnings);
            return 0;
        } catch (Exception e) {
            String message = (e.getMessage() != null) ? e.getMessage() : e.toString();
            System.err.println("midpoint-diff: " + message);
            return EXIT_ERROR;
        }
    }

    /** Parse the candidate object files (schema is now registered); skip anything that isn't an object. */
    private static List<Fragment> parseObjects(PrismContext prismContext, Discovery.ScanResult scan,
            List<String> warnings) {
        List<Fragment> fragments = new ArrayList<>();
        for (File file : scan.objectFiles()) {
            PrismObject<? extends Objectable> object;
            try {
                object = prismContext.parseObject(file);
            } catch (Exception e) {
                warnings.add("skipped " + file + " — could not parse (" + e.getMessage() + ")");
                continue;
            }
            if (object.getCompileTimeClass() == null) {
                warnings.add("skipped " + file + " — not a recognized midPoint object type");
                continue;
            }
            fragments.add(Fragment.of(object, file));
        }
        return fragments;
    }

    private static TopoSort.Result<SchemaFile> sortSchemas(List<SchemaFile> schemas) {
        Map<String, SchemaFile> byNamespace = new LinkedHashMap<>();
        for (SchemaFile schema : schemas) {
            byNamespace.putIfAbsent(schema.targetNamespace(), schema);
        }
        return TopoSort.sort(schemas,
                schema -> schema.importedNamespaces().stream().map(byNamespace::get)
                        .filter(Objects::nonNull).collect(Collectors.toList()),
                Comparator.comparing(SchemaFile::targetNamespace)
                        .thenComparing(schema -> schema.sourceFile().getPath()));
    }

    private static TopoSort.Result<DeployObject> sortObjects(List<DeployObject> objects) {
        Map<String, DeployObject> byOid = new LinkedHashMap<>();
        for (DeployObject object : objects) {
            if (object.oid() != null) {
                byOid.putIfAbsent(object.oid(), object);
            }
        }
        return TopoSort.sort(objects,
                object -> object.outboundOids().stream().map(byOid::get)
                        .filter(Objects::nonNull).collect(Collectors.toList()),
                Comparator.comparing(DeployObject::typeName)
                        .thenComparing(object -> object.name() == null ? "" : object.name())
                        .thenComparing(DeployObject::identityKey));
    }

    // --- output ---

    private void printPlan(TopoSort.Result<SchemaFile> schemaOrder, TopoSort.Result<DeployObject> objectOrder,
            List<SchemaFile> localSchemas, List<String> warnings) {
        int steps = schemaOrder.ordered().size()
                + objectOrder.ordered().stream().mapToInt(object -> object.fragments().size()).sum();
        System.out.println("Apply order (" + steps + (steps == 1 ? " step):" : " steps):"));

        Map<String, Integer> schemaSeqByNamespace = new LinkedHashMap<>();
        Map<String, Integer> objectSeqByOid = new LinkedHashMap<>();
        int seq = 0;

        for (SchemaFile schema : schemaOrder.ordered()) {
            seq++;
            schemaSeqByNamespace.put(schema.targetNamespace(), seq);
            List<Integer> needs = schema.importedNamespaces().stream()
                    .map(schemaSeqByNamespace::get).filter(Objects::nonNull).sorted().collect(Collectors.toList());
            System.out.println(line(seq, schema.fragment()) + needsSuffix(needs));
        }

        for (DeployObject object : objectOrder.ordered()) {
            List<Integer> needs = object.outboundOids().stream()
                    .map(objectSeqByOid::get).filter(Objects::nonNull).sorted().collect(Collectors.toList());
            int firstSeq = seq + 1;
            boolean first = true;
            for (Fragment fragment : object.fragments()) {
                seq++;
                String suffix = first ? needsSuffix(needs) : "   # same object as #" + firstSeq;
                System.out.println(line(seq, fragment) + suffix);
                first = false;
            }
            if (object.oid() != null) {
                objectSeqByOid.put(object.oid(), firstSeq);
            }
        }

        if (!quiet) {
            for (SchemaFile local : localSchemas) {
                System.err.println("midpoint-diff: note: registered local schema (not deployed): "
                        + local.sourceFile() + " [" + local.targetNamespace() + "]");
            }
            for (SchemaFile schema : schemaOrder.brokenAt()) {
                System.err.println("midpoint-diff: warning: schema dependency cycle — broke it at "
                        + schema.sourceFile() + "; the order there is approximate.");
            }
            for (DeployObject object : objectOrder.brokenAt()) {
                System.err.println("midpoint-diff: warning: object dependency cycle — broke it at "
                        + object.identityLabel() + "; the order there is approximate.");
            }
            for (String warning : warnings) {
                System.err.println("midpoint-diff: warning: " + warning);
            }
        }
    }

    private static String line(int seq, Fragment fragment) {
        return String.format("  %2d  %-12s  %-38s  %-18s  %s", seq, fragment.typeName(),
                fragment.identityLabel(), fragment.name() != null ? fragment.name() : "", fragment.sourceFile());
    }

    private static String needsSuffix(List<Integer> needs) {
        if (needs.isEmpty()) {
            return "";
        }
        return "   (needs " + needs.stream().map(n -> "#" + n).collect(Collectors.joining(",")) + ")";
    }
}
