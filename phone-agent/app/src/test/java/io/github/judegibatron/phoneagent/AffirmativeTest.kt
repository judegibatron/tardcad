package io.github.judegibatron.phoneagent

import io.github.judegibatron.phoneagent.session.SessionController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AffirmativeTest {

    @Test
    fun `clear yes answers confirm`() {
        listOf("yes", "Yes.", "yeah go ahead", "sure", "okay send it", "yep", "confirm", "do it").forEach {
            assertTrue("expected yes for '$it'", SessionController.isAffirmative(it))
        }
    }

    @Test
    fun `no and hedged answers do not confirm`() {
        listOf("no", "No, don't", "cancel", "yes wait no", "never mind", "stop", "what?", "").forEach {
            assertFalse("expected no for '$it'", SessionController.isAffirmative(it))
        }
    }
}
