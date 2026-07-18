/*
 * Copyright (c) 2026 IS4IT
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package de.is4it.midpoint.diff;

import com.evolveum.midpoint.prism.Objectable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.schema.MidPointPrismContextFactory;

import org.junit.jupiter.api.Test;

import static de.is4it.midpoint.diff.ApplyCommand.Confirmation.ABORTED;
import static de.is4it.midpoint.diff.ApplyCommand.Confirmation.NON_INTERACTIVE;
import static de.is4it.midpoint.diff.ApplyCommand.Confirmation.PROCEED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline tests for the {@code apply} safety logic: the confirmation decision and the create-body strip. */
class ApplyCommandTest {

    @Test
    void yesFlagAlwaysProceeds() {
        assertEquals(PROCEED, ApplyCommand.decideConfirmation(true, false, null));
        assertEquals(PROCEED, ApplyCommand.decideConfirmation(true, true, "n"), "--yes overrides a 'no' too");
    }

    @Test
    void nonInteractiveWithoutYesRefuses() {
        assertEquals(NON_INTERACTIVE, ApplyCommand.decideConfirmation(false, false, null));
        // no console => the answer is irrelevant; still refuses without --yes
        assertEquals(NON_INTERACTIVE, ApplyCommand.decideConfirmation(false, false, "y"));
    }

    @Test
    void interactivePromptHonoursTheAnswer() {
        assertEquals(PROCEED, ApplyCommand.decideConfirmation(false, true, "y"));
        assertEquals(PROCEED, ApplyCommand.decideConfirmation(false, true, "yes"));
        assertEquals(PROCEED, ApplyCommand.decideConfirmation(false, true, "  Y "), "trimmed + case-insensitive");
        assertEquals(ABORTED, ApplyCommand.decideConfirmation(false, true, "n"));
        assertEquals(ABORTED, ApplyCommand.decideConfirmation(false, true, ""), "empty answer (default) aborts");
        assertEquals(ABORTED, ApplyCommand.decideConfirmation(false, true, null));
    }

    @Test
    void createBodyStripsOperationalMetadata() throws Exception {
        PrismContext prismContext = new MidPointPrismContextFactory().createInitializedPrismContext();
        PrismObject<? extends Objectable> user = prismContext.parseObject(userWithMetadata());

        String body = ApplyCommand.createBody(prismContext, user);

        assertFalse(body.contains("createChannel"),
                "operational metadata must be stripped from the create body:\n" + body);
        assertTrue(body.contains("Keep Me"), "real content (fullName) must survive:\n" + body);
    }

    private static String userWithMetadata() {
        return "<user xmlns=\"http://midpoint.evolveum.com/xml/ns/public/common/common-3\""
                + " oid=\"cafe0000-0000-0000-0000-000000000001\">"
                + "<name>zz-strip</name>"
                + "<fullName>Keep Me</fullName>"
                + "<metadata><createChannel>http://midpoint.evolveum.com/channel#init</createChannel></metadata>"
                + "</user>";
    }
}
