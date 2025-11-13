/**
 * StorageTest.kt - Tests for paste storage and expiration logic
 * 
 * Tests verify that expired pastes are automatically deleted from the database.
 */

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant
import PasteRepo
import Pastes

class StorageTest {
    private lateinit var db: Database
    private lateinit var repo: PasteRepo
    private lateinit var testDbFile: File
    private val testPepper = "test-pepper-12345"

    @Before
    fun setUp() {
        // Use a temporary file database for testing (more reliable than in-memory)
        testDbFile = File.createTempFile("test_paste_db", ".sqlite")
        testDbFile.deleteOnExit()
        db = Database.connect("jdbc:sqlite:${testDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        // Create repo - this will create tables in its init block
        repo = PasteRepo(db, testPepper)
    }

    @After
    fun tearDown() {
        // Clean up test database file
        if (::testDbFile.isInitialized && testDbFile.exists()) {
            testDbFile.delete()
        }
    }

    @Test
    fun testDeleteExpired_DeletesOnlyExpiredPastes() {
        val now = Instant.now().epochSecond
        val pastExpiry = now - 3600 // 1 hour ago
        val futureExpiry = now + 3600 // 1 hour from now

        // Create expired paste
        val expiredId = "expired123"
        repo.create(
            id = expiredId,
            ct = "encrypted-content-1",
            iv = "iv12345678901",
            meta = PasteMeta(expireTs = pastExpiry),
            rawDeleteToken = "token1"
        )

        // Create non-expired paste
        val activeId = "active123"
        repo.create(
            id = activeId,
            ct = "encrypted-content-2",
            iv = "iv12345678902",
            meta = PasteMeta(expireTs = futureExpiry),
            rawDeleteToken = "token2"
        )

        // Verify both pastes exist in database (getIfAvailable filters expired, so check directly)
        val expiredExistsBefore = transaction(db) {
            Pastes.selectAll().where { Pastes.id eq expiredId }.singleOrNull() != null
        }
        assertTrue("Expired paste should exist in database before cleanup", expiredExistsBefore)
        assertNotNull("Active paste should exist before cleanup", repo.getIfAvailable(activeId))

        // Delete expired pastes
        val deletedCount = repo.deleteExpired()

        // Verify results
        assertEquals("Should delete 1 expired paste", 1, deletedCount)
        val expiredExistsAfter = transaction(db) {
            Pastes.selectAll().where { Pastes.id eq expiredId }.singleOrNull() != null
        }
        assertFalse("Expired paste should be deleted from database", expiredExistsAfter)
        assertNotNull("Active paste should still exist", repo.getIfAvailable(activeId))
    }

    @Test
    fun testDeleteExpired_DeletesMultipleExpiredPastes() {
        val now = Instant.now().epochSecond
        val pastExpiry1 = now - 7200 // 2 hours ago
        val pastExpiry2 = now - 1800 // 30 minutes ago
        val futureExpiry = now + 3600 // 1 hour from now

        // Create multiple expired pastes
        repo.create("expired1", "ct1", "iv12345678901", PasteMeta(expireTs = pastExpiry1), "token1")
        repo.create("expired2", "ct2", "iv12345678902", PasteMeta(expireTs = pastExpiry2), "token2")
        
        // Create non-expired paste
        repo.create("active1", "ct3", "iv12345678903", PasteMeta(expireTs = futureExpiry), "token3")

        // Delete expired pastes
        val deletedCount = repo.deleteExpired()

        // Verify results
        assertEquals("Should delete 2 expired pastes", 2, deletedCount)
        val expired1Exists = transaction(db) {
            Pastes.selectAll().where { Pastes.id eq "expired1" }.singleOrNull() != null
        }
        val expired2Exists = transaction(db) {
            Pastes.selectAll().where { Pastes.id eq "expired2" }.singleOrNull() != null
        }
        assertFalse("First expired paste should be deleted", expired1Exists)
        assertFalse("Second expired paste should be deleted", expired2Exists)
        assertNotNull("Active paste should still exist", repo.getIfAvailable("active1"))
    }

    @Test
    fun testDeleteExpired_NoExpiredPastes_ReturnsZero() {
        val now = Instant.now().epochSecond
        val futureExpiry = now + 3600

        // Create only non-expired paste
        repo.create("active1", "ct1", "iv12345678901", PasteMeta(expireTs = futureExpiry), "token1")

        // Delete expired pastes
        val deletedCount = repo.deleteExpired()

        // Verify results
        assertEquals("Should delete 0 pastes", 0, deletedCount)
        assertNotNull("Active paste should still exist", repo.getIfAvailable("active1"))
    }

    @Test
    fun testDeleteExpired_EmptyDatabase_ReturnsZero() {
        // Delete expired pastes from empty database
        val deletedCount = repo.deleteExpired()

        // Verify results
        assertEquals("Should delete 0 pastes from empty database", 0, deletedCount)
    }

    @Test
    fun testDeleteExpired_DeletesExactlyExpiredPastes() {
        val now = Instant.now().epochSecond
        val exactlyNow = now // Paste expiring exactly now should be deleted

        // Create paste expiring exactly now
        repo.create("exact1", "ct1", "iv12345678901", PasteMeta(expireTs = exactlyNow), "token1")
        
        // Create paste expiring 1 second ago
        repo.create("past1", "ct2", "iv12345678902", PasteMeta(expireTs = now - 1), "token2")
        
        // Create paste expiring 1 second from now (should not be deleted)
        repo.create("future1", "ct3", "iv12345678903", PasteMeta(expireTs = now + 1), "token3")

        // Delete expired pastes
        val deletedCount = repo.deleteExpired()

        // Verify results
        assertEquals("Should delete 2 expired pastes (exactly now and past)", 2, deletedCount)
        val exact1Exists = transaction(db) {
            Pastes.selectAll().where { Pastes.id eq "exact1" }.singleOrNull() != null
        }
        val past1Exists = transaction(db) {
            Pastes.selectAll().where { Pastes.id eq "past1" }.singleOrNull() != null
        }
        assertFalse("Paste expiring exactly now should be deleted", exact1Exists)
        assertFalse("Paste expiring in the past should be deleted", past1Exists)
        assertNotNull("Paste expiring in the future should not be deleted", repo.getIfAvailable("future1"))
    }

    @Test
    fun testGetIfAvailable_FiltersExpiredPastes() {
        val now = Instant.now().epochSecond
        val pastExpiry = now - 3600
        val futureExpiry = now + 3600

        // Create expired paste
        repo.create("expired1", "ct1", "iv12345678901", PasteMeta(expireTs = pastExpiry), "token1")
        
        // Create non-expired paste
        repo.create("active1", "ct2", "iv12345678902", PasteMeta(expireTs = futureExpiry), "token2")

        // Verify getIfAvailable filters expired pastes (but they still exist in DB)
        val expiredExistsInDb = transaction(db) {
            Pastes.selectAll().where { Pastes.id eq "expired1" }.singleOrNull() != null
        }
        assertTrue("Expired paste should exist in database", expiredExistsInDb)
        assertNull("Expired paste should not be available via getIfAvailable", repo.getIfAvailable("expired1"))
        assertNotNull("Active paste should be available", repo.getIfAvailable("active1"))
    }
}
