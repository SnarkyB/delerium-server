package routes

/**
 * GetPasteRouteTest.kt - Tests for GET /api/pastes/{id} endpoint
 * 
 * Tests paste retrieval with various scenarios:
 * - Success cases
 * - Not found / expired
 * - Single-view deletion
 * - View limit enforcement
 * - View counting
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
import java.time.Instant
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import PasteRepo
import CreatePasteResponse
import PastePayload
import createTestDatabase
import createTestAppConfig
import createTestPasteRequest
import testModule

class GetPasteRouteTest {
    private lateinit var db: Database
    private lateinit var repo: PasteRepo
    private lateinit var testDbFile: File
    private val testPepper = "test-pepper-get"
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

    // ========== Success Tests ==========

    @Test
    fun testGetPastes_Success_Returns200WithPayload() = testApplication {
        val cfg = createTestAppConfig(powEnabled = false, rlEnabled = false)
        val request = createTestPasteRequest()

        application {
            testModule(repo, null, null, cfg)
        }

        // Create a paste first
        val createResponse = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            setBody(objectMapper.writeValueAsString(request))
        }
        val createResult = objectMapper.readValue<CreatePasteResponse>(createResponse.bodyAsText())

        // Retrieve it
        val getResponse = client.get("/api/pastes/${createResult.id}")
        assertEquals(HttpStatusCode.OK, getResponse.status)
        val payload = objectMapper.readValue<PastePayload>(getResponse.bodyAsText())
        assertEquals("Ciphertext should match", request.ct, payload.ct)
        assertEquals("IV should match", request.iv, payload.iv)
        assertEquals("ExpireTs should match", request.meta.expireTs, payload.meta.expireTs)
    }

    // ========== Not Found Tests ==========

    @Test
    fun testGetPastes_NonExistent_Returns404() = testApplication {
        val cfg = createTestAppConfig()

        application {
            testModule(repo, null, null, cfg)
        }

        val response = client.get("/api/pastes/nonexistent123")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun testGetPastes_Expired_Returns404() = testApplication {
        val cfg = createTestAppConfig(powEnabled = false, rlEnabled = false)
        // Create paste with future expiry first
        val futureExpiry = Instant.now().epochSecond + 3600
        val request = createTestPasteRequest(expireTs = futureExpiry)

        application {
            testModule(repo, null, null, cfg)
        }

        // Create paste
        val createResponse = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            setBody(objectMapper.writeValueAsString(request))
        }
        val createResult = objectMapper.readValue<CreatePasteResponse>(createResponse.bodyAsText())

        // Manually expire the paste by deleting it (simulates expiration)
        repo.delete(createResult.id)

        // Try to retrieve expired/deleted paste
        val getResponse = client.get("/api/pastes/${createResult.id}")
        assertEquals(HttpStatusCode.NotFound, getResponse.status)
    }

    // ========== Single View Tests ==========

    @Test
    fun testGetPastes_SingleView_DeletesAfterFirstView() = testApplication {
        val cfg = createTestAppConfig(powEnabled = false, rlEnabled = false)
        val request = createTestPasteRequest(singleView = true)

        application {
            testModule(repo, null, null, cfg)
        }

        // Create paste
        val createResponse = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            setBody(objectMapper.writeValueAsString(request))
        }
        val createResult = objectMapper.readValue<CreatePasteResponse>(createResponse.bodyAsText())

        // First view should succeed
        val getResponse1 = client.get("/api/pastes/${createResult.id}")
        assertEquals(HttpStatusCode.OK, getResponse1.status)

        // Second view should fail (paste deleted)
        val getResponse2 = client.get("/api/pastes/${createResult.id}")
        assertEquals(HttpStatusCode.NotFound, getResponse2.status)
    }

    // ========== View Limit Tests ==========

    @Test
    fun testGetPastes_ViewLimitReached_DeletesAfterLimit() = testApplication {
        val cfg = createTestAppConfig(powEnabled = false, rlEnabled = false)
        val request = createTestPasteRequest(viewsAllowed = 2)

        application {
            testModule(repo, null, null, cfg)
        }

        // Create paste
        val createResponse = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            setBody(objectMapper.writeValueAsString(request))
        }
        val createResult = objectMapper.readValue<CreatePasteResponse>(createResponse.bodyAsText())

        // First view should succeed - shows viewsLeft BEFORE increment
        val getResponse1 = client.get("/api/pastes/${createResult.id}")
        assertEquals(HttpStatusCode.OK, getResponse1.status)
        val payload1 = objectMapper.readValue<PastePayload>(getResponse1.bodyAsText())
        assertEquals("Views left should be 2 (before increment)", 2, payload1.viewsLeft)

        // Second view should succeed and delete - shows viewsLeft BEFORE increment (1), then deletes
        val getResponse2 = client.get("/api/pastes/${createResult.id}")
        assertEquals(HttpStatusCode.OK, getResponse2.status)
        val payload2 = objectMapper.readValue<PastePayload>(getResponse2.bodyAsText())
        assertEquals("Views left should be 1 (before increment, then deletes)", 1, payload2.viewsLeft)

        // Third view should fail (paste deleted)
        val getResponse3 = client.get("/api/pastes/${createResult.id}")
        assertEquals(HttpStatusCode.NotFound, getResponse3.status)
    }

    @Test
    fun testGetPastes_IncrementsViews() = testApplication {
        val cfg = createTestAppConfig(powEnabled = false, rlEnabled = false)
        val request = createTestPasteRequest(viewsAllowed = 5)

        application {
            testModule(repo, null, null, cfg)
        }

        // Create paste
        val createResponse = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            setBody(objectMapper.writeValueAsString(request))
        }
        val createResult = objectMapper.readValue<CreatePasteResponse>(createResponse.bodyAsText())

        // First view - payload shows viewsLeft BEFORE this view is counted
        val getResponse1 = client.get("/api/pastes/${createResult.id}")
        val payload1 = objectMapper.readValue<PastePayload>(getResponse1.bodyAsText())
        assertEquals("Views left should be 5 (before increment)", 5, payload1.viewsLeft)

        // Second view - now shows 4 (after first increment)
        val getResponse2 = client.get("/api/pastes/${createResult.id}")
        val payload2 = objectMapper.readValue<PastePayload>(getResponse2.bodyAsText())
        assertEquals("Views left should be 4 (after first increment)", 4, payload2.viewsLeft)
    }

    @Test
    fun testGetPastes_UnlimitedViews_ReturnsNullViewsLeft() = testApplication {
        val cfg = createTestAppConfig(powEnabled = false, rlEnabled = false)
        val request = createTestPasteRequest(viewsAllowed = null)

        application {
            testModule(repo, null, null, cfg)
        }

        // Create paste
        val createResponse = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            setBody(objectMapper.writeValueAsString(request))
        }
        val createResult = objectMapper.readValue<CreatePasteResponse>(createResponse.bodyAsText())

        // Retrieve it
        val getResponse = client.get("/api/pastes/${createResult.id}")
        val payload = objectMapper.readValue<PastePayload>(getResponse.bodyAsText())
        assertNull("Views left should be null for unlimited", payload.viewsLeft)
    }
}
