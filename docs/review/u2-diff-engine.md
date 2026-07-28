# u2 — `DiffEngine`, `DeltaOutput`, `NaturalKeys`, `XmlPretty`

Reviewed at `740747f` (working tree). Scope: the engine that decides what gets added **and deleted** on a
live server — the post-passes over the prism delta (`applyMerge`, `retainItemsPresentIn`,
`stripProtectedValues`, `findAccruedInPlaceEdits`, `findInputWarnings`) and their ordering, natural-key
matching, blast-radius reporting, and the hand-rolled XML tidy that produces the REST `PATCH`/`POST` body.

Every finding below was reproduced against `target/midgie.jar` (midPoint/prism 4.10.3) and cross-checked
against the prism sources (`prism-impl-4.10.3-SNAPSHOT-sources.jar`).

Format: `SEVERITY | file:line | failure | fix`

## Findings

major | src/main/java/de/is4it/midpoint/diff/DiffEngine.java:580 | `applyMerge`'s "this item cannot merge, leave it as replace" test is `mod.getValuesToReplace() != null`, but prism only produces a whole-value replace for single-valued **properties** — `fixupDelta` is overridden in `PrismPropertyImpl` only (`ItemImpl:684`, `PrismPropertyImpl:398`); `PrismReferenceValueImpl` inherits `representsSameValue == false` (`PrismValueImpl:190`), so a changed single-valued **reference** diffs to `delete old` + `add new`. `applyMerge` then strips the delete and emits an `add`-only delta on a single-valued item, with no merge-fallback note. Reproduced: `diff` of a `ResourceType` whose `connectorRef` changed, with `--multivalue merge`, emits exactly `<modificationType>add</modificationType><path>c:connectorRef</path>` and nothing else; the same run reports the fallback correctly for the sibling `description` property ("`'description'` diffs to a whole-value replace…"). Applying that PATCH throws `SchemaException: Attempt to put more than one value to single-valued item` in `ItemImpl.add` (:394), so the server rejects it — and `deploy --plan`, which advances a local projection via `ObjectDelta.applyTo`, throws the same exception locally. Hits every single-valued ref: `connectorRef`, `resourceRef`, `objectRef`/`ownerRef`, and nested `assignment/N/targetRef` under natural-keys. | Base the fallback on cardinality, not on the delta shape: in the loop, `ItemDefinition<?> def = mod.getDefinition(); if (def != null && def.isSingleValue()) { fallbacks.add(mod.getPath().toString()); continue; }` before the `getValuesToDelete()` branch. Keep the `getValuesToReplace() != null` check for definition-less deltas.

major | src/main/java/de/is4it/midpoint/diff/DiffEngine.java:293 | The accrued-in-place-edit guard asks `multiValue.isMerge(containerPath)` where `containerPath` is only the **outermost** container — the loop at :279-288 `break`s at the *first* id segment — while `applyMerge` (:577) matches the full id-free path. So a per-path merge rule on a *nested* container (`--path a/b/panel=merge`, or a deep `Policy` selector, both explicitly supported) never arms the guard for the edits that happen underneath it, and the accrued value is overwritten with no refusal. Reproduced on a `SystemConfiguration` whose live `adminGuiConfiguration/objectDetails/objectDetailsPage/1` holds a keyless `panel id=10` plus a keyed `panel id=11`, desired declaring one keyless panel: with `--equivalence natural-keys --multivalue merge` the run correctly refuses ("refusing to merge: … at adminGuiConfiguration/objectDetails/objectDetailsPage/1/panel/10/display/label"); with `--equivalence natural-keys --path adminGuiConfiguration/objectDetails/objectDetailsPage/panel=merge` — merge on exactly the container being clobbered — the identical delta is emitted, exit 1, no refusal. The inverse also misfires: a merge rule on the outer container flags deep edits inside inner containers that are on `replace`, where in-place editing is replace's own semantics, giving a spurious exit-2. | Walk every id segment instead of only the first: for each id in `mod.getPath()`, form the id-free prefix ending at that id and test `multiValue.isMerge(prefix)`; flag when **any** level on the path is on merge and `desiredDeclaresValueAt` is false. `idFreePrefix` already computes the first level and can be generalised to yield the list.

