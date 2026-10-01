package io.github.stardomains3.oxproxion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RpGreetingSyncTest {

    @Test
    fun anEmptyThreadTakesTheGreeting() {
        assertTrue(RpGreetingSync.shouldReplace("", null, "*Mira looks at you.*", false))
    }

    @Test
    fun aBubbleThatIsStillTheCardLineFollowsARename() {
        assertTrue(
            RpGreetingSync.shouldReplace(
                current = "Hello, I'm Mira.",
                expandedBefore = "Hello, I'm Mira.",
                expandedNow = "Hello, I'm Vera.",
                templateChanged = false
            )
        )
    }

    @Test
    fun aRewriteStaysWhenTheCardGreetingDidNotChange() {
        assertFalse(
            RpGreetingSync.shouldReplace(
                current = "She doesn't look up.",
                expandedBefore = "*Mira looks at you.*",
                expandedNow = "*Mira looks at you.*",
                templateChanged = false
            )
        )
    }

    @Test
    fun aRewriteFollowsAnEditToTheGreetingItself() {
        assertTrue(
            RpGreetingSync.shouldReplace(
                current = "She doesn't look up.",
                expandedBefore = "*Mira looks at you.*",
                expandedNow = "You again?",
                templateChanged = true
            )
        )
    }

    @Test
    fun anUnknownPriorLineDoesNotClobberARewrite() {
        assertFalse(RpGreetingSync.shouldReplace("She doesn't look up.", null, "*Mira looks at you.*", false))
    }

    @Test
    fun aBubbleAlreadyOnTheNewLineIsLeftAlone() {
        assertFalse(RpGreetingSync.shouldReplace("You again?", "Hello.", "You again?", true))
    }

    @Test
    fun trailingSpaceIsNotANewGreeting() {
        assertFalse(RpGreetingSync.greetingTextChanged("Hello.", "Hello.\n"))
        assertTrue(RpGreetingSync.greetingTextChanged("Hello.", "You again?"))
    }
}
