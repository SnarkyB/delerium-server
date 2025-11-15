package routes

/**
 * PowRouteTest.kt - Tests for GET /api/pow endpoint
 * 
 * Tests PoW challenge generation and configuration handling.
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
import PowService
import PowChallenge
import createTestDatabase
import createTestAppConfig
import testModule

class PowRouteTest {
    private lateinit var db: Database
    private lateinit var repo: PasteRepo
    private lateinit var testDbFile: File
    private val testPepper = "test-pepper-pow"
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
    fun testGetPow_PowEnabled_ReturnsChallenge() = testApplication {
        val cfg = createTestAppConfig(powEnabled = true)
        val pow = PowService(cfg.powDifficulty, cfg.powTtl)

        application {
            testModule(repo, null, pow, cfg)
        }

        val response = client.get("/api/pow")
        assertEquals(HttpStatusCode.OK, response.status)
        val challenge = objectMapper.readValue<PowChallenge>(response.bodyAsText())
        assertNotNull("Challenge should not be null", challenge.challenge)
        assertTrue("Difficulty should be positive", challenge.difficulty > 0)
        assertTrue("ExpiresAt should be in the future", challenge.expiresAt > Instant.now().epochSecond)
    }

    @Test
    fun testGetPow_PowDisabled_Returns204() = testApplication {
        val cfg = createTestAppConfig(powEnabled = false)

        application {
            testModule(repo, null, null, cfg)
        }

        val response = client.get("/api/pow")
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun testGetPow_ChallengeContainsRequiredFields() = testApplication {
        val cfg = createTestAppConfig(powEnabled = true)
        val pow = PowService(cfg.powDifficulty, cfg.powTtl)

        application {
            testModule(repo, null, pow, cfg)
        }

        val response = client.get("/api/pow")
        val challenge = objectMapper.readValue<PowChallenge>(response.bodyAsText())
        assertNotNull("Challenge string should not be null", challenge.challenge)
        assertTrue("Challenge should not be empty", challenge.challenge.isNotEmpty())
        assertEquals("Difficulty should match config", cfg.powDifficulty, challenge.difficulty)
        assertTrue("ExpiresAt should be in the future", challenge.expiresAt > Instant.now().epochSecond)
    }
}
