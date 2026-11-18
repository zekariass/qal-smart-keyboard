package com.qalsmartkeyboard.packsync

import com.qalsmartkeyboard.api.ApiClients
import com.qalsmartkeyboard.api.ApiErrors
import com.qalsmartkeyboard.api.generated.apis.LanguagesApi
import com.qalsmartkeyboard.api.generated.models.ClientPlatformEnum
import com.qalsmartkeyboard.api.generated.models.ReleaseChannel

/**
 * [PackDiscovery] over the generated client (`GET /language-resources`, `GET /resource-signing-keys`).
 * The main client sends the client headers and, when signed in, the bearer token (staged rollouts
 * are only visible to authenticated callers). Generated-client exceptions become `ApiError`.
 */
class ApiPackDiscovery(clients: ApiClients, private val pageLimit: Int = 100, private val maxPages: Int = 10) : PackDiscovery {
    private val api: LanguagesApi = clients.api(::LanguagesApi)

    override fun offers(languageCode: String, channel: String, appVersion: String): List<ResourceOffer> = call {
        val out = ArrayList<ResourceOffer>()
        var cursor: String? = null
        for (page in 0 until maxPages) {
            val r = api.listLanguageResources(
                platform = ClientPlatformEnum.ANDROID,
                appVersion = appVersion,
                channel = ReleaseChannel.valueOf(channel),
                languageCode = languageCode,
                cursor = cursor,
                limit = pageLimit,
            )
            r.items.mapTo(out) {
                ResourceOffer(
                    id = it.id.toString(),
                    languageCode = it.languageCode,
                    resourceType = it.resourceType.value,
                    version = it.version,
                    platform = it.platform.value,
                    channel = it.channel.value,
                    artifactUrl = it.artifactUrl.toString(),
                    sha256 = it.sha256,
                    sizeBytes = it.sizeBytes,
                    containerSizeBytes = it.containerSizeBytes,
                    baseResourceId = it.baseResourceId?.toString(),
                    signatureKeyId = it.signatureKeyId,
                    minAppVersion = it.minAppVersion,
                )
            }
            cursor = r.nextCursor ?: break
        }
        out
    }

    override fun signingKeys(): List<KeyAnnouncement> = call {
        api.listResourceSigningKeys().items.map { KeyAnnouncement(it.keyId, it.status.value) }
    }

    private fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        throw ApiErrors.map(e)
    }
}
