package routes

/**
 * CreatePasteRouteTest.kt - Tests for POST /api/pastes endpoint
 * 
 * Tests paste creation with various validation scenarios:
 * - Success cases
 * - Rate limiting
 * - PoW validation
 * - Size validation
 * - Expiry validation
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
import TokenBucket
import PowService
import CreatePasteResponse
import ErrorResponse
import PowChallenge
import PowSubmission
import createTestDatabase
import createTestAppConfig
import createTestPasteRequest
import solvePowChallenge
import createBase64UrlString
import base64UrlEncode
import testModule

class CreatePasteRouteTest {
    private lateinit var db: Database
    private lateinit var repo: PasteRepo
    private lateinit var testDbFile: File
    private val testPepper = "test-pepper-create"
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
    fun testPostPastes_Success_Returns201WithIdAndToken() = testApplication {
        val cfg = createTestAppConfig(powEnabled = false, rlEnabled = false)
        val request = createTestPasteRequest()

        application {
            testModule(repo, null, null, cfg)
        }

        val response = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            setBody(objectMapper.writeValueAsString(request))
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val result = objectMapper.readValue<CreatePasteResponse>(response.bodyAsText())
        assertNotNull("ID should not be null", result.id)
        assertTrue("ID should not be empty", result.id.isNotEmpty())
        assertEquals("ID length should match config", cfg.idLength, result.id.length)
        assertNotNull("Delete token should not be null", result.deleteToken)
        assertEquals("Delete token should be 24 characters", 24, result.deleteToken.length)
    }

    // ========== Rate Limiting Tests ==========

    @Test
    fun testPostPastes_RateLimited_Returns429() = testApplication {
        val cfg = createTestAppConfig(powEnabled = false, rlEnabled = true, rlCapacity = 1, rlRefill = 1)
        val rl = TokenBucket(cfg.rlCapacity, cfg.rlRefill)
        val request = createTestPasteRequest()

        application {
            testModule(repo, rl, null, cfg)
        }

        // First request should succeed
        val response1 = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            setBody(objectMapper.writeValueAsString(request))
        }
        assertEquals(HttpStatusCode.Created, response1.status)

        // Second request should be rate limited
        val response2 = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            setBody(objectMapper.writeValueAsString(request))
        }
        assertEquals(HttpStatusCode.TooManyRequests, response2.status)
        val error = objectMapper.readValue<ErrorResponse>(response2.bodyAsText())
        assertEquals("rate_limited", error.error)
    }

    // ========== JSON Validation Tests ==========

    @Test
    fun testPostPastes_InvalidJson_Returns400() = testApplication {
        val cfg = createTestAppConfig(powEnabled = false, rlEnabled = false)

        application {
            testModule(repo, null, null, cfg)
        }

        val response = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            setBody("{ invalid json }")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = objectMapper.readValue<ErrorResponse>(response.bodyAsText())
        assertEquals("invalid_json", error.error)
    }

    // ========== PoW Validation Tests ==========

    @Test
    fun testPostPastes_PowRequiredButMissing_Returns400() = testApplication {
        val cfg = createTestAppConfig(powEnabled = true)
        val pow = PowService(cfg.powDifficulty, cfg.powTtl)
        val request = createTestPasteRequest(pow = null)

        application {
            testModule(repo, null, pow, cfg)
        }

        val response = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            setBody(objectMapper.writeValueAsString(request))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = objectMapper.readValue<ErrorResponse>(response.bodyAsText())
        assertEquals("pow_required", error.error)
    }

    @Test
    fun testPostPastes_PowInvalid_Returns400() = testApplication {
        val cfg = createTestAppConfig(powEnabled = true)
        val pow = PowService(cfg.powDifficulty, cfg.powTtl)
        val request = createTestPasteRequest(
            pow = PowSubmission(challenge = "invalid-challenge", nonce = 12345L)
        )

        application {
            testModule(repo, null, pow, cfg)
        }

        val response = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            setBody(objectMapper.writeValueAsString(request))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = objectMapper.readValue<ErrorResponse>(response.bodyAsText())
        assertEquals("pow_invalid", error.error)
    }

    @Test
    fun testPostPastes_ValidPow_Returns201() = testApplication {
        val cfg = createTestAppConfig(powEnabled = true, powDifficulty = 8)
        val pow = PowService(cfg.powDifficulty, cfg.powTtl)

        application {
            testModule(repo, null, pow, cfg)
        }

        // Get a challenge
        val powResponse = client.get("/api/pow")
        val challenge = objectMapper.readValue<PowChallenge>(powResponse.bodyAsText())

        // Solve it
        val nonce = solvePowChallenge(challenge.challenge, challenge.difficulty)
        assertNotNull("Should be able to solve PoW challenge", nonce)

        // Create paste with valid PoW
        val request = createTestPasteRequest(
            pow = PowSubmission(challenge = challenge.challenge, nonce = nonce!!)
        )

        val response = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            setBody(objectMapper.writeValueAsString(request))
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val result = objectMapper.readValue<CreatePasteResponse>(response.bodyAsText())
        assertNotNull("ID should be returned", result.id)
    }

    // ========== Size Validation Tests ==========

    @Test
    fun testPostPastes_ContentTooLarge_Returns400() = testApplication {
        val cfg = createTestAppConfig(powEnabled = false, rlEnabled = false, maxSizeBytes = 100)
        val largeCt = createBase64UrlString(200) // Larger than maxSizeBytes
        val request = createTestPasteRequest(ct = largeCt)

        application {
            testModule(repo, null, null, cfg)
        }

        val response = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            setBody(objectMapper.writeValueAsString(request))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = objectMapper.readValue<ErrorResponse>(response.bodyAsText())
        assertEquals("size_invalid", error.error)
    }

    @Test
    fun testPostPastes_IVTooSmall_Returns400() = testApplication {
        val cfg = createTestAppConfig(powEnabled = false, rlEnabled = false)
        val smallIV = base64UrlEncode(ByteArray(8)) // Less than 12 bytes
        val request = createTestPasteRequest(iv = smallIV)

        application {
            testModule(repo, null, null, cfg)
        }

        val response = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            setBody(objectMapper.writeValueAsString(request))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = objectMapper.readValue<ErrorResponse>(response.bodyAsText())
        assertEquals("size_invalid", error.error)
    }

    @Test
    fun testPostPastes_IVTooLarge_Returns400() = testApplication {
        val cfg = createTestAppConfig(powEnabled = false, rlEnabled = false)
        val largeIV = base64UrlEncode(ByteArray(100)) // More than 64 bytes
        val request = createTestPasteRequest(iv = largeIV)

        application {
            testModule(repo, null, null, cfg)
        }

        val response = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            setBody(objectMapper.writeValueAsString(request))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = objectMapper.readValue<ErrorResponse>(response.bodyAsText())
        assertEquals("size_invalid", error.error)
    }

    // ========== Expiry Validation Tests ==========

    @Test
    fun testPostPastes_ExpiryTooSoon_Returns400() = testApplication {
        val cfg = createTestAppConfig(powEnabled = false, rlEnabled = false)
        val tooSoon = Instant.now().epochSecond + 5 // Less than 10 seconds
        val request = createTestPasteRequest(expireTs = tooSoon)

        application {
            testModule(repo, null, null, cfg)
        }

        val response = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            setBody(objectMapper.writeValueAsString(request))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = objectMapper.readValue<ErrorResponse>(response.bodyAsText())
        assertEquals("expiry_too_soon", error.error)
    }

    @Test
    fun testPostPastes_ExpiryExactly10Seconds_Returns201() = testApplication {
        val cfg = createTestAppConfig(powEnabled = false, rlEnabled = false)
        // Use 11 seconds to account for processing time - validation requires at least 10 seconds
        val exactly10Seconds = Instant.now().epochSecond + 11
        val request = createTestPasteRequest(expireTs = exactly10Seconds)

        application {
            testModule(repo, null, null, cfg)
        }

        val response = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            setBody(objectMapper.writeValueAsString(request))
        }

        assertEquals(HttpStatusCode.Created, response.status)
    }
}
