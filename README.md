# Delerium Paste Server

A secure, encrypted paste server built with Kotlin and Ktor. All encryption happens client-side; the server only stores encrypted content.

## Features

- 🔒 **Client-side encryption** - Decryption keys never touch the server
- ⏱️ **Automatic expiration** - Pastes are automatically deleted when they expire
- 🔐 **Proof-of-work** - Optional protection against spam
- 🚦 **Rate limiting** - Configurable rate limiting per IP
- 📊 **View limits** - Optional view count limits and single-view pastes
- 🗑️ **Secure deletion** - Deletion tokens with pepper-based hashing
- 🧹 **Automatic cleanup** - Background task removes expired pastes hourly

## Quick Start

### Option 1: Docker (Recommended)

```bash
# Pull and run the latest image
docker run -d \
  -p 8080:8080 \
  -v $(pwd)/data:/data \
  -e DELETION_TOKEN_PEPPER=$(openssl rand -hex 32) \
  ghcr.io/your-username/delerium-paste-server:latest
```

The server will be available at `http://localhost:8080`

### Option 2: Build from Source

#### Prerequisites

- JDK 21 or higher
- Gradle 8.x (included via wrapper)

#### Build and Run

```bash
# Clone the repository
git clone <repository-url>
cd delerium-paste/server

# Build the project
./gradlew build

# Run the server
./gradlew run
```

Or run the JAR directly:

```bash
# Build distribution
./gradlew installDist

# Run from distribution
./build/install/delerium-paste-server/bin/delerium-paste-server
```

## Configuration

### Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `DELETION_TOKEN_PEPPER` | No | Auto-generated | Secret pepper for hashing deletion tokens. **Production**: Set explicitly for consistency across restarts. Generate with: `openssl rand -hex 32` |

### Application Configuration (`application.conf`)

```hocon
ktor {
  deployment { port = 8080 }
  application { modules = [ AppKt.module ] }
}

storage {
  dbPath = "jdbc:sqlite:/data/pastes.db"
  deletionTokenPepper = ${?DELETION_TOKEN_PEPPER}
  
  pow {
    enabled = true
    difficulty = 10
    ttlSeconds = 180
  }
  
  rateLimit {
    enabled = true
    capacity = 30
    refillPerMinute = 30
  }
  
  paste {
    maxSizeBytes = 1048576  # 1 MB
    idLength = 10
  }
}
```

### Configuration Options

- **`storage.dbPath`**: SQLite database path (default: `/data/pastes.db`)
- **`storage.pow.enabled`**: Enable/disable proof-of-work (default: `true`)
- **`storage.pow.difficulty`**: PoW difficulty (leading zero bits, default: `10`)
- **`storage.pow.ttlSeconds`**: PoW challenge TTL in seconds (default: `180`)
- **`storage.rateLimit.enabled`**: Enable/disable rate limiting (default: `true`)
- **`storage.rateLimit.capacity`**: Token bucket capacity (default: `30`)
- **`storage.rateLimit.refillPerMinute`**: Tokens refilled per minute (default: `30`)
- **`storage.paste.maxSizeBytes`**: Maximum paste size in bytes (default: `1048576` = 1 MB)
- **`storage.paste.idLength`**: Length of generated paste IDs (default: `10`)

## Deployment

### Docker Deployment

#### Using Published Images

**From GitHub Container Registry (GHCR):**
```bash
docker pull ghcr.io/your-username/delerium-paste-server:latest
docker run -d \
  -p 8080:8080 \
  -v /path/to/data:/data \
  -e DELETION_TOKEN_PEPPER=your-secret-pepper \
  ghcr.io/your-username/delerium-paste-server:latest
```

**From Docker Hub:**
```bash
docker pull your-username/delerium-paste-server:latest
docker run -d \
  -p 8080:8080 \
  -v /path/to/data:/data \
  -e DELETION_TOKEN_PEPPER=your-secret-pepper \
  your-username/delerium-paste-server:latest
```

#### Building Docker Image Locally

```bash
# Build the image
docker build -t delerium-paste-server:latest .

# Run the container
docker run -d \
  -p 8080:8080 \
  -v $(pwd)/data:/data \
  -e DELETION_TOKEN_PEPPER=$(openssl rand -hex 32) \
  delerium-paste-server:latest
```

#### Docker Compose

Create `docker-compose.yml`:

```yaml
version: '3.8'

services:
  delerium-paste:
    image: ghcr.io/your-username/delerium-paste-server:latest
    ports:
      - "8080:8080"
    volumes:
      - ./data:/data
    environment:
      - DELETION_TOKEN_PEPPER=${DELETION_TOKEN_PEPPER:-change-me-in-production}
    restart: unless-stopped
```

Run with:
```bash
docker-compose up -d
```

### Production Deployment

#### Requirements

- **Database**: SQLite database file (created automatically)
- **Storage**: Persistent volume for `/data` directory
- **Port**: 8080 (configurable)
- **Memory**: ~256 MB minimum
- **CPU**: 1 core minimum

#### Production Checklist

