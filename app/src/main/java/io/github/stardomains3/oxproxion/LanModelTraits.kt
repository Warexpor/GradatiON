package io.github.stardomains3.oxproxion

/**
 * Local servers don't say what a model can do, so guess from its id. Only used when a model is
 * first listed; the model edit sheet still lets the user override both flags.
 */
object LanModelTraits {
    private val REASONING = listOf(
        "qwen3", "deepseek-r1", "-r1", "r1-", "gpt-oss", "qwq", "magistral", "thinking",
    )
    private val VISION = listOf(
        "llava", "-vl", "vision", "gemma3", "pixtral", "minicpm-v",
    )

    fun isReasoning(id: String): Boolean = REASONING.any { id.contains(it, ignoreCase = true) }

    fun isVision(id: String): Boolean = VISION.any { id.contains(it, ignoreCase = true) }
}
