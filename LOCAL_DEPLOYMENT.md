# Local Deployment Guide

This guide shows how to deploy and run the delerium-paste-server locally.

## Quick Start

### Option 1: Using the Run Script (Easiest)

```bash
./run-local.sh
```

This script will:
- Create the `data/` directory if needed
- Set the database path to `./data/pastes.db`
- Build the distribution if needed
- Start the server on port 8080

### Option 2: Manual Deployment

```bash
# 1. Build the project
./gradlew build

# 2. Create distribution
./gradlew installDist

# 3. Create data directory
mkdir -p data

# 4. Set database path and run
export DB_PATH="jdbc:sqlite:$(pwd)/data/pastes.db"
./build/install/delerium-paste-server/bin/delerium-paste-server
```

### Option 3: Using Gradle Run

```bash
# Set database path
export DB_PATH="jdbc:sqlite:$(pwd)/data/pastes.db"

# Run directly
./gradlew run
```

## Configuration

### Environment Variables

- **`DB_PATH`**: Database connection string (default: `jdbc:sqlite:/data/pastes.db`)
  - For local: `jdbc:sqlite:./data/pastes.db` or use absolute path
- **`DELETION_TOKEN_PEPPER`**: Secret pepper for deletion tokens (optional, auto-generated if not set)

### Database Location

By default, the database is stored at:
- **Docker**: `/data/pastes.db` (inside container)
- **Local**: `./data/pastes.db` (relative to project root)

To use a custom location:
```bash
export DB_PATH="jdbc:sqlite:/path/to/your/database.db"
```

## Verifying the Deployment

### Check Server Status

```bash
# Check if server is running
curl http://localhost:8080/api/pow

# Expected response:
# {"challenge":"...","difficulty":10,"expiresAt":...}
```

### Check Database

```bash
# List tables
sqlite3 data/pastes.db ".tables"

# Should show: pastes
```

### Test API Endpoints

```bash
# Get PoW challenge
curl http://localhost:8080/api/pow

# Create a paste (example - requires proper encryption client-side)
curl -X POST http://localhost:8080/api/pastes \
  -H "Content-Type: application/json" \
  -d '{"ct":"...","iv":"...","meta":{"expireTs":9999999999}}'
```

## Stopping the Server

If running in foreground:
- Press `Ctrl+C`

If running in background:
```bash
# Find process
lsof -ti:8080

# Kill process
kill $(lsof -ti:8080)
```

## Troubleshooting

### Port Already in Use

```bash
# Find what's using port 8080
lsof -i:8080

# Kill the process or change port in application.conf
```

### Database Permission Issues

```bash
# Ensure data directory is writable
chmod 755 data
chmod 644 data/pastes.db
```

### Build Issues

```bash
# Clean and rebuild
./gradlew clean build

# Rebuild distribution
./gradlew clean installDist
```

## Development Tips

1. **Auto-reload**: For development, use `./gradlew run` which supports hot reload
2. **Logs**: Check console output for server logs
3. **Database**: The SQLite database is created automatically on first run
4. **Testing**: Run tests with `./gradlew test`

## Next Steps

- See [README.md](README.md) for full documentation
- See [docs/CONTAINER_PUBLISHING.md](docs/CONTAINER_PUBLISHING.md) for Docker deployment
- Access the API at `http://localhost:8080`
