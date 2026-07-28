# u5 — deploy pipeline: `Discovery`, `Fragment`, `DeployObject`, `TopoSort`, `ReferenceCollector`, `Reconciler`, `PlanStore`

Reviewed at `297aa6f` (working tree). Scope: content discovery, fragment identity + grouping, the two-tier
dependency sort, the reconcile decision table, and the `--plan` filename round-trip. Deliberate conventions
(arbitrary cycle break, out-of-set refs ignored, duplicate OID = fragments, multi-valued warning, file-by-file
apply, per-object rejection continues, exit codes) are not reported.

Format: `SEVERITY | file:line | failure | fix`

## Findings

major | src/main/java/de/is4it/midpoint/diff/Reconciler.java:97 | `plan()` resolves nothing for an OID-less object unless `--match-by-name` is set (`else { current = null; }`), so `--create-if-absent` alone plans `CREATE` for it on *every* run without ever asking the server. A pipeline running `deploy /config --url … -c -y` over a tree that contains OID-less files (the normal hand-written style) succeeds the first time and then POSTs the same objects again on the second run: midPoint rejects the duplicate `name` with 409, the unit is recorded as failed and the run exits 2 — the documented idempotent re-run only holds for OID-carrying objects. `ReconcilerTest.oidlessWithCreateIfAbsentPlansACreate` pins the current behaviour; every live test that uses `-c` on an OID-less object pairs it with `-m`. | When `createIfAbsent` is set and the object has no OID, do the type+name search anyway (`LiveLookup.byName` is already there and is what `-m` uses) — or refuse `-c` for an OID-less unit unless `-m` is given, instead of silently creating a duplicate.

major | src/main/java/de/is4it/midpoint/diff/TopoSort.java:87 | The cycle break picks "the remaining node with the fewest unmet prerequisites", but candidates are not restricted to nodes *on* a cycle — every remaining node has in-degree ≥ 1 at that point, so a plain dependent of a cycle member ties with the cycle members and the `readyOrder` tiebreak can pick it. Verified: roles `Mid1`↔`Mid2` (mutual inducement) plus `Aaa` inducing only `Mid1` produces the order `Aaa, Mid1, Mid2` — `Aaa` is applied before the role it induces (with `-c` its POST references a not-yet-created target), and *two* "dependency cycle — broke it at …" warnings are printed, the first naming `Aaa`, which is not in any cycle. Ordering correctly degrades only inside the cycle; here it leaks to acyclic nodes and the diagnostic points at the wrong object. | Restrict the forced candidates to nodes that lie on a cycle (a node reachable from itself through `successors`), and only fall back to the current min-in-degree pick if that set is empty.

major | src/main/java/de/is4it/midpoint/diff/PlanStore.java:69 | `write()` accepts a null/blank OID for `Kind.PATCH` and silently substitutes the sanitised object *name* as the filename token; `parse()` then hands that name back as `Entry.oid` and `--apply-plan` issues `PATCH /ws/rest/<type>/<Name>`, which is not an OID and fails at replay time. This is reachable today: in `--plan`, the second fragment of an OID-less object is planned via `Reconciler.planAgainst` against a clone that has no OID, so `Reconciler.java:117` computes `targetOid == null` and returns a `PATCH` with `oid == null`. The plan file looks valid under review and only breaks when it is executed. (Same defect the `DeployCommand` review reports at :726 — the guard belongs here, in the store that encodes the address.) | Throw from `write()` when `kind == PATCH` and the OID is blank ("a patch plan entry must carry an OID"); a name token is only meaningful for a create body.

minor | src/main/java/de/is4it/midpoint/diff/Discovery.java:112 | `collectFiles` never dedupes, so overlapping arguments walk the same files twice. Verified: `deploy src/test/resources/deploy src/test/resources/deploy/roles` prints 11 apply units instead of 8, lists every role file twice (so a live run reconciles and PATCHes each of them twice), and emits the bogus warning "…/manager.xml and …/manager.xml both set the multi-valued 'inducement'" — the same file on both sides of a clobber warning that the non-overlapping run does not produce. `deploy config config/roles` is an easy mistake to make in a pipeline. | Dedupe by `File.getCanonicalFile()` (or `Path.toRealPath()`) while collecting, before the sort.

minor | src/main/java/de/is4it/midpoint/diff/PlanStore.java:70 | The sequence is written with `%03d`, and `read()` orders by filename (`Arrays.sort(File[])` = lexicographic). Past 999 apply units the padding stops working and `1000-…` sorts *before* `999-…`, so `--apply-plan` replays the tail of a large tree out of dependency order — objects get created before the objects they reference, for a tree whose plan was reviewed as correctly ordered. A midPoint config repo with 1000+ fragment files is not exotic. | Pad to the width of the total unit count (or a fixed `%05d`), and/or sort in `read()` on the parsed numeric sequence rather than on the raw filename.

