package ai.closepaw.agent

import ai.closepaw.agent.cognition.context.NavigationState
import ai.closepaw.agent.cognition.policy.ToolArbitrationResult
import ai.closepaw.agent.cognition.policy.TurnToolPolicy
import ai.closepaw.tool.ToolCallResult
import ai.closepaw.tool.ToolName
import ai.closepaw.session.UnansweredVoiceQuestion

/** Reason why the agent stopped. */
sealed class AgentStopReason {
    data class GoalAchieved(val message: String = "Goal achieved") : AgentStopReason()
    data object UserRequested : AgentStopReason()
    data class TaskImpossible(val message: String) : AgentStopReason()
    data class Error(val message: String) : AgentStopReason()
}

/** Outcome of a single turn. */
sealed class TurnOutcome {
    data object Continue : TurnOutcome()
    data class Complete(val message: String, val success: Boolean = true) : TurnOutcome()
    data class Error(val message: String, val recoverable: Boolean) : TurnOutcome()
    data object Cancelled : TurnOutcome()
}

/**
 * Mutable runtime state carried across turns.
 *
 * `navigationState` powers loop detection (stable-screen warning).
 */
internal data class TurnRunnerState(
    val navigationState: NavigationState = NavigationState(),
    val screenshotRequested: Boolean = false,
)

/**
 * Full output of one `AgentTurnRunner.executeTurn()` call:
 * - `outcome`: control decision for the outer Agent loop
 * - `nextState`: state to feed into the next turn
 */
internal data class TurnExecutionResult(
    val outcome: TurnOutcome,
    val nextState: TurnRunnerState
)

/**
 * Outcome of executing the selected tool calls for a turn.
 *
 * Tracks which tools actually reached a terminal state (success/failure/cancelled)
 * so callers can distinguish "planned but not executed" from "executed and succeeded".
 */
internal data class ExecutionPhaseResult(
    val executedToolIds: Set<String>,
    val terminatedEarly: Boolean,
    val lastTerminalResult: ToolCallResult?
) {
    companion object {
        val EMPTY = ExecutionPhaseResult(
            executedToolIds = emptySet(),
            terminatedEarly = false,
            lastTerminalResult = null
        )
    }
}

/**
 * Maps the planning + execution results to the control-loop outcome.
 *
 * A failed tool ends its batch and returns control to the planner with the diagnostic.
 * Explicit stops, unanswered voice requests and approvals belonging to the parent end the run.
 */
internal fun decideTurnOutcome(
    policy: TurnToolPolicy,
    turnResult: TurnResult,
    arbitration: ToolArbitrationResult,
    execution: ExecutionPhaseResult
): TurnOutcome {
    if (execution.terminatedEarly) {
        return when (val last = execution.lastTerminalResult) {
            is ToolCallResult.Cancelled -> if (last.replan) TurnOutcome.Continue else TurnOutcome.Cancelled
            // The failed call and its diagnostic are already in model history. Replan from them.
            is ToolCallResult.Error -> if (last.exception is UnansweredVoiceQuestion || last.exception is ParentApprovalRequired) {
                TurnOutcome.Complete(last.exception.message ?: last.error, success = false)
            } else TurnOutcome.Continue
            else -> TurnOutcome.Error(
                message = "Tool execution aborted before completion",
                recoverable = true
            )
        }
    }
    val completeTaskCall = arbitration.selectedToolCalls.find { it.name == ToolName.CompleteTask.raw }
    if (completeTaskCall != null && completeTaskCall.id !in execution.executedToolIds) {
        return TurnOutcome.Error(
            message = "complete_task was planned but did not execute",
            recoverable = true
        )
    }
    val decision = policy.decideCompletion(turnResult, arbitration)
    if (!decision.shouldComplete) return TurnOutcome.Continue
    return TurnOutcome.Complete(
        message = decision.summary ?: "Goal achieved",
        success = decision.success
    )
}

internal class ParentApprovalRequired(description: String, packageName: String) :
    Exception("Run this action in the parent task to request user approval: $description ($packageName).")
