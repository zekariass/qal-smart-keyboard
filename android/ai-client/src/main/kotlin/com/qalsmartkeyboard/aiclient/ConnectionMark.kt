package com.qalsmartkeyboard.aiclient

/**
 * Set once a connection to the server carries the request (an OkHttp network interceptor only runs then). A call that
 * fails while it is still unset never reached the server: refused, unknown host, no route, the connect timeout, or the
 * call timeout firing while connecting. The AI client reports that as [com.qalsmartkeyboard.ime.ai.AiFailure.Unreachable]
 * ("can't reach the server"), not as "That took too long", which is kept for a server that accepted the request and
 * answered too slowly. Carries no request data.
 */
internal class ConnectionMark {
    @Volatile var connected: Boolean = false
        private set

    /** Marks the request's [ConnectionMark] (its tag) as connected and goes on unchanged. */
    object Interceptor : okhttp3.Interceptor {
        override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
            chain.request().tag(ConnectionMark::class.java)?.connected = true
            return chain.proceed(chain.request())
        }
    }
}
