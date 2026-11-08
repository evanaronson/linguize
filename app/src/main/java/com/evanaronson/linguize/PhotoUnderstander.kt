package com.evanaronson.linguize

import android.content.Context
import com.evanaronson.linguize.data.CheckPreferences
import com.evanaronson.linguize.data.ProviderKeys
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.llm.Provider
import com.evanaronson.linguize.llm.lab.GeminiVisionClient
import com.evanaronson.linguize.llm.lab.ImageInput
import com.evanaronson.linguize.llm.lab.OpenAIVisionClient
import com.evanaronson.linguize.llm.lab.PhotoPrompt
import com.evanaronson.linguize.llm.lab.PhotoReading
import com.evanaronson.linguize.llm.lab.VisionClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Reads a photo of text with the configured provider and model. Like [Understander], it has
 * no history, revision or write-back dependency, and the image is never stored.
 */
class PhotoUnderstander(
    private val settings: CheckPreferences,
    private val keys: ProviderKeys,
    private val clients: Map<Provider, VisionClient>,
    loadPrompt: () -> PhotoPrompt,
) {
    /** Read from assets on first use, which is off the main thread. */
    private val prompt by lazy(loadPrompt)

    suspend fun context(): UnderstandContext = withContext(Dispatchers.IO) {
        val provider = settings.provider
        UnderstandContext(provider, settings.model(provider) ?: provider.recommendedModel, settings.nativeLanguage)
    }

    /** Throws CheckFailure, or IllegalArgumentException for an image that isn't fit to send. */
    suspend fun understand(image: ImageInput, requestedContext: UnderstandContext? = null): PhotoReading =
        withContext(Dispatchers.IO) {
            // Not a model failure: the image itself can't be sent. Hosts show it as one they couldn't open.
            require(image.bytes.isNotEmpty() && image.bytes.size <= MAX_IMAGE_BYTES) { "Unusable image: $image" }
            try {
                val used = requestedContext ?: context()
                val key = keys.get(used.provider)?.takeUnless { it.isBlank() }
                    ?: throw CheckFailure(CheckFailure.Reason.NoKey)
                if (key.any { it !in '!'..'~' }) {
                    throw CheckFailure(CheckFailure.Reason.BadKey, userMessage = CheckFailure.Message.InvalidKey)
                }
                val raw = clients.getValue(used.provider).generate(key, used.model, prompt.request(image, used.native))
                prompt.read(raw)
            } catch (e: CheckFailure) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw CheckFailure(CheckFailure.Reason.BadResponse, cause = e)
            }
        }

    companion object {
        /** Prepared images are far smaller; anything bigger was not prepared by this app. */
        const val MAX_IMAGE_BYTES = 6 * 1024 * 1024

        /**
         * Its own HTTP client: reading a page with a multimodal model takes longer than a
         * check, so it gets a longer time limit than the check's 30 seconds.
         */
        fun create(context: Context, settings: CheckPreferences, keys: ProviderKeys): PhotoUnderstander {
            val http = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .callTimeout(90, TimeUnit.SECONDS)
                .build()
            val assets = context.applicationContext.assets
            return PhotoUnderstander(
                settings, keys,
                mapOf(Provider.Gemini to GeminiVisionClient(http), Provider.OpenAI to OpenAIVisionClient(http)),
            ) { PhotoPrompt.load { name -> assets.open(name).bufferedReader().use { it.readText() } } }
        }
    }
}
