# Test Suite Organization

This directory contains the test suite for the delerium-paste-server API.

## Test Structure

The tests are organized into three main categories:

### 1. Unit Tests - Route Handlers (`routes/`)

Individual endpoint tests with mocked dependencies to verify request validation, error handling, and response formats.

- **`PowRouteTest.kt`** (3 tests)
  - GET /api/pow endpoint
  - PoW challenge generation
  - Configuration handling (enabled/disabled)

- **`CreatePasteRouteTest.kt`** (11 tests)
  - POST /api/pastes endpoint
  - Success cases
  - Rate limiting
  - PoW validation
  - Size validation (content, IV)
  - Expiry validation

- **`GetPasteRouteTest.kt`** (6 tests)
  - GET /api/pastes/{id} endpoint
  - Success cases
  - Not found / expired
  - Single-view deletion
  - View limit enforcement
  - View counting

- **`DeletePasteRouteTest.kt`** (4 tests)
  - DELETE /api/pastes/{id} endpoint
  - Success cases
  - Token validation (missing, invalid)
  - Non-existent paste handling

**Total Route Tests: 24**

### 2. Integration Tests (`integration/`)

End-to-end tests with real database and services to verify full workflows.

- **`PasteLifecycleIntegrationTest.kt`** (3 tests)
  - Full paste lifecycle (create → retrieve → delete)
  - Concurrent requests
  - Multiple pastes with different metadata

- **`PowIntegrationTest.kt`** (1 test)
  - Real PoW challenge generation and solving
  - Paste creation with valid PoW

- **`RateLimitingIntegrationTest.kt`** (2 tests)
  - Rate limiting with real TokenBucket
  - X-Forwarded-For header handling

- **`ViewLimitIntegrationTest.kt`** (3 tests)
  - Paste expiration
  - Single-view paste flow
  - View limit enforcement

- **`SecurityIntegrationTest.kt`** (2 tests)
  - Security headers verification
  - CORS headers verification

**Total Integration Tests: 11**

### 3. Storage Tests

- **`StorageTest.kt`** (6 tests)
  - Database operations
  - CRUD operations
  - View counting
  - Expiration handling

**Total Storage Tests: 6**

### 4. Test Utilities

- **`TestUtils.kt`**
  - Helper functions for creating test databases
  - Test configuration builders
  - PoW challenge solving
  - Base64url encoding utilities
  - Test paste request builders
  - Test module setup for Ktor

## Test Summary

| Category | Files | Tests |
|----------|-------|-------|
| Route Unit Tests | 4 | 24 |
| Integration Tests | 5 | 11 |
| Storage Tests | 1 | 6 |
| Utilities | 1 | - |
| **Total** | **11** | **42** |

## Running Tests

```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests "routes.CreatePasteRouteTest"

# Run specific test method
./gradlew test --tests "routes.CreatePasteRouteTest.testPostPastes_Success_Returns201WithIdAndToken"

# Run all route tests
./gradlew test --tests "routes.*"

# Run all integration tests
./gradlew test --tests "integration.*"

# Run with detailed output
./gradlew test --info
```

## Test Coverage

The test suite provides comprehensive coverage of:

- ✅ All API endpoints (GET /api/pow, POST /api/pastes, GET /api/pastes/{id}, DELETE /api/pastes/{id})
- ✅ Request validation (JSON, size, expiry, PoW)
- ✅ Error handling (400, 403, 404, 429, 500)
- ✅ Rate limiting
- ✅ Proof-of-Work validation
- ✅ View limits and single-view deletion
- ✅ Paste expiration
- ✅ Database operations
- ✅ Security features

## Design Principles

1. **Separation of Concerns**: Unit tests focus on individual endpoints, integration tests verify full workflows
2. **Readability**: Each test file focuses on a specific endpoint or feature
3. **Maintainability**: Small, focused test files are easier to update and debug
4. **Reusability**: Common test utilities are centralized in `TestUtils.kt`
5. **Isolation**: Each test uses its own temporary database and cleans up after itself

## Adding New Tests

When adding new tests:

1. **Route tests**: Add to the appropriate file in `routes/` directory
2. **Integration tests**: Add to the appropriate file in `integration/` directory or create a new file for a new feature
3. **Utilities**: Add reusable helpers to `TestUtils.kt`
4. Follow existing naming conventions: `test<Method><Scenario>_<ExpectedResult>`
5. Include descriptive assertion messages
6. Clean up resources in `@After` methods
