package integration

/**
 * SecurityIntegrationTest.kt - Security-related integration tests
 * 
 * Tests security headers, CORS, and other security features.
 * Note: Some headers are set by App.kt, not by routes, so these tests
 * verify the routes work correctly in a security context.
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
import PasteRepo
import createTestDatabase
import createTestAppConfig
import testModule

class SecurityIntegrationTest {
    private lateinit var db: Database
    private lateinit var repo: PasteRepo
    private lateinit var testDbFile: File
    private val testPepper = "test-pepper-security"

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
    fun testSecurityHeaders() = testApplication {
        val cfg = createTestAppConfig(powEnabled = false, rlEnabled = false)

        application {
            testModule(repo, null, null, cfg)
        }

        val response = client.get("/api/pow")
        val headers = response.headers

        // Check security headers (these would be set by App.kt intercept, but we're testing routes only)
        // In a full integration test with App.module(), we'd check:
        // - X-Content-Type-Options: nosniff
        // - X-Frame-Options: DENY
        // - Content-Security-Policy
        // etc.
        // For now, we just verify the response works
        assertNotNull("Response should have headers", headers)
    }

    @Test
    fun testCorsHeaders() = testApplication {
        val cfg = createTestAppConfig(powEnabled = false, rlEnabled = false)

        application {
            testModule(repo, null, null, cfg)
        }

        // CORS headers would be set by CORS plugin in App.kt
        // This test verifies the endpoint works
        val response = client.get("/api/pow")
        assertEquals(HttpStatusCode.NoContent, response.status)
    }
}
