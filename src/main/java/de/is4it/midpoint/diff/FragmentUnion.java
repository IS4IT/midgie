/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import java.util.List;

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
        for (Fragment fragment : fragments.subList(1, fragments.size())) {
            mergeInto(union.getValue(), fragment.object().getValue(), ItemPath.EMPTY_PATH, fragment);
        }
        LOG.debug("united {} fragment(s) into one {} object", fragments.size(), first.typeName());
        return union;
    }

    private static void mergeInto(PrismContainerValue<?> target, PrismContainerValue<?> source,
            ItemPath prefix, Fragment origin) throws SchemaException {
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
            Fragment origin) throws SchemaException {
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
    private static void mergeValues(Item<?, ?> target, Item<?, ?> source, ItemPath path, Fragment origin)
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
                throw new SchemaException("Contradiction at '" + path + "': " + origin.sourceFile()
                        + " sets '" + render(sourceValue) + "', another fragment of the same object sets '"
                        + render(held) + "'.");
            }
        }
    }

    private static PrismContainerValue<?> soleValue(PrismContainer<?> container) {
        return container.getValues().isEmpty() ? null : container.getValues().get(0);
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