minor | src/main/java/de/is4it/midpoint/diff/PlanStore.java:110 | `parse()` returns `parts[1]`/`parts[2]` with no validation, and `MidpointRestClient.objectUri` builds the request URI by raw string concatenation (`restBase + "/" + restType + "/" + oid`). A plan directory that has been renamed, hand-edited, or shipped through CI as an artifact can therefore retarget a write with no error: `001-users-<oid>#tail.patch.xml` patches the truncated OID before the `#`, `001-nodes-x.patch.xml` addresses a different REST collection, and a token containing a space makes `URI.create` throw a bare `IllegalArgumentException`. The whole point of `--apply-plan` is that what was reviewed is what runs. | Validate before use: the type against `[A-Za-z]+` (REST collections are camelCase — confirmed in `ObjectTypes`) and the token against the OID charset `[A-Za-z0-9._-]+`, rejecting the file otherwise.

minor | src/main/java/de/is4it/midpoint/diff/Fragment.java:64 | Identity is "OID, else type/name" with no bridge between the two forms, so one object described by a fragment that carries its OID plus a fragment that carries only the name becomes **two** graph nodes: the cross-fragment contradiction check and the multi-valued clobber warning never run between them, the OID-less node gets no inbound edge (nothing that references the object orders itself before it), and at apply time the OID-less file is reported only as "skipped — no oid" unless `-m` is passed. Dropping the OID from one file of a fragment set is exactly the kind of edit that looks harmless. | When grouping, index groups by `(type, name)` as well as by OID and let an OID-less fragment join the matching OID-carrying group, inheriting its OID for addressing.

minor | src/main/java/de/is4it/midpoint/diff/Reconciler.java:159 | The OID-less skip reason is a fixed string that ends "…or --match-by-name to match by name" — it is also produced when `--match-by-name` **is** set and the name search simply found nothing (`matches.isEmpty()` → `current == null` → `planCreate` → `skip`). The operator is told to pass a flag they already passed and concludes the flag is being ignored. | Branch the message on `matchByName`: "no object named '<name>' on the server; pass --create-if-absent to create it".

## Checked and found sound

- Diff direction: `Reconciler.planPatch` calls `DiffEngine.diff(desired, current, …)`, and `DiffEngine.reconcile`
  does `live.diff(desired)` — the delta transforms live → desired, as DESIGN requires.
- `TopoSort` determinism: `LinkedHashSet` node order + a total `readyOrder` comparator in both the
  `PriorityQueue` and the forced pick; per-node prerequisites are deduped before counting in-degree, so
  in-degree cannot overcount; self-references and out-of-set prerequisites are dropped; the `!emitted.add`
  guard and the `emitted.contains(successor)` skip make double emission and negative in-degrees impossible;
  the loop cannot spin (`orElseThrow` is unreachable while `ordered.size() < nodeSet.size()`).
- `ReferenceCollector`: the prism visitor reaches every `PrismReferenceValue` in the subtree, the object's own
  OID is stripped, and `DeployObject` strips the group's OID again — no self-edge from either side. References
  that carry only a filter/name legitimately produce no edge; `--retry` is the documented answer.
- `DeployObject` grouping: `seen`/`multiSeenIn`/`multiWarned` are per group; the contradiction check fires on
  the first differing single-valued value and correctly ignores operational items and multi-valued containers;
  the type check does catch the same-OID/different-type case (a different type with the same name lands in a
  different group by construction). Group order and fragment order within a group are deterministic.
- `PlanStore` filename round-trip for tool-written names: REST collection names are camelCase with no `-`
  (verified against `ObjectTypes` in the midPoint checkout) and the sequence is digits, so `split("-", 3)`
  recovers a dashed UUID intact; `sanitize` strips everything outside `[A-Za-z0-9]`, so a name with spaces or
  separators cannot break the split; `hasPlanFiles`/`read` filter on the same two suffixes.
- `Discovery`: `SchemaSupport.targetNamespace` returns `""` (never null) for a missing attribute, so the
  namespace comparator and the `byNamespace` lookup in `DeployCommand.sortSchemas` cannot NPE or produce a
  spurious edge from a blank import; non-well-formed XML, unparseable objects and unrecognised types are
  warnings, not aborts; the file walk order is stabilised by an explicit path sort.
