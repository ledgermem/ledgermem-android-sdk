# LedgerMem Android SDK

Kotlin SDK for the [LedgerMem](https://proofly.dev) memory API. Built on
coroutines + Flow, with a Room-backed offline cache and a WorkManager
periodic sync.

## Install

Once published to Maven Central:

```kotlin
dependencies {
    implementation("dev.proofly.ledgermem:ledgermem-android-sdk:0.1.0")
}
```

Minimum: **Android 7.0 (API 24)**, **Kotlin 1.9.24**, **JDK 17**.

## Gradle wrapper

The `gradle-wrapper.jar` is intentionally **not committed**. CI uses the
[gradle/wrapper-validation-action](https://github.com/gradle/wrapper-validation-action)
to verify a freshly downloaded jar against the `gradle-wrapper.properties`
checksum. To run locally, generate the wrapper jar once:

```bash
gradle wrapper --gradle-version 8.7
```

## Basic usage

```kotlin
import dev.proofly.ledgermem.*

val client = LedgerMemClient(
    LedgerMemClient.Config(
        apiKey = BuildConfig.LEDGERMEM_API_KEY,
        workspaceId = "ws_42",
    ),
)

lifecycleScope.launch {
    val hits = client.search(SearchRequest(query = "design review"))
    val saved = client.create(CreateMemoryInput(text = "Shipped v0.1"))
}
```

`LedgerMemClient.stream(pageSize)` returns a `Flow<List<Memory>>` for
paginating the entire workspace.

## Offline cache (Room)

```kotlin
val db = Room.databaseBuilder(context, MemoryDatabase::class.java, "ledgermem.db").build()
val cache = MemoryCache(db.memoryDao())

cache.recent(limit = 20).collect { memories ->
    // render in UI
}

cache.upsertAll(client.list(limit = 50).memories)
```

## Background sync (WorkManager)

```kotlin
class LedgerMemModule(private val app: Application) : LedgerMemSync.Provider {
    val cache: MemoryCache by lazy { /* … */ }
    val client: LedgerMemClient by lazy { /* … */ }

    override fun client() = client
    override fun cache() = cache
}

LedgerMemSync.install(provider)
LedgerMemSync.schedule(context, intervalMinutes = 30)
```

`SyncWorker` retries on transport errors and 5xx responses; non-recoverable
errors surface via `Result.failure()`.

## Errors

Calls throw a `LedgerMemException`:

| Subclass        | Meaning                                  |
| --------------- | ---------------------------------------- |
| `Configuration` | API key or workspace id missing/blank    |
| `Http`          | Non-2xx response (`status`, `code`)      |
| `Decoding`      | JSON could not be decoded                |
| `Transport`     | OkHttp/IO failure                        |

## KMP-ready note

The pure-Kotlin pieces (`Models.kt`, the JSON encoding helpers) are
designed to lift cleanly into a future Kotlin Multiplatform module so
`Client` can target JVM and native. Filed as #2.

## Testing

```bash
./gradlew :library:test
```

Tests run on the JVM via JUnit 5 with `MockWebServer`; no Android
instrumentation required.

## License

MIT. See `LICENSE`.
