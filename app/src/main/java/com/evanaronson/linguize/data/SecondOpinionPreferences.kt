package com.evanaronson.linguize.data

import android.content.Context
import com.evanaronson.linguize.llm.Provider

/** Which model a Lab second opinion asks. */
sealed interface SecondOpinionChoice {
    /** The service the check didn't use, with the model chosen for it in settings. */
    data object Automatic : SecondOpinionChoice

    /** No second opinions. */
    data object Off : SecondOpinionChoice

    /** A chosen service, and a model for it; a null [model] uses that service's model from settings. */
    data class Picked(val provider: Provider, val model: String? = null) : SecondOpinionChoice
}

/** The service and exact model a second opinion runs with. */
data class SecondOpinionTarget(val provider: Provider, val model: String)

/** Whether a second opinion can run next to a check, and with what. */
sealed interface SecondOpinionPlan {
    data class Ready(val target: SecondOpinionTarget) : SecondOpinionPlan
    data object Off : SecondOpinionPlan

    /** The second service has no saved key. */
    data class NoKey(val provider: Provider) : SecondOpinionPlan

    /** The choice comes to the same service and model as the check itself. */
    data object SameAsCheck : SecondOpinionPlan
}

/**
 * Where [choice] leads for a check that ran with [checkProvider] and [checkModel].
 * [modelFor] is the model settings use for a service; [hasKey] whether it has a saved key.
 */
fun planSecondOpinion(
    choice: SecondOpinionChoice,
    checkProvider: Provider,
    checkModel: String,
    modelFor: (Provider) -> String,
    hasKey: (Provider) -> Boolean,
): SecondOpinionPlan {
    val target = when (choice) {
        SecondOpinionChoice.Off -> return SecondOpinionPlan.Off
        SecondOpinionChoice.Automatic -> Provider.entries.first { it != checkProvider }.let { SecondOpinionTarget(it, modelFor(it)) }
        is SecondOpinionChoice.Picked -> SecondOpinionTarget(choice.provider, choice.model?.takeIf { it.isNotBlank() } ?: modelFor(choice.provider))
    }
    return when {
        target.provider == checkProvider && target.model == checkModel -> SecondOpinionPlan.SameAsCheck
        !hasKey(target.provider) -> SecondOpinionPlan.NoKey(target.provider)
        else -> SecondOpinionPlan.Ready(target)
    }
}

/**
 * The Lab second-opinion choice, in its own preferences file (`lab_second_opinion`), so it
 * never touches the app's settings, keys or history. Unset means [SecondOpinionChoice.Automatic].
 */
class SecondOpinionPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var choice: SecondOpinionChoice
        get() = when (prefs.getString(MODE, null)) {
            OFF -> SecondOpinionChoice.Off
            PICK -> Provider.tokens.decode(prefs.getString(PROVIDER, null))
                ?.let { SecondOpinionChoice.Picked(it, prefs.getString(MODEL, null)) }
                ?: SecondOpinionChoice.Automatic
            else -> SecondOpinionChoice.Automatic
        }
        set(value) {
            val edit = prefs.edit()
            when (value) {
                SecondOpinionChoice.Automatic -> edit.putString(MODE, AUTO).remove(PROVIDER).remove(MODEL)
                SecondOpinionChoice.Off -> edit.putString(MODE, OFF).remove(PROVIDER).remove(MODEL)
                is SecondOpinionChoice.Picked -> edit.putString(MODE, PICK).putString(PROVIDER, value.provider.token)
                    .putString(MODEL, value.model)
            }
            edit.apply()
        }

    private companion object {
        const val FILE = "lab_second_opinion"
        const val MODE = "mode"
        const val PROVIDER = "provider"
        const val MODEL = "model"
        const val AUTO = "auto"
        const val OFF = "off"
        const val PICK = "pick"
    }
}
