package integration

/**
 * PasteLifecycleIntegrationTest.kt - Full paste lifecycle integration tests
 * 
 * Tests the complete flow of creating, retrieving, and deleting pastes
 * with real database and services.
 */

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.jetbrains.exposed.sql.Database
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import PasteRepo
import CreatePasteResponse
import PastePayload
import createTestDatabase
import createTestAppConfig
import createTestPasteRequest
import testModule

class PasteLifecycleIntegrationTest {
    private lateinit var db: Database
    private lateinit var repo: PasteRepo
    private lateinit var testDbFile: File
    private val testPepper = "test-pepper-lifecycle"
    private val objectMapper = jacksonObjectMapper()

    @Before
    fun setUp() {
        val (database, file) = createTestDatabase()
        db = database
        testDbFile = file
        repo = PasteRepo(db, testPepper)
    }

    @After
    fun tearDown() {
        if (::testDbFile.isInitialized && testDbFile.exists()) {
            testDbFile.delete()
        }
    }

    @Test
    fun testFullPasteLifecycle_CreateRetrieveDelete() = testApplication {
        val cfg = createTestAppConfig(powEnabled = false, rlEnabled = false)

        application {
            testModule(repo, null, null, cfg)
        }

        // 1. Create paste
        val request = createTestPasteRequest()
        val createResponse = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            setBody(objectMapper.writeValueAsString(request))
        }
        assertEquals(HttpStatusCode.Created, createResponse.status)
        val createResult = objectMapper.readValue<CreatePasteResponse>(createResponse.bodyAsText())
        assertNotNull("Paste ID should be returned", createResult.id)
        assertNotNull("Delete token should be returned", createResult.deleteToken)

        // 2. Retrieve paste
        val getResponse = client.get("/api/pastes/${createResult.id}")
        assertEquals(HttpStatusCode.OK, getResponse.status)
        val payload = objectMapper.readValue<PastePayload>(getResponse.bodyAsText())
        assertEquals("Ciphertext should match", request.ct, payload.ct)
        assertEquals("IV should match", request.iv, payload.iv)
        assertEquals("ExpireTs should match", request.meta.expireTs, payload.meta.expireTs)

        // 3. Delete paste
        val deleteResponse = client.delete("/api/pastes/${createResult.id}?token=${createResult.deleteToken}")
        assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

        // 4. Verify paste is deleted
        val getAfterDeleteResponse = client.get("/api/pastes/${createResult.id}")
        assertEquals(HttpStatusCode.NotFound, getAfterDeleteResponse.status)
    }

    @Test
    fun testConcurrentRequests() = testApplication {
        val cfg = createTestAppConfig(powEnabled = false, rlEnabled = false)

        application {
            testModule(repo, null, null, cfg)
        }

        // Create multiple pastes sequentially
        val request = createTestPasteRequest()
        val results = mutableListOf<String>()

        for (i in 1..5) {
            val response = client.post("/api/pastes") {
                contentType(ContentType.Application.Json)
                setBody(objectMapper.writeValueAsString(request))
            }
            if (response.status == HttpStatusCode.Created) {
                val result = objectMapper.readValue<CreatePasteResponse>(response.bodyAsText())
                results.add(result.id)
            }
        }

        // Verify all requests succeeded
        assertEquals("All 5 requests should succeed", 5, results.size)
        assertEquals("All IDs should be unique", 5, results.distinct().size)
    }

    @Test
    fun testMultiplePastesWithDifferentMetadata() = testApplication {
        val cfg = createTestAppConfig(powEnabled = false, rlEnabled = false)

        application {
            testModule(repo, null, null, cfg)
        }

        // Create paste with MIME type
        val request1 = createTestPasteRequest(mime = "text/html")
        val response1 = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            setBody(objectMapper.writeValueAsString(request1))
        }
        val result1 = objectMapper.readValue<CreatePasteResponse>(response1.bodyAsText())
        val payload1 = objectMapper.readValue<PastePayload>(
            client.get("/api/pastes/${result1.id}").bodyAsText()
        )
        assertEquals("MIME type should be preserved", "text/html", payload1.meta.mime)

        // Create paste with view limit
        val request2 = createTestPasteRequest(viewsAllowed = 5)
        val response2 = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            setBody(objectMapper.writeValueAsString(request2))
        }
        val result2 = objectMapper.readValue<CreatePasteResponse>(response2.bodyAsText())
        val payload2 = objectMapper.readValue<PastePayload>(
            client.get("/api/pastes/${result2.id}").bodyAsText()
        )
        assertEquals("Views allowed should be 5", 5, payload2.meta.viewsAllowed)
        assertEquals("Views left should be 5 (before increment)", 5, payload2.viewsLeft)

        // Create paste with single view
        val request3 = createTestPasteRequest(singleView = true)
        val response3 = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            setBody(objectMapper.writeValueAsString(request3))
        }
        val result3 = objectMapper.readValue<CreatePasteResponse>(response3.bodyAsText())
        val payload3 = objectMapper.readValue<PastePayload>(
            client.get("/api/pastes/${result3.id}").bodyAsText()
        )
        assertTrue("Single view should be true", payload3.meta.singleView == true)
    }
}
