/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.ArrayList;
import java.util.List;

import javax.xml.namespace.QName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.ItemDefinition;
import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContainer;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismValue;
import com.evolveum.midpoint.prism.equivalence.EquivalenceStrategy;
import com.evolveum.midpoint.prism.path.ItemName;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.util.exception.SchemaException;

/**
 * Combines every {@link Fragment} of one object into a single desired object, so {@code deploy} reconciles
 * the object rather than each file that describes it.
 *
 * <p><b>Why this exists.</b> Reconciling file by file cannot compose a multi-valued item: two files that each
 * contribute a {@code panel} to one {@code SystemConfiguration} produce two PATCHes, the second one replacing
 * what the first wrote. The workaround was {@code multivalue=merge}, which stops the clobbering but pays for
 * it — under merge a value you <em>delete</em> from a file yields an empty delta, so the deletion is neither
 * applied nor reported as drift. Combining first removes the need for the trade: the desired object holds
 * both panels, plain {@code replace} writes both, and a deleted entry is still a deletion.
 *
 * <p><b>How values are paired.</b> Entries of a multi-valued container are matched by container id, else by
 * the schema's {@code a:naturalKey} ({@link NaturalKeys#match}) — the same rule the diff uses to decide
 * whether desired declares a value. That is what makes the union correct rather than naive: two files that
 * each declare {@code objectDetailsPage[type=UserType]} must combine into <em>one</em> value holding both
 * their panels, not two competing values. Where neither an id nor a filled key is available the values are
 * appended, de-duplicated by real value — so the same entry written twice collapses, while two entries that
 * merely resemble each other are kept apart, since nothing here can tell that they were meant to be one.
 *
 * <p>Note where this puts natural-key matching: <b>file against file</b>, both sides written by the operator,
 * with the outcome visible in {@code --plan} before anything is sent. Nothing accrued is at risk here, unlike
 * the same matching applied against a live object.
 *
 * <p>Operational items (metadata and friends) are never merged in — the server owns them, and a fragment
 * exported from a live object carries a set that means nothing on any other fragment.
 */
final class FragmentUnion {

    private static final Logger LOG = LoggerFactory.getLogger(FragmentUnion.class);

    private FragmentUnion() {
    }

    /**
     * The union of {@code fragments} — all of which must describe the same object.
     *
     * <p>A single fragment is returned as it stands: there is nothing to combine, and cloning it would only
     * cost a copy of every object in the tree for the layout most trees actually use.
     *
     * @throws SchemaException on a contradiction the grouping check could not see — a single-valued item
     *         given different values by two fragments <em>inside</em> a multi-valued container value
     */
    static PrismObject<? extends Objectable> of(List<Fragment> fragments) throws SchemaException {
        Fragment first = fragments.get(0);
        if (fragments.size() == 1) {
            return first.object();
        }
        PrismObject<? extends Objectable> union = first.object().clone();
        for (int i = 1; i < fragments.size(); i++) {
            Fragment fragment = fragments.get(i);
            // The fragments already folded in are what a contradiction has to be reported against: the value
            // being clashed with sits in the union, which by then belongs to no single file.
            mergeInto(union.getValue(), fragment.object().getValue(), ItemPath.EMPTY_PATH,
                    new Merge(fragment, fragments.subList(0, i)));
        }
        LOG.debug("united {} fragment(s) into one {} object", fragments.size(), first.typeName());
        return union;
    }

    /**
     * The fragment currently being merged in, and the ones already merged — enough to name <em>both</em>
     * sides of a contradiction rather than only the incoming one.
     */
    private record Merge(Fragment source, List<Fragment> earlier) {
    }

    private static void mergeInto(PrismContainerValue<?> target, PrismContainerValue<?> source,
            ItemPath prefix, Merge origin) throws SchemaException {
        for (Item<?, ?> sourceItem : source.getItems()) {
            ItemDefinition<?> definition = sourceItem.getDefinition();
            if (definition != null && definition.isOperational()) {
                continue;
            }
            ItemPath path = prefix.append(sourceItem.getElementName());
            Item<?, ?> targetItem = target.findItem(sourceItem.getElementName());
            if (targetItem == null) {
                addItem(target, sourceItem);
            } else if (sourceItem instanceof PrismContainer<?> sourceContainer
                    && targetItem instanceof PrismContainer<?> targetContainer) {
                mergeContainer(targetContainer, sourceContainer, path, origin);
            } else {
                mergeValues(targetItem, sourceItem, path, origin);
            }
        }
    }

    /**
     * Merge a container the target already declares: pair each source value with the target value describing
     * the same entry and recurse into it, or add it as a new entry.
     */
    private static void mergeContainer(PrismContainer<?> target, PrismContainer<?> source, ItemPath path,
            Merge origin) throws SchemaException {
        ItemDefinition<?> definition = target.getDefinition();
        boolean multiValue = definition == null || definition.isMultiValue();
        for (PrismContainerValue<?> sourceValue : source.getValues()) {
            // A single-valued container holds at most one value, so "the same entry" is simply the one
            // that is there — that is how two files each filling in a different part of, say,
            // deploymentInformation end up with both parts.
            PrismContainerValue<?> match = multiValue
                    ? NaturalKeys.match(target, sourceValue, sourceValue.getId())
                    : soleValue(target);
            if (match != null) {
                mergeInto(match, sourceValue, path, origin);
            } else {
                addValue(target, sourceValue, path);
            }
        }
    }

