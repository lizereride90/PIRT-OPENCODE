package io.github.zixt233.pirt.model

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceModelsTest {
    @Test
    fun emptySessionTitleRemainsPresentationNeutral() {
        val session = OcSession(runtimeKey = "draft", name = "")

        assertEquals("", session.displayName)
    }

    @Test
    fun firstMessageIsUsedWhenSessionHasNoTitle() {
        val session = OcSession(runtimeKey = "session", name = "", firstMessage = "Build an app")

        assertEquals("Build an app", session.displayName)
    }
}
