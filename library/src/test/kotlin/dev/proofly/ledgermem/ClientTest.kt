package dev.proofly.getmnemo

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: MnemoClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = MnemoClient(
            MnemoClient.Config(
                apiKey = "test_key",
                workspaceId = "ws_test",
                baseUrl = server.url("/").toString().trimEnd('/'),
                httpClient = OkHttpClient.Builder().build(),
            ),
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `search sends auth and workspace headers and parses hits`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "hits": [{
                        "memory": {
                          "id": "m1",
                          "text": "remember this",
                          "tags": ["important"],
                          "createdAt": "2026-01-01T00:00:00Z",
                          "updatedAt": "2026-01-01T00:00:00Z",
                          "workspaceId": "ws_test"
                        },
                        "score": 0.91
                      }]
                    }
                    """.trimIndent(),
                ),
        )

        val hits = client.search(SearchRequest(query = "remember"))
        assertEquals(1, hits.size)
        assertEquals("m1", hits[0].memory.id)

        val recorded = server.takeRequest()
        assertEquals("/v1/search", recorded.path)
        assertEquals("Bearer test_key", recorded.getHeader("Authorization"))
        assertEquals("ws_test", recorded.getHeader("x-workspace-id"))
    }

    @Test
    fun `create round-trips memory`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "id": "m2",
                      "text": "added via test",
                      "tags": [],
                      "createdAt": "2026-01-01T00:00:00Z",
                      "updatedAt": "2026-01-01T00:00:00Z",
                      "workspaceId": "ws_test"
                    }
                    """.trimIndent(),
                ),
        )
        val memory = client.create(CreateMemoryInput(text = "added via test"))
        assertEquals("m2", memory.id)
    }

    @Test
    fun `non-2xx surfaces as Http exception`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "error": "missing_workspace", "code": "auth.invalid" }"""),
        )
        val err = assertThrows<MnemoException.Http> {
            client.list()
        }
        assertEquals(401, err.status)
        assertEquals("auth.invalid", err.code)
    }
}
