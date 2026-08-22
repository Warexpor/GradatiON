/** Pure helpers for RP API history trimming (unit-tested). */
object RpApiMemory {
    /**
     * Keep [budget] non-system messages. In character RP, pin the opening greeting when
     * there is room; always keep the latest turn (never drop the user message being answered).
     */
    fun <T> trimNonSystem(
        nonSystem: List<T>,
        budget: Int,
        pinCharacterGreeting: Boolean,
        isAssistant: (T) -> Boolean
    ): List<T> {
        if (nonSystem.isEmpty() || budget <= 0) return emptyList()
        if (nonSystem.size <= budget) return nonSystem
        if (!pinCharacterGreeting) return nonSystem.takeLast(budget)
        val greeting = nonSystem.firstOrNull(isAssistant)
        val last = nonSystem.last()
        return when {
            greeting == null || greeting === last -> nonSystem.takeLast(budget)
            budget <= 1 -> listOf(last)
            else -> {
                val middleBudget = (budget - 2).coerceAtLeast(0)
                val middle = nonSystem.drop(1).dropLast(1).takeLast(middleBudget)
                listOf(greeting) + middle + listOf(last)
            }
        }
    }
}
