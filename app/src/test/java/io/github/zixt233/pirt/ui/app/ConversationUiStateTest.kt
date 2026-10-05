package io.github.zixt233.pirt.ui.app

import io.github.zixt233.pirt.model.OcSession
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationUiStateTest {
    @Test
    fun retainsPendingSelectionWhileOcSessionIsNotYetPersisted() {
        val draft = OcSession(runtimeKey = "draft:one", name = "")
        val state = ConversationUiState(draft)
        val pending = PendingConversation(
            session = draft.copy(firstMessage = "hello"),
            ocId = "oc-session-id",
        )

        state.selectedSessionId.value = draft.runtimeKey
        state.pendingConversations += pending

        assertEquals("draft:one", state.selectedSessionId.value)
        assertEquals(pending, state.pendingConversations.single())
    }
}
