package dev.proofly.ledgermem

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

public class LedgerMemClient(
    public val config: Config,
) {
    public data class Config(
        val apiKey: String,
        val workspaceId: String,
        val baseUrl: String = "https://api.proofly.dev",
        val httpClient: OkHttpClient = defaultClient(),
        val maxRetries: Int = DEFAULT_MAX_RETRIES,
    ) {
        public companion object {
            public const val DEFAULT_MAX_RETRIES: Int = 3
            public fun defaultClient(): OkHttpClient = OkHttpClient.Builder().build()
        }
    }

    init {
        require(config.apiKey.isNotBlank()) { "apiKey must not be blank" }
        require(config.workspaceId.isNotBlank()) { "workspaceId must not be blank" }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val jsonMedia = "application/json".toMediaType()

    public suspend fun search(request: SearchRequest): List<SearchHit> {
        val response: SearchResponse = call(
            method = "POST",
            path = "/v1/search",
            body = json.encodeToString(SearchRequest.serializer(), request),
            deserializer = SearchResponse.serializer(),
        )
        return response.hits
    }

    public suspend fun list(cursor: String? = null, limit: Int? = null): ListResult {
        val builder = StringBuilder("/v1/memories")
        val params = buildList {
            if (cursor != null) add("cursor=${urlEncode(cursor)}")
            if (limit != null) add("limit=$limit")
        }
        if (params.isNotEmpty()) {
            builder.append('?').append(params.joinToString("&"))
        }
        return call(
            method = "GET",
            path = builder.toString(),
            body = null,
            deserializer = ListResult.serializer(),
        )
    }

    /** Page-by-page stream of all memories from `cursor` forward. */
    public fun stream(pageSize: Int = 50): Flow<List<Memory>> = flow {
        var cursor: String? = null
        do {
            val page = list(cursor = cursor, limit = pageSize)
            if (page.memories.isNotEmpty()) emit(page.memories)
            // Treat blank cursor as end-of-stream to avoid an infinite loop when
            // the server returns "" instead of null.
            cursor = page.nextCursor?.takeIf { it.isNotBlank() }
        } while (cursor != null)
    }.flowOn(Dispatchers.IO)

    public suspend fun get(id: String): Memory = call(
        method = "GET",
        path = "/v1/memories/${urlEncode(id)}",
        body = null,
        deserializer = Memory.serializer(),
    )

    public suspend fun create(input: CreateMemoryInput): Memory = call(
        method = "POST",
        path = "/v1/memories",
        body = json.encodeToString(CreateMemoryInput.serializer(), input),
        deserializer = Memory.serializer(),
    )

    public suspend fun update(id: String, input: UpdateMemoryInput): Memory = call(
        method = "PATCH",
        path = "/v1/memories/${urlEncode(id)}",
        body = json.encodeToString(UpdateMemoryInput.serializer(), input),
        deserializer = Memory.serializer(),
    )

    public suspend fun delete(id: String) {
        executeRaw("DELETE", "/v1/memories/${urlEncode(id)}", null).close()
    }

    private suspend fun <T> call(
        method: String,
        path: String,
        body: String?,
        deserializer: kotlinx.serialization.KSerializer<T>,
    ): T {
        val response = executeRaw(method, path, body)
        response.use { res ->
            val payload = res.body?.string().orEmpty()
            return try {
                json.decodeFromString(deserializer, payload)
            } catch (err: Throwable) {
                throw LedgerMemException.Decoding(
                    "Failed to decode ${deserializer.descriptor.serialName}: ${err.message}",
                    err,
                )
            }
        }
    }

    private suspend fun executeRaw(method: String, path: String, body: String?): Response {
        val url = (config.baseUrl.trimEnd('/') + path).toHttpUrl()
        val maxAttempts = config.maxRetries.coerceAtLeast(0) + 1

        repeat(maxAttempts) { attempt ->
            val builder = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("x-workspace-id", config.workspaceId)
                .header("Accept", "application/json")
                .header("User-Agent", "ledgermem-android/$SDK_VERSION")

            val rb = body?.toRequestBody(jsonMedia)
            when (method) {
                "GET" -> builder.get()
                "DELETE" -> builder.delete(rb)
                "POST" -> builder.post(rb ?: "".toRequestBody(jsonMedia))
                "PATCH" -> builder.patch(rb ?: "".toRequestBody(jsonMedia))
                else -> error("Unsupported method: $method")
            }

            val response = try {
                await(config.httpClient.newCall(builder.build()))
            } catch (transport: LedgerMemException.Transport) {
                if (attempt < maxAttempts - 1) {
                    kotlinx.coroutines.delay(retryDelayMs(attempt))
                    return@repeat
                }
                throw transport
            }

            if (isRetryableStatus(response.code) && attempt < maxAttempts - 1) {
                response.close()
                kotlinx.coroutines.delay(retryDelayMs(attempt))
                return@repeat
            }

            if (!response.isSuccessful) {
                val raw = response.body?.string().orEmpty()
                response.close()
                val parsed = runCatching { json.decodeFromString(ApiError.serializer(), raw) }.getOrNull()
                throw LedgerMemException.Http(
                    status = response.code,
                    message = parsed?.error ?: "request failed",
                    code = parsed?.code,
                )
            }
            return response
        }
        throw LedgerMemException.Transport("request failed after retries", null)
    }

    private fun isRetryableStatus(status: Int): Boolean =
        status == 429 || status in 500..599

    private fun retryDelayMs(attempt: Int): Long {
        val shifted = RETRY_BASE_DELAY_MS shl attempt.coerceAtMost(20)
        val capped = minOf(shifted, RETRY_MAX_DELAY_MS)
        return (0..capped).random()
    }

    private suspend fun await(call: Call): Response = suspendCancellableCoroutine { cont ->
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(LedgerMemException.Transport(e.message ?: "io error", e))
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
        cont.invokeOnCancellation { call.cancel() }
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        const val SDK_VERSION = "0.1.0"
        const val RETRY_BASE_DELAY_MS = 200L
        const val RETRY_MAX_DELAY_MS = 5_000L
    }
}
