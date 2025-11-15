package integration

/**
 * RateLimitingIntegrationTest.kt - Rate limiting integration tests
 * 
 * Tests rate limiting functionality with real TokenBucket implementation.
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
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import PasteRepo
import TokenBucket
import createTestDatabase
import createTestAppConfig
import createTestPasteRequest
import testModule

class RateLimitingIntegrationTest {
    private lateinit var db: Database
    private lateinit var repo: PasteRepo
    private lateinit var testDbFile: File
    private val testPepper = "test-pepper-ratelimit"
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
    fun testPasteCreationWithRateLimiting() = testApplication {
        val cfg = createTestAppConfig(powEnabled = false, rlEnabled = true, rlCapacity = 2, rlRefill = 2)
        val rl = TokenBucket(cfg.rlCapacity, cfg.rlRefill)

        application {
            testModule(repo, rl, null, cfg)
        }

        val request = createTestPasteRequest()

        // First two requests should succeed
        val response1 = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            setBody(objectMapper.writeValueAsString(request))
        }
        assertEquals(HttpStatusCode.Created, response1.status)

        val response2 = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            setBody(objectMapper.writeValueAsString(request))
        }
        assertEquals(HttpStatusCode.Created, response2.status)

        // Third request should be rate limited
        val response3 = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            setBody(objectMapper.writeValueAsString(request))
        }
        assertEquals(HttpStatusCode.TooManyRequests, response3.status)
    }

    @Test
    fun testXForwardedForHeaderHandling() = testApplication {
        val cfg = createTestAppConfig(powEnabled = false, rlEnabled = true, rlCapacity = 10, rlRefill = 10)
        val rl = TokenBucket(cfg.rlCapacity, cfg.rlRefill)

        application {
            testModule(repo, rl, null, cfg)
        }

        // Note: Testing X-Forwarded-For requires trusted proxy IPs to be configured
        // This test verifies basic functionality
        val request = createTestPasteRequest()
        val response = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            header("X-Forwarded-For", "192.168.1.100")
            setBody(objectMapper.writeValueAsString(request))
        }
        assertEquals(HttpStatusCode.Created, response.status)
    }
}
