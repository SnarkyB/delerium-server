/**
 * TestUtils.kt - Utility functions for testing
 * 
 * Provides helper functions for creating test data, solving PoW challenges,
 * and setting up test environments.
 */

import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import org.jetbrains.exposed.sql.Database
import io.ktor.server.application.*
import io.ktor.server.routing.routing
import io.ktor.serialization.jackson.jackson
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import apiRoutes
import PasteRepo
import PasteMeta
import CreatePasteRequest
import PowSubmission
import PowService
import TokenBucket
import AppConfig

/**
 * Create a temporary SQLite database for testing
 * 
 * @return Pair of Database and File (for cleanup)
 */
fun createTestDatabase(): Pair<Database, File> {
    val testDbFile = File.createTempFile("test_paste_db", ".sqlite")
    testDbFile.deleteOnExit()
    val db = Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
    return Pair(db, testDbFile)
}

/**
 * Create a test AppConfig with customizable values
 * 
 * @param powEnabled Whether PoW is enabled (default: true)
 * @param powDifficulty PoW difficulty (default: 8 for fast tests)
 * @param powTtl PoW TTL in seconds (default: 300)
 * @param rlEnabled Whether rate limiting is enabled (default: true)
 * @param rlCapacity Rate limiter capacity (default: 100)
 * @param rlRefill Rate limiter refill per minute (default: 100)
 * @param maxSizeBytes Maximum paste size (default: 1048576)
 * @param idLength Paste ID length (default: 10)
 */
fun createTestAppConfig(
    powEnabled: Boolean = true,
    powDifficulty: Int = 8,
    powTtl: Int = 300,
    rlEnabled: Boolean = true,
    rlCapacity: Int = 100,
    rlRefill: Int = 100,
    maxSizeBytes: Int = 1048576,
    idLength: Int = 10
): AppConfig {
    return AppConfig(
        dbPath = "jdbc:sqlite:memory:",
        deletionPepper = "test-pepper-12345",
        powEnabled = powEnabled,
        powDifficulty = powDifficulty,
        powTtl = powTtl,
        rlEnabled = rlEnabled,
        rlCapacity = rlCapacity,
        rlRefill = rlRefill,
        maxSizeBytes = maxSizeBytes,
        idLength = idLength
    )
}

/**
 * Create a valid test paste request
 * 
 * @param ct Ciphertext (default: valid base64url string)
 * @param iv IV (default: valid 12-byte base64url string)
 * @param expireTs Expiration timestamp (default: 1 hour from now)
 * @param viewsAllowed Views allowed (default: null = unlimited)
 * @param singleView Single view flag (default: false)
 * @param mime MIME type (default: "text/plain")
 * @param pow Optional PoW submission
 */
fun createTestPasteRequest(
    ct: String = "dGVzdC1jaXBoZXJ0ZXh0LWNvbnRlbnQ",
    iv: String = "dGVzdC1pdi0xMjM",
    expireTs: Long = Instant.now().epochSecond + 3600,
    viewsAllowed: Int? = null,
    singleView: Boolean? = null,
    mime: String? = "text/plain",
    pow: PowSubmission? = null
): CreatePasteRequest {
    return CreatePasteRequest(
        ct = ct,
        iv = iv,
        meta = PasteMeta(
            expireTs = expireTs,
            viewsAllowed = viewsAllowed,
            mime = mime,
            singleView = singleView
        ),
        pow = pow
    )
}

/**
 * Solve a PoW challenge by brute force
 * 
 * This is a helper function for tests. It tries nonces sequentially
 * until it finds one that produces a hash with sufficient leading zero bits.
 * 
 * @param challenge The PoW challenge string
 * @param difficulty Required number of leading zero bits
 * @param maxAttempts Maximum number of nonces to try (default: 100000)
 * @return The nonce that solves the challenge, or null if not found within maxAttempts
 */
fun solvePowChallenge(challenge: String, difficulty: Int, maxAttempts: Int = 100000): Long? {
    val md = MessageDigest.getInstance("SHA-256")
    for (nonce in 0L until maxAttempts) {
        val input = "$challenge:$nonce".toByteArray()
        val digest = md.digest(input)
        val bits = leadingZeroBits(digest)
        if (bits >= difficulty) {
            return nonce
        }
    }
    return null
}

/**
 * Count leading zero bits in a byte array
 * Helper function for PoW solving
 */
private fun leadingZeroBits(b: ByteArray): Int {
    var bits = 0
    for (by in b) {
        val v = by.toInt() and 0xff
        if (v == 0) {
            bits += 8
            continue
        }
        bits += Integer.numberOfLeadingZeros(v) - 24
        break
    }
    return bits
}

/**
 * Base64url encode a byte array
 * 
 * @param bytes Byte array to encode
 * @return Base64url-encoded string without padding
 */
fun base64UrlEncode(bytes: ByteArray): String {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * Create a base64url-encoded string of a specific decoded size
 * 
 * @param decodedSize Desired decoded size in bytes
 * @return Base64url-encoded string that decodes to approximately decodedSize bytes
 */
fun createBase64UrlString(decodedSize: Int): String {
    val bytes = ByteArray(decodedSize)
    bytes.fill(0x42) // Fill with 'B' character
    return base64UrlEncode(bytes)
}

/**
 * Configure application module for testing
 * Helper function to set up ContentNegotiation and routing in testApplication blocks
 */
fun Application.testModule(
    repo: PasteRepo,
    rl: TokenBucket?,
    pow: PowService?,
    cfg: AppConfig
) {
    install(ContentNegotiation) {
        jackson()
    }
    routing {
        apiRoutes(repo, rl, pow, cfg)
    }
}
