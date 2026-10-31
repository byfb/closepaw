package ai.closepaw.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CustomInstructionsTest {
    @Test fun `custom instructions retain role prompt and clear back to it`() {
        assertThat(CustomInstructions.appendTo("Role and tools", "Read before acting"))
            .isEqualTo("Role and tools\n\nUser custom instructions:\nRead before acting")
        assertThat(CustomInstructions.appendTo("Role and tools", "  ")).isEqualTo("Role and tools")
    }
}
