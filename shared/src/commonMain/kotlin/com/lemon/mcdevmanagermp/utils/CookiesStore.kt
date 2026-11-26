package com.lemon.mcdevmanagermp.utils

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update

@OptIn(ExperimentalAtomicApi::class)
object CookiesStore {
    private val cookies = AtomicReference<Map<String, String>>(emptyMap())

    fun addCookies(list: List<String>) {
        val received = mutableMapOf<String, String>()
        list.forEach {
            val cookie = it.substringBefore(';')
            val separator = cookie.indexOf('=')
            if (separator <= 0) return@forEach

            val key = cookie.substring(0, separator).trim()
            if (key.isEmpty()) return@forEach

            // Cookie 值也可能包含等号，只在第一个等号处分隔。
            val value = cookie.substring(separator + 1).trim()
            received[key] = value
        }
        if (received.isNotEmpty()) cookies.update { it + received }
    }

    fun removeCookie(key: String) {
        cookies.update { it - key }
    }

    fun addCookie(key: String, value: String) {
        cookies.update { it + (key to value) }
    }

    fun getCookie(key: String): String? = cookies.load()[key]

    fun getAllCookiesString(): String =
        cookies.load().entries.joinToString("; ") { "${it.key}=${it.value}" }

    fun getAllCookiesMap(): Map<String, String> = cookies.load().toMap()

    fun clearCookies() {
        cookies.store(emptyMap())
    }
}
