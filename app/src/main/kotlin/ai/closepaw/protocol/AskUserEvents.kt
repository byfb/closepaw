package ai.closepaw.protocol

/** Agent is asking the user for help. */
data class AskUser(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val type: AskUserType,
        val message: String,
        val callId: String
) : AskUserDomainEvent

/** A matching request accepted its reply. The answer stays in the tool result. */
data class UserResponded(
        override val sessionId: SessionId,
        override val timestamp: Long,
        val callId: String,
) : AskUserDomainEvent
