package com.aiia.app.util

import java.util.Locale

/**
 * Transport rules for user-provided endpoints.
 *
 * The app talks to three kinds of hosts: cloud inference APIs, the local OpenAI-compatible
 * server on the device itself, and peers on the LAN over P2P. Only the last two are allowed
 * to stay on cleartext HTTP, so this is enforced in code instead of relying on
 * `usesCleartextTraffic`: Android's network security config can only allow cleartext for
 * named domains, and P2P peers have arbitrary LAN addresses.
 */
object EndpointPolicy {
    sealed interface Verdict {
        data object Allowed : Verdict

        data class Rejected(val reason: String) : Verdict
    }

    /** True when [host] is a literal loopback or RFC1918 / link-local address. */
    fun isPrivateHost(host: String): Boolean {
        val normalized = host.trim().lowercase(Locale.ROOT).removeSurrounding("[", "]")
        if (normalized == "localhost") return true
        if (!IPV4.matches(normalized)) return false
        val parts = normalized.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        val first = parts[0]
        val second = parts[1]
        return when {
            first == 127 -> true
            first == 10 -> true
            first == 192 && second == 168 -> true
            first == 172 && second in 16..31 -> true
            first == 169 && second == 254 -> true
            else -> false
        }
    }

    fun isCleartextHost(host: String): Boolean = isPrivateHost(host)

    /**
     * @param allowPrivateHosts `true` for LAN/loopback endpoints (local API, P2P, a LAN
     *   OpenAI-compatible server), `false` for anything leaving the device.
     */
    fun check(url: String, allowPrivateHosts: Boolean): Verdict {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return Verdict.Rejected("Адрес не задан")

        val scheme =
            trimmed.substringBefore("://", missingDelimiterValue = "")
                .lowercase(Locale.ROOT)
        if (scheme.isEmpty() || scheme == trimmed.lowercase(Locale.ROOT)) {
            return Verdict.Rejected("Адрес должен начинаться с http:// или https://")
        }
        if (scheme != "http" && scheme != "https") {
            return Verdict.Rejected("Недопустимая схема: $scheme")
        }

        val host =
            hostOf(trimmed)
                ?: return Verdict.Rejected("Не удалось разобрать адрес")

        if (scheme == "https") return Verdict.Allowed

        return when {
            isCleartextHost(host) && allowPrivateHosts -> Verdict.Allowed
            isCleartextHost(host) ->
                Verdict.Rejected(
                    "Cleartext HTTP разрешён только для локальной сети и самого устройства"
                )
            else -> Verdict.Rejected("Для внешнего адреса требуется HTTPS: $host")
        }
    }

    fun isAllowed(url: String, allowPrivateHosts: Boolean): Boolean = check(url, allowPrivateHosts) is Verdict.Allowed

    fun require(url: String, allowPrivateHosts: Boolean): String {
        val trimmed = url.trim()
        when (val verdict = check(trimmed, allowPrivateHosts)) {
            is Verdict.Allowed -> return trimmed
            is Verdict.Rejected -> throw IllegalArgumentException(verdict.reason)
        }
    }

    private fun hostOf(url: String): String? {
        val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
        if (afterScheme.isEmpty()) return null
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) return null
        if (authority.contains('@')) return null
        if (authority.startsWith("[")) return authority.substringBefore(']').plus("]")
        return authority.substringBefore(':').ifEmpty { null }
    }

    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
}