- [ ] Set `DELETION_TOKEN_PEPPER` to a secure random value
- [ ] Mount persistent volume for `/data` directory
- [ ] Configure reverse proxy (nginx/traefik) for HTTPS
- [ ] Set up monitoring and logging
- [ ] Configure firewall rules
- [ ] Set up backups for database file
- [ ] Review rate limiting settings
- [ ] Review proof-of-work difficulty

#### Reverse Proxy Example (nginx)

```nginx
server {
    listen 80;
    server_name paste.example.com;
    
    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

### Building from Source

#### Prerequisites

- JDK 21+
- Gradle (included via wrapper)

#### Build Steps

```bash
# Clean previous builds
./gradlew clean

# Build project (includes tests)
./gradlew build

# Run tests only
./gradlew test

# Create distribution
./gradlew installDist

# Run application
./gradlew run
```

#### Distribution Structure

After `installDist`, the distribution is available at:
```
build/install/delerium-paste-server/
├── bin/
│   ├── delerium-paste-server      # Unix script
│   └── delerium-paste-server.bat  # Windows script
└── lib/
    └── *.jar                      # Application and dependencies
```

## API Endpoints

### `GET /api/pow`
Request a proof-of-work challenge.

**Response:** `204 No Content` (if PoW disabled) or JSON challenge object

### `POST /api/pastes`
Create a new encrypted paste.

**Request Body:**
```json
{
  "ct": "base64url-encoded-ciphertext",
  "iv": "base64url-encoded-iv",
  "meta": {
    "expireTs": 1234567890,
    "viewsAllowed": 5,
    "mime": "text/plain",
    "singleView": false
  },
  "pow": {
    "challenge": "challenge-string",
    "nonce": 12345
  }
}
```

**Response:** `201 Created`
```json
{
  "id": "paste-id",
  "deleteToken": "deletion-token"
}
```

### `GET /api/pastes/{id}`
Retrieve an encrypted paste.

**Response:** `200 OK`
```json
{
  "ct": "ciphertext",
  "iv": "iv",
  "meta": { ... },
  "viewsLeft": 4
}
```

### `DELETE /api/pastes/{id}?token=...`
Delete a paste using deletion token.

**Response:** `204 No Content` (success) or `403 Forbidden` (invalid token)

## Database

The server uses SQLite for storage. The database file is created automatically at the configured path (default: `/data/pastes.db`).

### Automatic Cleanup

Expired pastes are automatically deleted by a background task that runs every hour. The cleanup:
- Removes all pastes where `expireTs <= current time`
- Logs the number of deleted pastes
- Runs continuously in the background

### Backup

To backup the database:
```bash
# Copy the database file
cp /data/pastes.db /backup/pastes-$(date +%Y%m%d).db

# Or use SQLite backup
sqlite3 /data/pastes.db ".backup /backup/pastes-$(date +%Y%m%d).db"
```

## Development

### Running Tests

```bash
# Run all tests
./gradlew test

# Run with coverage
./gradlew test jacocoTestReport
```

### Project Structure

```
server/
├── src/
│   ├── main/
│   │   ├── kotlin/
│   │   │   ├── App.kt          # Application setup
│   │   │   ├── Routes.kt        # API routes
│   │   │   ├── Storage.kt       # Database operations
│   │   │   ├── Models.kt        # Data models
│   │   │   ├── Pow.kt           # Proof-of-work
│   │   │   ├── RateLimiter.kt   # Rate limiting
│   │   │   └── Utils.kt         # Utilities
│   │   └── resources/
│   │       └── application.conf # Configuration
│   └── test/
│       └── kotlin/
│           └── StorageTest.kt  # Tests
├── build.gradle.kts
├── Dockerfile
└── docker-build.sh
```

## Publishing Container Images

See [docs/CONTAINER_PUBLISHING.md](docs/CONTAINER_PUBLISHING.md) for detailed instructions on:
- Publishing to GitHub Container Registry (GHCR)
- Publishing to Docker Hub
- Automated CI/CD workflows
- Manual publishing

## Security Considerations

1. **Deletion Token Pepper**: Always set `DELETION_TOKEN_PEPPER` in production for consistency
2. **HTTPS**: Use a reverse proxy with TLS in production
3. **Database Backups**: Regularly backup the SQLite database file
4. **Rate Limiting**: Adjust rate limits based on your use case
5. **Proof-of-Work**: Adjust difficulty based on spam patterns
6. **File Permissions**: Ensure database directory has appropriate permissions

## Troubleshooting

### Server won't start

- Check logs: `docker logs <container-id>` or application logs
- Verify database path is writable
- Ensure port 8080 is available
- Check `DELETION_TOKEN_PEPPER` is set (or allow auto-generation)

### Database issues

- Ensure `/data` volume is mounted and writable
- Check file permissions: `ls -la /data`
- Verify SQLite: `sqlite3 /data/pastes.db ".tables"`

### Build failures

- Ensure JDK 21+ is installed: `java -version`
- Check network connectivity for dependencies
- Review build logs for specific errors
- Try: `./gradlew clean build`

### Performance issues

- Adjust rate limiting settings
- Increase proof-of-work difficulty
- Monitor database size
- Check system resources (CPU, memory, disk)

## License

[Add your license here]

## Contributing

[Add contribution guidelines here]