    /** Merge a property or reference: a single-valued one must agree, a multi-valued one takes the union. */
    private static void mergeValues(Item<?, ?> target, Item<?, ?> source, ItemPath path, Merge origin)
            throws SchemaException {
        ItemDefinition<?> definition = target.getDefinition();
        boolean singleValue = definition != null && definition.isSingleValue();
        for (PrismValue sourceValue : source.getValues()) {
            if (!singleValue) {
                addValue(target, sourceValue, path);
                continue;
            }
            PrismValue held = target.getValues().isEmpty() ? null : target.getValues().get(0);
            if (held == null) {
                addValue(target, sourceValue, path);
                continue;
            }
            if (!held.equals(sourceValue, EquivalenceStrategy.REAL_VALUE)) {
                // The grouping check catches contradictions it can reach, but it descends only through
                // single-valued containers; a single-valued item inside a multi-valued container value
                // (an assignment's description, say) is only comparable once the two entries have been
                // paired, which is here. Failing is the only honest answer: picking one of the two would
                // make the deployed object depend on the order the files happened to be read in.
                throw new SchemaException(contradiction(path, held, sourceValue, origin));
            }
        }
    }

    private static PrismContainerValue<?> soleValue(PrismContainer<?> container) {
        return container.getValues().isEmpty() ? null : container.getValues().get(0);
    }

    /**
     * The message for two fragments that disagree about a single-valued item. Names <b>both</b> files — the
     * incoming one is known outright, the other is found by asking the already-merged fragments which of
     * them declares the value being clashed with.
     *
     * <p>Long values are reported as a diff rather than twice in full. A {@code description} or an
     * expression runs to dozens of lines, and printing both leaves the reader comparing two near-identical
     * blocks by eye to find the line that actually differs.
     */
    private static String contradiction(ItemPath path, PrismValue held, PrismValue incoming, Merge origin) {
        return "Contradiction at '" + path + "': " + TextDiff.clash(
                describe(sourcesDeclaring(origin.earlier(), path, held)), render(held),
                String.valueOf(origin.source().sourceFile()), render(incoming));
    }

    /**
     * Which of the already-merged fragments declare {@code value} at {@code path}. Normally one; more than
     * one when several fragments agreed on the value the incoming one contradicts, which is worth saying
     * since it tells the operator which side is the odd one out.
     */
    private static List<String> sourcesDeclaring(List<Fragment> earlier, ItemPath path, PrismValue value) {
        List<String> sources = new ArrayList<>();
        for (Fragment fragment : earlier) {
            List<PrismValue> declared = new ArrayList<>();
            collectValuesAt(fragment.object().getValue(), names(path), 0, declared);
            for (PrismValue candidate : declared) {
                if (candidate.equals(value, EquivalenceStrategy.REAL_VALUE)) {
                    sources.add(String.valueOf(fragment.sourceFile()));
                    break;
                }
            }
        }
        return sources;
    }

    /**
     * Every value at an id-free {@code path}, walking through <em>all</em> values of a multi-valued container
     * on the way. {@code findItem} cannot be used: the path carries no container ids, so a step through a
     * multi-valued container has no single answer, and the point here is to find the value wherever it sits.
     */
    private static void collectValuesAt(PrismContainerValue<?> container, List<QName> names, int index,
            List<PrismValue> found) {
        if (container == null || index >= names.size()) {
            return;
        }
        Item<?, ?> item = container.findItem(ItemName.fromQName(names.get(index)));
        if (item == null) {
            return;
        }
        if (index == names.size() - 1) {
            found.addAll(item.getValues());
        } else if (item instanceof PrismContainer<?> nested) {
            for (PrismContainerValue<?> value : nested.getValues()) {
                collectValuesAt(value, names, index + 1, found);
            }
        }
    }

    private static List<QName> names(ItemPath path) {
        List<QName> names = new ArrayList<>();
        for (Object segment : path.getSegments()) {
            if (ItemPath.isName(segment)) {
                names.add(ItemPath.toName(segment));
            }
        }
        return names;
    }

    /** The files as one phrase, falling back to the old wording when the value cannot be traced to a file. */
    private static String describe(List<String> sources) {
        if (sources.isEmpty()) {
            return "another fragment of the same object";
        }
        return String.join(" and ", sources);
    }

    private static String render(PrismValue value) {
        Object real = value.getRealValue();
        return String.valueOf(real != null ? real : value);
    }

    /** Add a whole item the target does not declare at all. */
    @SuppressWarnings({"unchecked", "rawtypes"}) // the item carries its own value/definition types
    private static void addItem(PrismContainerValue<?> target, Item<?, ?> item) throws SchemaException {
        ((PrismContainerValue) target).add(item.clone());
    }

    /**
     * Add a value, de-duplicating by real value: the same entry written into two files is one entry, not two.
     * Cloned, so the union never shares mutable state with the fragment it came from.
     */
    @SuppressWarnings({"unchecked", "rawtypes"}) // the value's type is the item's by construction
    private static void addValue(Item<?, ?> target, PrismValue value, ItemPath path) throws SchemaException {
        if (!((Item) target).add(value.clone(), EquivalenceStrategy.REAL_VALUE)) {
            LOG.debug("union: '{}' already holds an equal value; not added twice", path);
        }
    }
}
