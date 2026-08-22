package io.github.stardomains3.oxproxion

enum class ChatMode(val storageValue: String) {
    ASK("ask"),
    RP("rp");

    companion object {
        fun fromStorage(value: String?): ChatMode =
            entries.find { it.storageValue == value } ?: ASK
    }
}
