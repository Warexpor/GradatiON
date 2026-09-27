package io.github.stardomains3.oxproxion.code

import io.github.stardomains3.oxproxion.R

/** Each harness's own mark (monochrome, tinted at use); unknown agents get a terminal. */
val HarnessKind.iconRes: Int
    get() = when (this) {
        HarnessKind.CLAUDE_CODE -> R.drawable.ic_harness_claude
        HarnessKind.CODEX -> R.drawable.ic_harness_codex
        HarnessKind.OPENCODE -> R.drawable.ic_harness_opencode
        HarnessKind.GROK_BUILD -> R.drawable.ic_harness_grok
        HarnessKind.CURSOR_CLI -> R.drawable.ic_harness_cursor
        HarnessKind.PI -> R.drawable.ic_harness_pi
        HarnessKind.CUSTOM -> R.drawable.ic_code_terminal
    }
