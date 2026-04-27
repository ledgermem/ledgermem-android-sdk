package dev.proofly.ledgermem

import kotlinx.serialization.Serializable

@Serializable
public data class Memory(
    val id: String,
    val text: String,
    val tags: List<String> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
    val workspaceId: String,
)

@Serializable
public data class SearchHit(
    val memory: Memory,
    val score: Double,
    val highlight: String? = null,
)

@Serializable
public data class SearchRequest(
    val query: String,
    val topK: Int? = null,
    val filter: Filter? = null,
) {
    @Serializable
    public data class Filter(
        val tags: List<String>? = null,
    )
}

@Serializable
public data class CreateMemoryInput(
    val text: String,
    val tags: List<String>? = null,
    val source: String? = null,
)

@Serializable
public data class UpdateMemoryInput(
    val text: String? = null,
    val tags: List<String>? = null,
)

@Serializable
public data class ListResult(
    val memories: List<Memory>,
    val nextCursor: String? = null,
)

@Serializable
internal data class SearchResponse(val hits: List<SearchHit>)

@Serializable
internal data class ApiError(
    val error: String? = null,
    val code: String? = null,
)

public sealed class LedgerMemException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause) {
    public class Configuration(message: String) : LedgerMemException(message)
    public class Http(public val status: Int, message: String, public val code: String?) :
        LedgerMemException("HTTP $status: $message")
    public class Decoding(message: String, cause: Throwable?) : LedgerMemException(message, cause)
    public class Transport(message: String, cause: Throwable?) : LedgerMemException(message, cause)
}
