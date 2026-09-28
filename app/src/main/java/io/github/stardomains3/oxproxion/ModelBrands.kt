package io.github.stardomains3.oxproxion

import androidx.annotation.DrawableRes

/**
 * The maker behind a model id, for the mark in model lists. OpenRouter ids lead with the
 * provider slug; local ids ("qwen3:8b", "llama3.2") only carry the family, so those match by
 * keyword. Unknown makers get no mark and the row falls back to a monogram.
 */
object ModelBrands {

    data class Brand(val name: String, @DrawableRes val icon: Int)

    private val OPENAI = Brand("OpenAI", R.drawable.ic_brand_openai)
    private val ANTHROPIC = Brand("Anthropic", R.drawable.ic_brand_claude)
    private val GOOGLE = Brand("Google", R.drawable.ic_brand_gemini)
    private val GEMMA = Brand("Google", R.drawable.ic_brand_gemma)
    private val XAI = Brand("xAI", R.drawable.ic_brand_grok)
    private val DEEPSEEK = Brand("DeepSeek", R.drawable.ic_brand_deepseek)
    private val META = Brand("Meta", R.drawable.ic_brand_meta)
    private val MISTRAL = Brand("Mistral", R.drawable.ic_brand_mistral)
    private val QWEN = Brand("Qwen", R.drawable.ic_brand_qwen)
    private val MOONSHOT = Brand("Moonshot", R.drawable.ic_brand_kimi)
    private val ZAI = Brand("Z.ai", R.drawable.ic_brand_zai)
    private val MINIMAX = Brand("MiniMax", R.drawable.ic_brand_minimax)
    private val COHERE = Brand("Cohere", R.drawable.ic_brand_cohere)
    private val PERPLEXITY = Brand("Perplexity", R.drawable.ic_brand_perplexity)
    private val NVIDIA = Brand("NVIDIA", R.drawable.ic_brand_nvidia)
    private val MICROSOFT = Brand("Microsoft", R.drawable.ic_brand_microsoft)
    private val AMAZON = Brand("Amazon", R.drawable.ic_brand_nova)
    private val BAIDU = Brand("Baidu", R.drawable.ic_brand_baidu)
    private val TENCENT = Brand("Tencent", R.drawable.ic_brand_hunyuan)
    private val BYTEDANCE = Brand("ByteDance", R.drawable.ic_brand_doubao)
    private val NOUS = Brand("Nous", R.drawable.ic_brand_nous)
    private val OPENROUTER = Brand("OpenRouter", R.drawable.ic_brand_openrouter)
    private val INFLECTION = Brand("Inflection", R.drawable.ic_brand_inflection)
    private val AI21 = Brand("AI21", R.drawable.ic_brand_ai21)
    private val LIQUID = Brand("Liquid", R.drawable.ic_brand_liquid)
    private val INCEPTION = Brand("Inception", R.drawable.ic_brand_inception)
    private val ARCEE = Brand("Arcee", R.drawable.ic_brand_arcee)
    private val STEPFUN = Brand("StepFun", R.drawable.ic_brand_stepfun)
    private val XIAOMI = Brand("Xiaomi", R.drawable.ic_brand_xiaomi)
    private val GRADATION = Brand("GradatiON", R.drawable.ic_gradation_mark)

    private val bySlug = mapOf(
        "openai" to OPENAI,
        "anthropic" to ANTHROPIC,
        "google" to GOOGLE,
        "x-ai" to XAI,
        "deepseek" to DEEPSEEK,
        "meta-llama" to META,
        "mistralai" to MISTRAL,
        "qwen" to QWEN,
        "moonshotai" to MOONSHOT,
        "z-ai" to ZAI,
        "thudm" to ZAI,
        "minimax" to MINIMAX,
        "cohere" to COHERE,
        "perplexity" to PERPLEXITY,
        "nvidia" to NVIDIA,
        "microsoft" to MICROSOFT,
        "amazon" to AMAZON,
        "baidu" to BAIDU,
        "tencent" to TENCENT,
        "bytedance" to BYTEDANCE,
        "bytedance-seed" to BYTEDANCE,
        "nousresearch" to NOUS,
        "openrouter" to OPENROUTER,
        "inflection" to INFLECTION,
        "ai21" to AI21,
        "liquid" to LIQUID,
        "inception" to INCEPTION,
        "arcee-ai" to ARCEE,
        "stepfun-ai" to STEPFUN,
        "xiaomi" to XIAOMI,
        "gradation" to GRADATION,
    )

    /** Family keywords in the model part of an id, checked in order (gemma before gemini/google). */
    private val byFamily = listOf(
        "gpt" to OPENAI, "o1" to OPENAI, "o3" to OPENAI, "o4" to OPENAI,
        "claude" to ANTHROPIC,
        "gemma" to GEMMA, "gemini" to GOOGLE,
        "grok" to XAI,
        "deepseek" to DEEPSEEK,
        "llama" to META,
        "mistral" to MISTRAL, "mixtral" to MISTRAL, "codestral" to MISTRAL, "devstral" to MISTRAL, "magistral" to MISTRAL,
        "qwen" to QWEN, "qwq" to QWEN,
        "kimi" to MOONSHOT,
        "glm" to ZAI,
        "minimax" to MINIMAX,
        "command" to COHERE,
        "sonar" to PERPLEXITY,
        "nemotron" to NVIDIA,
        "phi" to MICROSOFT,
        "nova" to AMAZON,
        "ernie" to BAIDU,
        "hunyuan" to TENCENT,
        "doubao" to BYTEDANCE, "seed" to BYTEDANCE,
        "hermes" to NOUS,
        "lfm" to LIQUID,
    )

    fun of(model: LlmModel): Brand? = of(model.apiIdentifier)

    fun of(apiIdentifier: String): Brand? {
        val id = apiIdentifier.lowercase().removePrefix("~")
        // Local tags look like "qwen3:8b", so the family is searched across the whole id.
        val family = familyOf(id)
        val slug = ModelNames.providerOf(id)?.let { bySlug[it] }
        // Same maker, finer mark: Google's Gemma keeps its own over Gemini's.
        return if (slug != null) (family?.takeIf { it.name == slug.name } ?: slug) else family
    }

    private fun familyOf(id: String): Brand? {
        val words = id.split('-', '_', ':', '.', '/', ' ')
        for ((key, brand) in byFamily) {
            if (key.length <= 2) {
                if (words.any { it == key }) return brand
            } else if (id.contains(key)) {
                return brand
            }
        }
        return null
    }
}
