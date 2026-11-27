package ai.closepaw.tool

import android.util.Log
import ai.closepaw.model.ScreenSnapshot
import ai.closepaw.platform.AndroidPlatform
import ai.closepaw.tool.impl.OpenAppInvocation
import ai.closepaw.protocol.ApprovalDecision
import ai.closepaw.protocol.ApprovalDetails
import ai.closepaw.protocol.AppTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ToolRouter - Executes tool calls with state machine and policy-based approval.
 * 
 * Implements the tool call lifecycle:
 * 1. VALIDATING - Validate tool exists and parameters are correct
 * 2. POLICY CHECK - Ask PolicyEngine if allowed/denied/ask-user
 * 3. AWAITING_APPROVAL - (if needed) Wait for user decision
 * 4. EXECUTING - Run the tool
 * 5. SUCCESS/ERROR/CANCELLED - Terminal state
 * 
 * Pattern from Gemini CLI's CoreToolScheduler.
 */
class ToolRouter(
    private val registry: ToolRegistry,
    private val policyEngine: PolicyEngine
) {
    
    companion object {
        private const val TAG = "ToolRouter"
        
        /** Timeout for user approval - if not responded within this time, action is cancelled */
        internal const val APPROVAL_TIMEOUT_MS = 60_000L  // 60 seconds
    }
    
    // Track active tool calls for cancellation and state queries
    private val activeToolCalls = ConcurrentHashMap<String, ToolCallState>()

    // Per-call cancellation tokens — signalled by cancel()/cancelAll()
    private val cancellationTokens = ConcurrentHashMap<String, CancellationToken>()

    // Pending approval handlers (call ID -> deferred decision)
    private val pendingApprovals = ConcurrentHashMap<String, CompletableDeferred<ApprovalDecision>>()
    
    /**
     * Execute a tool call with full state machine lifecycle.
     * 
     * @param toolName Name of the tool to invoke
     * @param params Parameters for the tool
     * @param context Execution context with platform access
     * @param callId Optional caller-provided ID (use LLM call_id when available)
     * @param onStateChange Callback for state changes (for UI updates)
     * @param onApprovalRequired Suspend callback when user approval is needed.
     *        The callback receives ApprovalDetails which includes the callId
     *        that must be used when resolving the approval.
     * @return The final result of the tool call
     */
    suspend fun execute(
        toolName: String,
        params: JSONObject,
        context: ToolRouterContext,
        packageName: String? = null,
        callId: String? = null,
        onStateChange: ((ToolCallState) -> Unit)? = null,
        awaitApproval: (suspend (ApprovalDetails, Deferred<ApprovalDecision>) -> ApprovalDecision)? = null,
        onApprovalRequired: (suspend (ApprovalDetails) -> Unit)? = null
    ): ToolCallResult {
        val resolvedCallId = callId ?: generateCallId()
        val token = CancellationToken()
        cancellationTokens[resolvedCallId] = token

        Log.d(TAG, "Starting tool call: $resolvedCallId ($toolName)")
        
        // === STATE: VALIDATING ===
        var state: ToolCallState = ToolCallState.Validating(resolvedCallId, toolName, params)
        updateState(state, onStateChange)
        
        fun failPreparation(message: String, cause: Exception? = null): ToolCallResult.Error {
            updateState(ToolCallState.Error(resolvedCallId, toolName, params, message, cause), onStateChange)
            cleanupCall(resolvedCallId)
            return ToolCallResult.Error(resolvedCallId, message, cause)
        }

        val tool = registry.get(toolName) ?: return failPreparation("Unknown tool: $toolName")
        val invocation = try {
            val validation = tool.validate(params)
            if (validation is ValidationResult.Invalid)
                return failPreparation("Validation failed: ${validation.errors.joinToString(", ")}")
            tool.createInvocation(params)
        } catch (e: CancellationException) {
            cleanupCall(resolvedCallId)
            throw e
        } catch (e: Exception) {
            return failPreparation("Could not prepare $toolName: ${e.message ?: e.javaClass.simpleName}", e)
        }
        
        // Track if approval was required (for snapshot refresh after approval wait)
        var approvalWasRequired = false
        
        // === POLICY CHECK ===
        val appInvocation = invocation as? OpenAppInvocation
        if (appInvocation != null) {
            val diagnostic = try {
                appInvocation.resolve(context.platform)
            } catch (e: kotlinx.coroutines.CancellationException) {
                cleanupCall(resolvedCallId)
                throw e
            } catch (e: Exception) {
                "Installed app lookup failed: ${e.message}"
            }
            if (diagnostic != null) return failPreparation(diagnostic)
        }
        val destinationPackage = appInvocation?.destinationPackage
        val approvalPackageName = destinationPackage ?: packageName
        val policyDecision = policyEngine.check(toolName, params, packageName, destinationPackage)
        Log.d(TAG, "Policy decision for $toolName: $policyDecision")
        
        when (policyDecision) {
            is PolicyDecision.Deny -> {
                val cancelledState = ToolCallState.Cancelled(
                    resolvedCallId, toolName, params, "Policy denied: ${policyDecision.reason}", null
                )
                updateState(cancelledState, onStateChange)
                cleanupCall(resolvedCallId)
                return ToolCallResult.Cancelled(resolvedCallId, "Policy denied: ${policyDecision.reason}", replan = true)
            }
            
            is PolicyDecision.AskUser -> {
                val approvalSubjectPackage = approvalPackageName?.takeIf(::isValidApprovalPackageName)
                if (approvalSubjectPackage == null) {
                    val cancelledState = ToolCallState.Cancelled(
                        resolvedCallId, toolName, params, "Policy denied: approval package unknown", null
                    )
                    updateState(cancelledState, onStateChange)
                    cleanupCall(resolvedCallId)
                    return ToolCallResult.Cancelled(resolvedCallId, "Policy denied: approval package unknown", replan = true)
                }

                // === STATE: AWAITING_APPROVAL ===
                state = ToolCallState.AwaitingApproval(
                    callId = resolvedCallId,
                    toolName = toolName,
                    params = params,
                    invocation = invocation,
                    description = invocation.getDescription()
                )
                updateState(state, onStateChange)
                
                // Prepare approval tracking BEFORE notifying UI to avoid race with fast approvals
                val deferred = CompletableDeferred<ApprovalDecision>()
                pendingApprovals[resolvedCallId] = deferred

                // Notify that approval is required (includes callId for proper resolution)
                val approvalDetails = ApprovalDetails(
                    callId = resolvedCallId,
                    toolName = toolName,
                    args = params,
                    description = invocation.getDescription(),
                    packageName = approvalSubjectPackage,
                    appTier = policyDecision.appTier,
                    reason = policyDecision.reason
                )
                val decision = try {
                    if (awaitApproval != null) awaitApproval(approvalDetails, deferred)
                    else {
                        onApprovalRequired?.invoke(approvalDetails)
                        withTimeout(APPROVAL_TIMEOUT_MS) { deferred.await() }
                    }
                } catch (e: TimeoutCancellationException) {
                    // Timeout: return directly with proper message (not reusing DENIED path)
                    Log.w(TAG, "Approval timeout for $resolvedCallId")
                    pendingApprovals.remove(resolvedCallId)
                    val cancelledState = ToolCallState.Cancelled(
                        resolvedCallId, toolName, params, "Approval timed out", null
                    )
                    updateState(cancelledState, onStateChange)
                    cleanupCall(resolvedCallId)
                    return ToolCallResult.Cancelled(resolvedCallId, "Approval timed out")
                } catch (e: CancellationException) {
                    cleanupCall(resolvedCallId)
                    throw e
                } catch (e: Exception) {
                    val message = "Approval request failed: ${e.message ?: e.javaClass.simpleName}"
                    updateState(ToolCallState.Error(resolvedCallId, toolName, params, message, e), onStateChange)
                    cleanupCall(resolvedCallId)
                    return ToolCallResult.Error(resolvedCallId, message, e)
                } finally {
                    pendingApprovals.remove(resolvedCallId)
                }
                
                Log.d(TAG, "Approval decision for $resolvedCallId: $decision")
                
                when (decision) {
                    ApprovalDecision.DENIED -> {
                        val cancelledState = ToolCallState.Cancelled(
                            resolvedCallId, toolName, params, "User denied", decision
                        )
                        updateState(cancelledState, onStateChange)
                        cleanupCall(resolvedCallId)
                        return ToolCallResult.Cancelled(resolvedCallId, "User denied")
                    }
                    ApprovalDecision.ABORT -> {
                        val cancelledState = ToolCallState.Cancelled(
                            resolvedCallId, toolName, params, "User aborted", decision
                        )
                        updateState(cancelledState, onStateChange)
                        cleanupCall(resolvedCallId)
                        return ToolCallResult.Cancelled(resolvedCallId, "User aborted session")
                    }
                    ApprovalDecision.APPROVED -> {
                        // TOCTOU guard: re-check foreground app hasn't changed during approval wait.
                        val currentPkg = context.platform.getCurrentPackageName()
                        if (packageName != null && currentPkg != packageName) {
                            Log.w(TAG, "Foreground app changed during approval wait: $packageName -> $currentPkg")
                            val cancelledState = ToolCallState.Cancelled(
                                resolvedCallId, toolName, params,
                                "App changed during approval wait", decision
                            )
                            updateState(cancelledState, onStateChange)
                            cleanupCall(resolvedCallId)
                            return ToolCallResult.Cancelled(
                                resolvedCallId,
                                "App changed during approval wait", replan = true
                            )
                        } else if (packageName == null && currentPkg != null) {
                            // Original package was unknown — now check if landed on a BLOCKED app
                            val currentTier = policyEngine.appClassifier.classify(currentPkg)
                            if (currentTier == AppTier.BLOCKED) {
                                Log.w(TAG, "Blocked app detected after approval: $currentPkg")
                                val cancelledState = ToolCallState.Cancelled(
                                    resolvedCallId, toolName, params,
                                    "Blocked app detected after approval", decision
                                )
                                updateState(cancelledState, onStateChange)
                                cleanupCall(resolvedCallId)
                                return ToolCallResult.Cancelled(
                                    resolvedCallId,
                                    "Blocked app detected after approval"
                                )
                            }
                        }
                        // Continue to execution
                        approvalWasRequired = true
                    }
                }
            }
            
            PolicyDecision.Allow -> {
                // === STATE: SCHEDULED ===
                state = ToolCallState.Scheduled(resolvedCallId, toolName, params, invocation)
                updateState(state, onStateChange)
            }
        }
        
        // Check for cancellation before execution
        if (context.isCancelled() || token.isCancelled()) {
            val cancelledState = ToolCallState.Cancelled(resolvedCallId, toolName, params, "Cancelled before execution")
            updateState(cancelledState, onStateChange)
            cleanupCall(resolvedCallId)
            return ToolCallResult.Cancelled(resolvedCallId, "Cancelled before execution")
        }
        
        // === STATE: EXECUTING ===
        state = ToolCallState.Executing(resolvedCallId, toolName, params, invocation)
        updateState(state, onStateChange)
        
        val executionResult = try {
            val executionSnapshot = if (approvalWasRequired) {
                val raw = context.platform.captureScreen()
                policyEngine.appClassifier.maskIfBlocked(raw, context.platform.getCurrentPackageName())
            } else context.currentSnapshot
            val execContext = object : ToolExecutionContext {
                override val callId: String = resolvedCallId
                override val platform: AndroidPlatform = context.platform
                override val currentSnapshot: ScreenSnapshot? = executionSnapshot
                override val appClassifier: AppClassifier = policyEngine.appClassifier
                override fun isCancelled(): Boolean = context.isCancelled() || token.isCancelled()
            }
            invocation.execute(execContext)
        } catch (e: CancellationException) {
            cleanupCall(resolvedCallId)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Tool execution failed: $toolName", e)
            ToolExecutionResult.Failure(e.message ?: "Execution failed", e)
        }
        
        // === TERMINAL STATE === (cleanup in finally to handle any unexpected exceptions - M1)
        return try {
            when (executionResult) {
                is ToolExecutionResult.Success -> {
                    val successState = ToolCallState.Success(resolvedCallId, toolName, params, executionResult)
                    updateState(successState, onStateChange)
                    ToolCallResult.Success(
                        callId = resolvedCallId,
                        output = executionResult.output,
                        observation = executionResult.observation
                    )
                }
                
                is ToolExecutionResult.Failure -> {
                    val errorState = ToolCallState.Error(
                        resolvedCallId, toolName, params, executionResult.error, executionResult.exception
                    )
                    updateState(errorState, onStateChange)
                    ToolCallResult.Error(resolvedCallId, executionResult.error, executionResult.exception)
                }
                
                is ToolExecutionResult.Cancelled -> {
                    val cancelledState = ToolCallState.Cancelled(resolvedCallId, toolName, params, executionResult.reason)
                    updateState(cancelledState, onStateChange)
                    ToolCallResult.Cancelled(resolvedCallId, executionResult.reason)
                }
            }
        } finally {
            // Ensure cleanup regardless of how we exit (M1 fix)
            cleanupCall(resolvedCallId)
        }
    }
    
    /**
     * Resolve a pending approval.
     * 
     * Called when user responds to an approval request.
     * 
     * @param callId The tool call ID to resolve
     * @param decision The user's approval decision
     * @return true if approval was resolved, false if no pending approval found for callId
     */
    fun resolveApproval(callId: String, decision: ApprovalDecision): Boolean {
        val deferred = pendingApprovals[callId]
        return if (deferred?.complete(decision) == true) {
            Log.d(TAG, "Resolved approval for $callId: $decision")
            true
        } else {
            Log.w(TAG, "No pending approval found for $callId")
            false
        }
    }
    
    /**
     * Cancel a tool call.
     *
     * Signals the per-call cancellation token and aborts any pending approval.
     * Does NOT remove from activeToolCalls — the executing coroutine reaches
     * a terminal state and cleans up via the normal path.
     */
    fun cancel(callId: String) {
        cancellationTokens[callId]?.cancel()
        pendingApprovals[callId]?.complete(ApprovalDecision.ABORT)
        Log.d(TAG, "Cancelled tool call: $callId")
    }

    /**
     * Cancel all active tool calls.
     *
     * Signals all per-call cancellation tokens and aborts all pending approvals.
     * Tracking in activeToolCalls persists until each call reaches a terminal state.
     */
    fun cancelAll() {
        cancellationTokens.values.forEach { it.cancel() }
        pendingApprovals.values.forEach { it.complete(ApprovalDecision.ABORT) }
        pendingApprovals.clear()
        Log.d(TAG, "Signalled cancellation for all tool calls")
    }
    
    /**
     * Get the current state of a tool call.
     */
    fun getState(callId: String): ToolCallState? = activeToolCalls[callId]
    
    /**
     * Get all active (non-terminal) tool calls.
     */
    fun getActiveCallIds(): Set<String> = activeToolCalls.keys.toSet()
    
    /**
     * Check if there are any pending approvals.
     */
    fun hasPendingApprovals(): Boolean = pendingApprovals.isNotEmpty()
    
    private fun updateState(state: ToolCallState, callback: ((ToolCallState) -> Unit)?) {
        if (!state.isTerminal()) {
            activeToolCalls[state.callId] = state
        }
        callback?.invoke(state)
        Log.d(TAG, "State: ${state.callId} -> ${state::class.simpleName}")
    }
    
    private fun generateCallId(): String = UUID.randomUUID().toString().take(8)

    /** Remove a call from all tracking maps. Called when a call reaches a terminal state. */
    private fun cleanupCall(callId: String) {
        activeToolCalls.remove(callId)
        cancellationTokens.remove(callId)
    }

    private fun isValidApprovalPackageName(packageName: String): Boolean =
        packageName.isNotBlank() && '.' in packageName
}

/**
 * Context provided to ToolRouter for execution.
 */
interface ToolRouterContext {
    val platform: AndroidPlatform
    val currentSnapshot: ScreenSnapshot?
    fun isCancelled(): Boolean
}

/**
 * Simple implementation of ToolRouterContext.
 */
class SimpleToolRouterContext(
    override val platform: AndroidPlatform,
    override val currentSnapshot: ScreenSnapshot? = null,
    private val cancellationFlag: AtomicBoolean = AtomicBoolean(false)
) : ToolRouterContext {
    override fun isCancelled(): Boolean = cancellationFlag.get()
    
    fun cancel() {
        cancellationFlag.set(true)
    }
}
