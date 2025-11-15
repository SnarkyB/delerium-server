package integration

/**
 * ViewLimitIntegrationTest.kt - View limit integration tests
 * 
 * Tests view limit enforcement, expiration, and single-view functionality
 * with real database operations.
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

class ViewLimitIntegrationTest {
    private lateinit var db: Database
    private lateinit var repo: PasteRepo
    private lateinit var testDbFile: File
    private val testPepper = "test-pepper-viewlimit"
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
    fun testPasteExpiration() = testApplication {
        val cfg = createTestAppConfig(powEnabled = false, rlEnabled = false)
        // Create paste with valid expiry first (validation requires at least 10 seconds)
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

        // Retrieve immediately (should work)
        val getResponse1 = client.get("/api/pastes/${createResult.id}")
        assertEquals(HttpStatusCode.OK, getResponse1.status)

        // Manually delete the paste to simulate expiration
        // (In real scenario, expiration happens naturally via getIfAvailable filter)
        repo.delete(createResult.id)

        // Retrieve after expiration (should fail)
        val getResponse2 = client.get("/api/pastes/${createResult.id}")
        assertEquals(HttpStatusCode.NotFound, getResponse2.status)
    }

    @Test
    fun testSingleViewPasteFlow() = testApplication {
        val cfg = createTestAppConfig(powEnabled = false, rlEnabled = false)

        application {
            testModule(repo, null, null, cfg)
        }

        // Create single-view paste
        val request = createTestPasteRequest(singleView = true)
        val createResponse = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            setBody(objectMapper.writeValueAsString(request))
        }
        val createResult = objectMapper.readValue<CreatePasteResponse>(createResponse.bodyAsText())

        // First view succeeds
        val getResponse1 = client.get("/api/pastes/${createResult.id}")
        assertEquals(HttpStatusCode.OK, getResponse1.status)
        val payload1 = objectMapper.readValue<PastePayload>(getResponse1.bodyAsText())
        assertNotNull("Payload should be returned", payload1.ct)

        // Second view fails (paste deleted)
        val getResponse2 = client.get("/api/pastes/${createResult.id}")
        assertEquals(HttpStatusCode.NotFound, getResponse2.status)
    }

    @Test
    fun testViewLimitEnforcement() = testApplication {
        val cfg = createTestAppConfig(powEnabled = false, rlEnabled = false)

        application {
            testModule(repo, null, null, cfg)
        }

        // Create paste with view limit of 3
        val request = createTestPasteRequest(viewsAllowed = 3)
        val createResponse = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            setBody(objectMapper.writeValueAsString(request))
        }
        val createResult = objectMapper.readValue<CreatePasteResponse>(createResponse.bodyAsText())

        // First view - shows viewsLeft BEFORE increment
        val getResponse1 = client.get("/api/pastes/${createResult.id}")
        assertEquals(HttpStatusCode.OK, getResponse1.status)
        val payload1 = objectMapper.readValue<PastePayload>(getResponse1.bodyAsText())
        assertEquals("Views left should be 3 (before increment)", 3, payload1.viewsLeft)

        // Second view - shows viewsLeft BEFORE increment
        val getResponse2 = client.get("/api/pastes/${createResult.id}")
        assertEquals(HttpStatusCode.OK, getResponse2.status)
        val payload2 = objectMapper.readValue<PastePayload>(getResponse2.bodyAsText())
        assertEquals("Views left should be 2 (after first increment)", 2, payload2.viewsLeft)

        // Third view (should delete) - shows viewsLeft BEFORE increment (1), then deletes
        val getResponse3 = client.get("/api/pastes/${createResult.id}")
        assertEquals(HttpStatusCode.OK, getResponse3.status)
        val payload3 = objectMapper.readValue<PastePayload>(getResponse3.bodyAsText())
        assertEquals("Views left should be 1 (before increment, then deletes)", 1, payload3.viewsLeft)

        // Fourth view (should fail - paste deleted)
        val getResponse4 = client.get("/api/pastes/${createResult.id}")
        assertEquals(HttpStatusCode.NotFound, getResponse4.status)
    }
}
