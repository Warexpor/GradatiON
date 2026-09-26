package io.github.stardomains3.oxproxion.code

/**
 * Resolves default-agent pill labels for [CodeHostDialog]: prefer a live
 * `bridge/listHarnesses` list when the host answered; otherwise the static [HarnessKind] catalog.
 */
object AgentPillOptions {

    fun resolve(live: List<HarnessInfo>, selected: HarnessKind): List<Pair<String, HarnessKind>> {
        if (live.isEmpty()) {
            return HarnessKind.entries.filter { it != HarnessKind.CUSTOM }.map { it.displayName to it }
        }
        val opts = live.map { it.name to it.kind }.toMutableList()
        if (opts.none { it.second == selected }) {
            opts.add(0, selected.displayName to selected)
        }
        return opts
    }
}