minor | src/main/java/de/is4it/midpoint/diff/NaturalKeys.java:56 | `carries` is documented (and relied on by DESIGN.md's "a key only counts when both values actually carry its constituents") as proving the key is discriminating, but `NaturalKeyDefinition.getConstituents` returns only the constituents that are **present** in the value (prism-api `NaturalKeyDefinition:53-66`, `if (item != null) items.add(item)`), and `carries` never compares that against `getNaturalKeyConstituents().size()`. A value carrying *half* a composite key therefore passes. midPoint's most-edited keyed container is exactly composite: `ResourceObjectTypeDefinitionType` is keyed by `kind + intent` (`common-provisioning-3.xsd:589-590`), and omitting `<intent>` in a hand-written `schemaHandling/objectType` fragment is routine. Reproduced: desired `objectType` with `<kind>account</kind>` and no `<intent>`, live holding `account/default` + `entitlement/group` — the "does not carry its natural key (kind + intent), so it cannot be matched to a live value" warning, which is the one that would explain the outcome, never fires, and the fragment silently degrades to a whole-value add/delete. The same leniency makes `keyedContainers` (:113) recommend `natural-keys + merge` via `init --write-policy` for a container whose key cannot discriminate — the bad advice that method's own javadoc says it exists to avoid. | Require every declared constituent: in `carries`, fetch `List<QName> declared = value.getDefinition().getNaturalKeyConstituents()` and return false when `declared == null || declared.isEmpty() || constituents.size() != declared.size()`, keeping the existing per-item `hasAnyValue()` check.

minor | src/main/java/de/is4it/midpoint/diff/DiffEngine.java:486 | The partial-declaration warning — the tool's headline pre-write advisory — mixes two denominators: `desiredContainer.getValues().size()` counts *all* desired values including ones that match nothing live, while `unmatched` counts live values, so the sentence can be arithmetically impossible and reads as if less is at risk than really is. Reproduced verbatim: `'schemaHandling/objectType': desired declares 1 of 2 live value(s), and this path is on multivalue=replace, so the other 2 will be DELETED.` (1 + 2 > 2 — in fact the one desired value matched nothing, so all 2 live values go). An operator reading "1 of 2" before typing `y` reasonably concludes one entry is being kept. | Report the matched count, not the desired count: `int matched = liveContainer.getValues().size() - unmatched;` and phrase it "desired matches `matched` of `live.size()` live value(s) … the other `unmatched` will be DELETED".

minor | src/main/java/de/is4it/midpoint/diff/DiffEngine.java:749 | `MultiValueStrategy.isMerge` ranks identity-scoped `Policy` rules and plain `--path` rules in one specificity contest, but an identity-scoped glob carries 1-2 extra `Type`/`Name` literal segments while a `--path` glob carries none, so `GlobPath.compareSpecificity` (which orders on raw literal count first) compares incommensurable things. A deep CLI `--path` therefore beats the policy file that DESIGN.md says the CLI is merely the "baseline" for: `--path adminGuiConfiguration/objectDetails/objectDetailsPage/panel=replace` (4 literals) silently outranks a policy line `SystemConfiguration/*/**/panel multivalue=merge` (2 literals: `SystemConfiguration`, `panel`), and the central policy is ignored with no diagnostic. Note `Policy.resolveFor` handles every *object-level* knob the other way round (policy layered over baseline, :186-228), so the two knob families disagree. | Resolve in two tiers rather than one: pick the most-specific matching identity-scoped rule first and fall back to the most-specific `--path` rule only when no policy rule matches — or, equivalently, drop the identity prefix segments before calling `compareSpecificity` and break the tie in favour of `identityScoped`.

## Checked and found sound

- **Pass ordering in `diff` (:127-151).** `applyMerge` → `retainItemsPresentIn` → `stripProtectedValues` →
  `findAccruedInPlaceEdits` → `findInputWarnings` is safe in both directions I could construct.
  `applyMerge` running *before* retention cannot leak a stale `mergeFallbackPaths` entry, because a
  fallback is only recorded for `valuesToReplace != null`, which prism emits only for a single-valued
  property present on **both** sides — i.e. one that is by definition in `desired` and survives retention.
  Retention before the accrued check is deliberate and necessary: `retainItemsPresentIn` judges at the
  id-free prefix precisely so the deep id-bearing deltas reach `findAccruedInPlaceEdits` intact.
  Every pass collects into a list and removes afterwards, so none mutate `delta.getModifications()`
  mid-iteration.
- **`removeModification` is path-precise.** It removes by `ItemDelta.equals`, which compares `parentPath`
  with `ItemPath.equivalent` — and `ItemPathComparatorUtil.segmentsEquivalent` *does* compare container ids
  (`Objects.equals(toId(o1), toId(o2))`). So `assignment/1/x` and `assignment/2/x` cannot collide, and no
  pass can collaterally drop a sibling delta.
- **Blast-radius counting.** `removedValueCount` counting only `valuesToDelete` is correct for what prism
  actually emits: an item present in live and absent in desired takes the `other == null` branch of
  `ItemImpl.diffInternal` (:622-631) and yields `valuesToDelete` even for single-valued properties —
  `fixupDelta` runs only in the `other != null` branch. Verified: `--scope full` against a live object with
  `description` + two `subtype` values reports "REMOVES 3 values" and the per-path breakdown. A `replace`
  delta really is an update, and counting it would cry wolf on every `fullName` change.
- **Vacuous natural-key matching.** The `carriesKey` gates in `matchingDesiredValue` (:355-362) and
  `matchingLiveValue` (:526-530) are load-bearing: prism's `DefaultNaturalKeyDefinitionImpl.areNotEquivalent`
  returns "equivalent" when the constituent is absent from both sides, so without those gates an
  `assignment` (declared key `identifier`, never filled in practice) would match everything and disable the
  accrued guard entirely. The gates also happen to prevent the NPE path in
  `NaturalKeyDefinitionImpl.valuesMatch` (`targetValue.getDefinition()` unguarded), since `carries` fails
  first — and `carries` wraps its own `RuntimeException`. `NaturalKeys.names` is unwrapped but is only ever
  reached after `of()` returned non-null, so `DummyPrismObjectDefinition`'s
  `UnsupportedOperationException` cannot escape.
- **Single-valued containers under `--scope present`.** The claim in `retainItemsPresentIn`'s javadoc — that
  two fragments can each fill a different child of one single-valued container — holds:
  `PrismContainerValueImpl.representsSameValue` (:1017-1025) short-circuits to `true` for any single-valued
  container in `lax` mode, so prism recurses into child deltas rather than emitting a whole-value
  add/delete, and the id-free prefix then names the exact child.
- **`XmlPretty` namespace stripping.** I could not construct an input where a genuinely *used* prefix is
  dropped. The `scan.contains(prefix + ":")` test only ever over-approximates (`xmlns:cc` keeps `xmlns:c`
  alive; any descendant `xmlns:` declaration keeps a prefix `s` alive), and every legal use of a prefix —
  element name, attribute name, `xsi:type` value, `<t:path>`/QName content, `relation="org:default"` — is
  literally `prefix:` in the text. `scan.replace(attr, " ")` only blanks *declarations*, never uses, and
  only the root tag is ever rewritten, so a descendant's own declaration is untouched. `openingTagEnd` is
  quote-aware (single quotes inside double-quoted values and vice versa are handled), `splitAttributes`
  always advances, and the `xmlns:p` with no `=` case throws into the documented catch-all. Verified
  end-to-end on a real `objectModification`: `xmlns:apti`, `c`, `t`, `xsi` all kept, unused ones dropped.
- **`DeltaOutput`.** `render`'s `toLowerCase()` is locale-sensitive in principle but none of
  `xml`/`json`/`summary` contains a dotted-I, so the Turkish-locale trap does not apply. `toSummary` /
  `removalWarning` render the *final* mutated delta (they are called through `DiffResult`, after all
  post-passes), so the reported blast radius matches the payload.

## Not reported (deliberate per DESIGN.md)

`live.diff(desired)` direction; `REAL_VALUE` default; `present`/`replace`/`real-value` defaults; pure
midPoint source XML; the REST-free core; dropping protected values (including the documented gap where a
`ProtectedStringType` nested inside a larger added/deleted container is not walked — `stripProtectedValues`
javadoc and DESIGN.md "Safety guards" both call it out); `XmlPretty` returning its input on any parsing
surprise; `--quiet` dropping advisories.
