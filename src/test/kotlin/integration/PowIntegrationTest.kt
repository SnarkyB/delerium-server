package integration

/**
 * PowIntegrationTest.kt - Proof-of-Work integration tests
 * 
 * Tests PoW functionality with real challenge generation and solving.
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
import PowService
import CreatePasteResponse
import PowChallenge
import PowSubmission
import createTestDatabase
import createTestAppConfig
import createTestPasteRequest
import solvePowChallenge
import testModule

class PowIntegrationTest {
    private lateinit var db: Database
    private lateinit var repo: PasteRepo
    private lateinit var testDbFile: File
    private val testPepper = "test-pepper-pow-integration"
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
    fun testPasteCreationWithPow() = testApplication {
        val cfg = createTestAppConfig(powEnabled = true, powDifficulty = 8)
        val pow = PowService(cfg.powDifficulty, cfg.powTtl)

        application {
            testModule(repo, null, pow, cfg)
        }

        // 1. Get PoW challenge
        val powResponse = client.get("/api/pow")
        assertEquals(HttpStatusCode.OK, powResponse.status)
        val challenge = objectMapper.readValue<PowChallenge>(powResponse.bodyAsText())
        assertNotNull("Challenge should be returned", challenge.challenge)

        // 2. Solve PoW challenge
        val nonce = solvePowChallenge(challenge.challenge, challenge.difficulty)
        assertNotNull("Should be able to solve PoW", nonce)

        // 3. Create paste with valid PoW
        val request = createTestPasteRequest(
            pow = PowSubmission(challenge = challenge.challenge, nonce = nonce!!)
        )
        val createResponse = client.post("/api/pastes") {
            contentType(ContentType.Application.Json)
            setBody(objectMapper.writeValueAsString(request))
        }
        assertEquals(HttpStatusCode.Created, createResponse.status)
        val createResult = objectMapper.readValue<CreatePasteResponse>(createResponse.bodyAsText())
        assertNotNull("Paste should be created", createResult.id)
    }
}
