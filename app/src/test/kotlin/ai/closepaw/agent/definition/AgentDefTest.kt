package ai.closepaw.agent.definition

import com.google.common.truth.Truth.assertThat
import ai.closepaw.agent.AgentExecutionRole
import org.junit.Test

class AgentDefTest {

    @Test
    fun `default definition has expected role and tools`() {
        assertThat(DefaultRoleDef.executionRole).isEqualTo(AgentExecutionRole.MAIN)
        assertThat(DefaultRoleDef.allowedTools)
            .containsExactly(
                "mobile_action",
                "system_button",
                "wait",
                "open_app",
                "search_apps",
                "get_current_time",
                "request_screenshot",
                "scratchpad",
                "shell",
                "write_todos",
                "complete_task",
                "ask_user",
                "remember_experience",
                "activate_skill",
                "delegate_task",
                "browser_script"
            )
        assertThat(DefaultRoleDef.delegatable).isTrue()
        assertThat(DefaultRoleDef.systemPrompt).contains("standalone Android automation agent")
        assertThat(DefaultRoleDef.systemPrompt).contains("Delegation")
    }
}
