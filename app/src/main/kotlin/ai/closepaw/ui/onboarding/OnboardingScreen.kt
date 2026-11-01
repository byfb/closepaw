package ai.closepaw.ui.onboarding

import ai.closepaw.ui.theme.uiResources

import ai.closepaw.R

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import ai.closepaw.onboarding.ApiKeyAuthMethod
import ai.closepaw.onboarding.ApiKeyStepState
import ai.closepaw.onboarding.DemoStepState
import ai.closepaw.onboarding.OnboardingEffect
import ai.closepaw.onboarding.OnboardingProvider
import ai.closepaw.onboarding.OnboardingStepState
import ai.closepaw.onboarding.PermissionStepState
import ai.closepaw.onboarding.StepOutcomes
import ai.closepaw.onboarding.WizardStep
import kotlinx.coroutines.flow.Flow

/**
 * Full-screen onboarding wizard.
 *
 * Routes to per-step composables based on [currentStep]. Uses [OnboardingShell]
 * for shared scaffold (progress bar, step count, title, back arrow).
 */
@Composable
fun OnboardingScreen(
    currentStep: WizardStep,
    stepState: OnboardingStepState?,
    outcomes: StepOutcomes,
    selectedProvider: OnboardingProvider,
    authMethod: ApiKeyAuthMethod,
    accessibilityGranted: Boolean,
    overlayGranted: Boolean,
    batteryGranted: Boolean,
    effects: Flow<OnboardingEffect>,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onOpenSettings: () -> Unit,
    onSkipStep: () -> Unit,
    onProviderSelected: (OnboardingProvider) -> Unit,
    onAuthMethodSelected: (ApiKeyAuthMethod) -> Unit,
    onStartOAuth: () -> Unit,
    onCancelOAuth: () -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onValidateApiKey: () -> Unit,
    onRetryValidation: () -> Unit,
    onStartDemo: () -> Unit,
    onFinish: () -> Unit,
    onGoToAuthStep: () -> Unit,
    onEffect: (OnboardingEffect) -> Unit
) {
    val strings = uiResources()
    // Consume one-shot effects
    LaunchedEffect(Unit) {
        effects.collect { effect -> onEffect(effect) }
    }

    val totalSteps = 5
    // Back arrow on all steps except the first
    val backAction: (() -> Unit)? = if (currentStep != WizardStep.Accessibility) onBack else null

    when (currentStep) {
        WizardStep.Accessibility -> {
            OnboardingShell(
                stepIndex = 1,
                totalSteps = totalSteps,
                title = strings.getString(R.string.ui_let_closepaw_control_your_phone)
            ) {
                PermissionStepContent(
                    step = WizardStep.Accessibility,
                    state = stepState as? PermissionStepState ?: PermissionStepState.Checking,
                    onOpenSettings = onOpenSettings,
                    onSkip = {},
                    onContinue = onContinue
                )
            }
        }

        WizardStep.Overlay -> {
            OnboardingShell(
                stepIndex = 2,
                totalSteps = totalSteps,
                title = strings.getString(R.string.ui_see_controls_while_the_agent_works),
                onBack = backAction
            ) {
                PermissionStepContent(
                    step = WizardStep.Overlay,
                    state = stepState as? PermissionStepState ?: PermissionStepState.Checking,
                    onOpenSettings = onOpenSettings,
                    onSkip = {},
                    onContinue = onContinue
                )
            }
        }

        WizardStep.Battery -> {
            OnboardingShell(
                stepIndex = 3,
                totalSteps = totalSteps,
                title = strings.getString(R.string.ui_keep_long_tasks_alive),
                onBack = backAction
            ) {
                PermissionStepContent(
                    step = WizardStep.Battery,
                    state = stepState as? PermissionStepState ?: PermissionStepState.Checking,
                    onOpenSettings = onOpenSettings,
                    onSkip = onSkipStep,
                    onContinue = onContinue
                )
            }
        }

        WizardStep.ApiKey -> {
            OnboardingShell(
                stepIndex = 4,
                totalSteps = totalSteps,
                title = strings.getString(R.string.ui_connect_your_model),
                onBack = backAction
            ) {
                ApiKeyStepContent(
                    state = stepState as? ApiKeyStepState ?: ApiKeyStepState.Empty,
                    selectedProvider = selectedProvider,
                    authMethod = authMethod,
                    onProviderSelected = onProviderSelected,
                    onAuthMethodSelected = onAuthMethodSelected,
                    onStartOAuth = onStartOAuth,
                    onCancelOAuth = onCancelOAuth,
                    onContinue = onContinue,
                    onKeyChanged = onApiKeyChanged,
                    onValidate = onValidateApiKey,
                    onRetry = onRetryValidation
                )
            }
        }

        WizardStep.Demo -> {
            OnboardingShell(
                stepIndex = 5,
                totalSteps = totalSteps,
                title = strings.getString(R.string.ui_try_a_safe_demo),
                onBack = backAction
            ) {
                DemoStepContent(
                    state = stepState as? DemoStepState ?: DemoStepState.Ready,
                    onRunDemo = onStartDemo,
                    onSkip = onSkipStep,
                    onGoToAuthStep = onGoToAuthStep
                )
            }
        }

        WizardStep.Complete -> {
            OnboardingShell(
                stepIndex = totalSteps,
                totalSteps = totalSteps,
                title = strings.getString(R.string.ui_you_re_all_set),
                onBack = backAction
            ) {
                CompleteStepContent(
                    outcomes = outcomes,
                    authMethod = authMethod,
                    accessibilityGranted = accessibilityGranted,
                    overlayGranted = overlayGranted,
                    batteryGranted = batteryGranted,
                    onFinish = onFinish
                )
            }
        }
    }
}
