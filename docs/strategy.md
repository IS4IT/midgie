# Diff strategy

The flags themselves are listed under [Diff strategy](reference.md#diff-strategy) in the reference. This page is about choosing them.

## Choosing a strategy

**Equivalence needs no choosing.** The default `--equivalence auto` decides per multi-valued container: where the schema declares a natural key and every value in your file fills it in, entries are matched by that key and updated **in place**, keeping their container ids and the metadata hanging off them; everywhere else it falls back to `real-value`, which is what midgie did before `auto` existed. The one thing you can do to get more out of it is fill in the keys — a `<panel>` with its `<identifier>`, a `schemaHandling/objectType` with both its `<kind>` and `<intent>` — and midgie's input warnings tell you when a value is missing one. A key left empty *inside* an entry costs less: with one `<addFocus/>` without its `<name>` in an object type's synchronization reaction, the object types are still paired by `kind` + `intent` and edited in place, and only the `addFocus` actions are compared by content — a change to one replaces that action whole. Run with `-v` to see which lists were compared that way and the key that decided it: `note: ResourceType/HR: schemaHandling/objectType/synchronization/reaction/actions/addFocus does not fill in its key (name), so its entries are compared as whole values: … schemaHandling/objectType itself is still paired by its key`.

**Container ids from another server are recognised.** A list entry that carries an `id` is paired with the server's entry of the same id, whatever the strategy — right for a file exported from the server you deploy to, and wrong for one exported elsewhere, where the same number can name an entry someone added on this server. Before diffing, midgie checks each list's ids against the server's: a matching id whose filled-in natural key differs, an entry the server already holds under another id, or a server entry another file entry declares, all mean the numbering came from somewhere else, and that list's entries are then paired by key or content instead (`-v` says so per list). midPoint never reuses a container id, so none of these happens with a file exported from the same server. One case cannot be told apart: a single edited entry whose foreign id happens to be taken here, with no other entry in the list to show the mismatch. Exporting without ids, or filling in natural keys, avoids it.

Setting `--equivalence natural-keys` by hand is almost always worse than leaving it on `auto`: prism takes one equivalence for a whole-object diff, so it applies to *every* container of that object — including the many types that declare a key real config never fills in (an `assignment` rarely carries an `<identifier>`), where prism then pairs unrelated entries positionally and edits one into the other. `auto` avoids that by diffing the object under both strategies and keeping each change from the pass its path belongs to. The other strategies are for inspection, not reconciliation: `data` and `literal` compare operational data and metadata, so against a live server they never report "no differences" at all.

**Multi-value handling mostly needs no choosing either.** The default `--multivalue auto` depends on whether the tree is **tracked** (in a full git clone, on a server with `init --create-state`):

- **Tracked: `merge`.** Values a list holds on the server beyond your files — entries midPoint ships, an admin added, another tree owns — stay. What *your* tree dropped is still removed, because the three-way comparison against the last deployed revision finds it and each object's PATCH carries the delete; an entry edited without a natural key is added and its old form deleted in that same request. So is an entry whose key is filled in but whose values were compared by content: a live value that carries the container id or natural key of an entry your file declares is that entry's old form, not someone else's, and merge deletes it rather than keep it beside the new one. So merge's two weaknesses on its own — it can express neither a removal nor such an edit — do not apply. What merge keeps is named per object (`keeps live value(s) the tree does not list: inducement ×1 (multivalue=merge; …)`), so an entry someone added to a list the tree means to own does not go unseen; a `<path>  multivalue=replace` policy rule makes the tree own that list.
- **Untracked: `replace`.** Without the history, merge could express neither a removal nor an edit (it would keep `[alpha, beta]` for live `[alpha]` and desired `[beta]`), so the tree is authoritative for every list it declares, and deploy says so in a note. A `multivalue=merge` rule, or `init --write-policy`'s suggestion, keeps a list open where the server holds more.

**Inside a value the tree declares, a tracked tree owns only what it deployed.** Merge and replace decide which values of a list survive; they say nothing about what is *inside* a matched value — one `<mapping>` of an object template paired by its `name`, or a single-valued container such as `archetypePolicy/display`. A two-way diff deletes whatever the server's copy holds there beyond your file, whoever put it there: an admin who set `lifecycleState` on ten mappings would see ten deletes. On a tracked tree with `--scope present` (the default) midgie compares three ways instead, for every item below the top level, at any depth:

| your file now | last deployed | server | result |
|---|---|---|---|
| value *v* | anything | anything | set to *v* (replaced or added) |
| absent | absent | *w* | **kept**, and named in the "keeps live value(s)" note |
| absent | *v* | *v* | removed — your tree dropped it |
| absent | *v* | *w* ≠ *v* | **kept**, with a note that it was changed on the server after the deploy |

That is the rule list values already follow — only remove what midgie deployed, in the form it deployed it — carried to every depth. The first deploy of a tracked tree has nothing to compare against and removes nothing below the top level. An untracked tree, an offline `diff`, and `--scope full` keep the two-way behaviour: the file is authoritative for everything inside the values it declares.

`--multivalue merge|replace` on the command line (or in the policy, per object or path) overrides either. `--no-prune` withholds the removals but leaves the default alone: flipping the run to replace because of it would delete more, not less. Note that *your own* tree splitting a list across several files needs neither: `deploy` combines the files describing one object before diffing. `diff` reads the same history on a live run, so it shows the same change deploy would send; an offline `diff` of two files has none, and uses replace.

## Syncing an item nested inside a multi-valued container

To manage **one entry** deep inside multi-valued containers — say a single GUI panel at
`adminGuiConfiguration/objectDetails/objectDetailsPage[type=UserType]/panel[identifier=my-panel]` — combine
`multivalue=merge` with the natural-key matching `--equivalence auto` already gives you:

```
# midgie.policy
SystemConfigurationType/SystemConfiguration/**/panel  multivalue=merge
```

Earlier versions needed an `equivalence=natural-keys` line here as well. They no longer do, and adding one
is a step backwards — it would widen key matching to every container of that object, `assignment` included.
What the recipe does still require is that your file fills the keys in: `<type>` on the `objectDetailsPage`
and `<identifier>` on the `panel`, or `auto` will decline to match them and warn that it did.

Scoped to the container that needs it rather than to the whole object — the same shape
`init --write-policy` generates. On a tracked tree merge is already the default and the line is not needed.

The midPoint schema declares an `a:naturalKey` for these containers (`panel` → `identifier`,
`objectDetailsPage` → `type`, and ~100 more), so prism matches your id-less fragment against the live values
by key and emits a single deep delta — e.g. `replace …/objectDetailsPage[84]/panel[284]/displayOrder` —
leaving sibling entries untouched — and, on a tracked tree, the items inside the panel that your file does not mention (see [Choosing a strategy](#choosing-a-strategy)). Your fragment only needs the key items plus what
you manage:

```xml
<systemConfiguration>
  <name>SystemConfiguration</name>
  <adminGuiConfiguration><objectDetails><objectDetailsPage>
    <type>c:UserType</type>
    <panel>
      <identifier>my-panel</identifier>
      <displayOrder>16</displayOrder>
    </panel>
  </objectDetailsPage></objectDetails></adminGuiConfiguration>
</systemConfiguration>
```

`merge` is what keeps the siblings: without it, replace semantics treat the containers you declare as
authoritative and delete every entry you did not list. midgie warns when that is about to happen — see
below.

## Guard rails

A partial fragment can destroy live state, so midgie reports the danger before anything is written. These are **advisories, not blocks** — the run proceeds; use `--dry-run` to inspect first, and `--quiet` to silence them.

- **Blast radius.** Every summary line states what it removes (`delete …/objectDetailsPage (removes 2 values)`), and a removing delta prints `WARNING: this change REMOVES n values from the live object` with the paths. A removed *container* value takes everything nested in it, so this is the number that matters.
- **A value missing its natural key.** `'…/panel' value #1 does not carry its natural key (identifier), so it cannot be matched to a live value` — it would be applied as a whole-value change instead of updating the matching entry.
- **A partially declared container.** `'…/panel': desired matches 1 of 4 live value(s), and this path is on multivalue=replace, so the other 3 will be DELETED. Set '…/panel multivalue=merge' to update just the entries you list.` When the key is not filled in on every value it adds that, since without it the entries can only be matched by content.

These stay quiet unless they mean something: only containers with a schema-declared natural key are checked, the key must actually be *in use* by the live values (plenty of types declare one that real config never fills in — an `assignment` or `inducement` rarely carries an `<identifier>`), and a live value that desired restates verbatim counts as declared. The key-matched merge recipe above produces no warnings at all.

**One check does block: a natural key held twice.** A change that would leave a keyed list with more values sharing one filled-in key than the server holds now is refused for that object (exit 2), in `deploy`, a live `diff` and an offline one alike: `refusing a change that would leave schemaHandling/objectType[kind=GENERIC, intent=task] ×2 — values sharing one natural key, which is meant to name a single entry …`. The key names one entry, so two values carrying it mean the tree declares the entry twice, or an entry was added beside its live copy instead of replacing it — and midPoint rejects some of these outright: a resource with two object types of one kind and intent no longer loads its schema. A duplicate the server already holds does not block a change; it is only never added to.
