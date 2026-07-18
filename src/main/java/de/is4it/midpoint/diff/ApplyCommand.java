/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.Callable;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code apply} subcommand: reconcile a running midPoint object to the desired state. It fetches the
 * current object over REST, computes the minimal itemDelta (same engine as {@code diff}), and
 * {@code PATCH}es it. With {@code --create-if-absent}, a missing object is created via {@code POST}.
 *
 * <p>Idempotent: when the object already matches the desired state the delta is empty and nothing is
 * sent. Exit codes: {@code 0} = success (applied, created, or already in sync), {@code 2} = error.
 * {@code --dry-run} computes and prints the delta without sending it.
 */
@Command(
        name = "apply",
        mixinStandardHelpOptions = true,
        description = "Reconcile a live midPoint object to the desired state (diff, then PATCH/POST)."
)
public class ApplyCommand implements Callable<Integer> {

    private static final int EXIT_ERROR = 2;

    @Parameters(index = "0", paramLabel = "DESIRED", description = "Desired-state object XML.")
    File desiredFile;

    @Mixin
    StrategyOptions strategyOptions;

    @Mixin
    ConnectionOptions connectionOptions;

    @Mixin
    SchemaOptions schemaOptions;

    @Option(names = "--create-if-absent",
            description = "If the object does not exist, POST the whole desired object to create it.")
    boolean createIfAbsent;

    @Option(names = "--dry-run",
            description = "Compute and print the delta but do not send it.")
    boolean dryRun;

    @Option(names = "--format", paramLabel = "xml|summary", defaultValue = "xml",
            description = "Dry-run output format. xml: ObjectModificationType. summary: changed-path list.")
    String format;

    @Override
    public Integer call() {
        StrategyOptions.Resolved strategy = strategyOptions.resolve();
        if (strategy == null) {
            return EXIT_ERROR;
        }
        if (!connectionOptions.isLive()) {
            System.err.println("midpoint-diff: apply needs a running server — pass --host (and --user/--password).");
            return EXIT_ERROR;
        }
        MidpointRestClient client = connectionOptions.newClient();
        if (client == null) {
            return EXIT_ERROR;
        }

        try {
            PrismContext prismContext = schemaOptions.buildContext(client);

            PrismObject<? extends Objectable> desired = prismContext.parseObject(desiredFile);
            LiveTarget target = LiveTarget.resolve(desired, connectionOptions.oid);

            String liveXml = fetchOrCreate(client, target);
            if (liveXml == null) {
                return 0; // created (or would-create under dry-run); nothing more to reconcile
            }

            PrismObject<? extends Objectable> current = prismContext.parseObject(liveXml);
            DiffEngine.DiffResult result = new DiffEngine(prismContext)
                    .diff(desired, current, strategy.equivalence, strategy.scope, strategy.multiValue);

            for (String path : result.mergeFallbackPaths()) {
                System.err.println("midpoint-diff: note: '" + path + "' diffs to a whole-value replace; "
                        + "merge does not apply there, so it was left as replace.");
            }

            if (!result.hasDrift()) {
                System.out.println("No changes: " + target.restType + "/" + target.oid + " already matches desired.");
                return 0;
            }

            int changes = result.delta().getModifications().size();
            if (dryRun) {
                System.out.println("summary".equalsIgnoreCase(format)
                        ? DeltaOutput.toSummary(result.delta())
                        : DeltaOutput.toModificationXml(prismContext, result.delta()));
                System.err.println("midpoint-diff: dry run — " + changes + " change(s) NOT applied to "
                        + target.restType + "/" + target.oid + ".");
                return 0;
            }

            client.patchObject(target.restType, target.oid,
                    DeltaOutput.toModificationXml(prismContext, result.delta()));
            System.out.println("Applied " + changes + " change(s) to " + target.restType + "/" + target.oid + ".");
            return 0;
        } catch (Exception e) {
            String message = (e.getMessage() != null) ? e.getMessage() : e.toString();
            System.err.println("midpoint-diff: " + message);
            return EXIT_ERROR;
        }
    }

    /**
     * GET the current object, or handle a 404: create it (POST) when {@code --create-if-absent}, else fail.
     *
     * @return the live object XML to diff, or {@code null} when the object was created (nothing to diff)
     */
    private String fetchOrCreate(MidpointRestClient client, LiveTarget target) throws Exception {
        try {
            return client.getObject(target.restType, target.oid);
        } catch (MidpointRestClient.RestException e) {
            if (!e.isNotFound()) {
                throw e;
            }
            if (!createIfAbsent) {
                throw new IllegalStateException(target.restType + "/" + target.oid
                        + " does not exist. Pass --create-if-absent to create it.");
            }
            String desiredXml = Files.readString(desiredFile.toPath(), StandardCharsets.UTF_8);
            if (dryRun) {
                System.err.println("midpoint-diff: dry run — would create " + target.restType + "/"
                        + target.oid + " (object absent).");
                return null;
            }
            String createdOid = client.createObject(target.restType, desiredXml);
            System.out.println("Created " + target.restType + "/"
                    + (createdOid != null ? createdOid : target.oid) + ".");
            return null;
        }
    }
}
