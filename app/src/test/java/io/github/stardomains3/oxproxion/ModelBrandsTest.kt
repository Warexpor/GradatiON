package io.github.stardomains3.oxproxion

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class ModelBrandsTest {

    /** SVG shorthand Android can't parse throws only when the mark is first drawn. */
    @Test fun everyBrandMarkInflates() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val ids = R.drawable::class.java.fields.filter { it.name.startsWith("ic_brand_") }
        assert(ids.size >= 30) { "brand marks missing: ${ids.size}" }
        for (f in ids) assertNotNull(f.name, ContextCompat.getDrawable(ctx, f.getInt(null)))
    }

    @Test fun providerSlugWins() {
        assertEquals("Anthropic", ModelBrands.of("anthropic/claude-sonnet-4")?.name)
        assertEquals("Meta", ModelBrands.of("meta-llama/llama-3.3-70b-instruct")?.name)
        assertEquals(R.drawable.ic_brand_grok, ModelBrands.of("x-ai/grok-4")?.icon)
        assertEquals("Qwen", ModelBrands.of("qwen/qwen3-235b-a22b:free")?.name)
    }

    @Test fun gemmaIsNotGemini() {
        assertEquals(R.drawable.ic_brand_gemma, ModelBrands.of("google/gemma-3-27b-it")?.icon)
        assertEquals(R.drawable.ic_brand_gemini, ModelBrands.of("google/gemini-2.5-pro")?.icon)
    }

    @Test fun localIdsMatchByFamily() {
        assertEquals("Qwen", ModelBrands.of("qwen3:8b")?.name)
        assertEquals("Meta", ModelBrands.of("llama3.2:3b")?.name)
        assertEquals("DeepSeek", ModelBrands.of("deepseek-r1:14b")?.name)
        assertEquals("OpenAI", ModelBrands.of("gpt-oss:20b")?.name)
        assertEquals("Microsoft", ModelBrands.of("phi4-mini")?.name)
    }

    @Test fun unknownMakersFallBackToMonogram() {
        assertNull(ModelBrands.of("sao10k/l3-euryale-70b"))
        val m = LlmModel("Sao10K: Euryale", "sao10k/l3-euryale-70b", isVisionCapable = false)
        assertEquals("S", ModelRow.monogramFor(m))
    }

    @Test fun shortKeysNeedWholeWords() {
        // "o3" is OpenAI's, but not inside another name.
        assertEquals("OpenAI", ModelBrands.of("o3-mini")?.name)
        assertNull(ModelBrands.of("foo3bar"))
    }

    @Test fun localFilterKeepsOnlyLanModels() {
        val lan = LlmModel("qwen3:8b", "qwen3:8b", isVisionCapable = false, isLANModel = true)
        val cloud = LlmModel("Qwen: Qwen3", "qwen/qwen3-8b", isVisionCapable = false)
        assertEquals(listOf(lan), listOf(lan, cloud).filter { ModelFilter.LOCAL.matches(it) })
        assertEquals(ModelFilter.LOCAL, ModelFilter.fromPrefs(ModelFilter.typePref(ModelFilter.LOCAL), "ALL"))
    }

    /** Old installs drop the untouched seed models, but keep edits, their own picks and the one in use. */
    @Test fun oldSeededDefaultsArePruned() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val prefs = SharedPreferencesHelper(ctx)
        prefs.saveCustomModels(listOf(
            DemoModel.model(),
            LlmModel("OpenAI: GPT-4.1", "openai/gpt-4.1", true),
            LlmModel("xAI: Grok 4", "x-ai/grok-4", true),
            LlmModel("My Grok 3", "x-ai/grok-3", false),
            LlmModel("Anthropic: Claude Opus 4.1", "anthropic/claude-opus-4.1", true),
        ))
        prefs.savePreferenceModelnewchat("x-ai/grok-4")
        prefs.mainPrefs.edit().putBoolean("default_models_seeded", true).commit()
        prefs.seedDefaultModelsIfNeeded()
        assertEquals(
            listOf(DemoModel.ID, "x-ai/grok-4", "x-ai/grok-3", "anthropic/claude-opus-4.1"),
            prefs.getCustomModels().map { it.apiIdentifier }
        )
    }

    @Test fun filterReadsTheOldTwoPrefs() {
        assertEquals(ModelFilter.FREE, ModelFilter.fromPrefs("VISION", "FREE"))
        assertEquals(ModelFilter.VISION, ModelFilter.fromPrefs("VISION", "ALL"))
        assertEquals(ModelFilter.ALL, ModelFilter.fromPrefs("ALL", "PAID"))
    }
}
