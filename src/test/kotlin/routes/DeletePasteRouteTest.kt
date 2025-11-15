package routes

/**
 * DeletePasteRouteTest.kt - Tests for DELETE /api/pastes/{id} endpoint
 * 
 * Tests paste deletion with various scenarios:
 * - Success cases
 * - Missing token
 * - Invalid token
 * - Non-existent paste
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
import ErrorResponse
import createTestDatabase
import createTestAppConfig
import createTestPasteRequest
import testModule

class DeletePasteRouteTest {
    private lateinit var db: Database
    private lateinit var repo: PasteRepo
    private lateinit var testDbFile: File
    private val testPepper = "test-pepper-delete"
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
    fun testDeletePastes_Success_Returns204() = testApplication {
        val cfg = createTestAppConfig(powEnabled = false, rlEnabled = false)
        val request = createTestPasteRequest()

        application {
            testModule(repo, null, null, cfg)
        }

        // Create paste
        val createResponse = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            setBody(objectMapper.writeValueAsString(request))
        }
        val createResult = objectMapper.readValue<CreatePasteResponse>(createResponse.bodyAsText())

        // Delete it
        val deleteResponse = client.delete("/api/pastes/${createResult.id}?token=${createResult.deleteToken}")
        assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

        // Verify it's deleted
        val getResponse = client.get("/api/pastes/${createResult.id}")
        assertEquals(HttpStatusCode.NotFound, getResponse.status)
    }

    // ========== Token Validation Tests ==========

    @Test
    fun testDeletePastes_MissingToken_Returns400() = testApplication {
        val cfg = createTestAppConfig(powEnabled = false, rlEnabled = false)
        val request = createTestPasteRequest()

        application {
            testModule(repo, null, null, cfg)
        }

        // Create paste
        val createResponse = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            setBody(objectMapper.writeValueAsString(request))
        }
        val createResult = objectMapper.readValue<CreatePasteResponse>(createResponse.bodyAsText())

        // Try to delete without token
        val deleteResponse = client.delete("/api/pastes/${createResult.id}")
        assertEquals(HttpStatusCode.BadRequest, deleteResponse.status)
        val error = objectMapper.readValue<ErrorResponse>(deleteResponse.bodyAsText())
        assertEquals("missing_token", error.error)
    }

    @Test
    fun testDeletePastes_InvalidToken_Returns403() = testApplication {
        val cfg = createTestAppConfig(powEnabled = false, rlEnabled = false)
        val request = createTestPasteRequest()

        application {
            testModule(repo, null, null, cfg)
        }

        // Create paste
        val createResponse = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            setBody(objectMapper.writeValueAsString(request))
        }
        val createResult = objectMapper.readValue<CreatePasteResponse>(createResponse.bodyAsText())

        // Try to delete with wrong token
        val deleteResponse = client.delete("/api/pastes/${createResult.id}?token=wrong-token-12345")
        assertEquals(HttpStatusCode.Forbidden, deleteResponse.status)
        val error = objectMapper.readValue<ErrorResponse>(deleteResponse.bodyAsText())
        assertEquals("invalid_token", error.error)
    }

    @Test
    fun testDeletePastes_NonExistent_Returns403() = testApplication {
        val cfg = createTestAppConfig()

        application {
            testModule(repo, null, null, cfg)
        }

        // Try to delete non-existent paste
        val deleteResponse = client.delete("/api/pastes/nonexistent123?token=some-token")
        assertEquals(HttpStatusCode.Forbidden, deleteResponse.status)
        val error = objectMapper.readValue<ErrorResponse>(deleteResponse.bodyAsText())
        assertEquals("invalid_token", error.error)
    }
}
