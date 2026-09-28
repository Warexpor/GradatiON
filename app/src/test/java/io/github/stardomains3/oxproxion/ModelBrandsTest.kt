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

    @Test fun filterReadsTheOldTwoPrefs() {
        assertEquals(ModelFilter.FREE, ModelFilter.fromPrefs("VISION", "FREE"))
        assertEquals(ModelFilter.VISION, ModelFilter.fromPrefs("VISION", "ALL"))
        assertEquals(ModelFilter.ALL, ModelFilter.fromPrefs("ALL", "PAID"))
    }
}
