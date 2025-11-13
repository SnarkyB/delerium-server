#!/bin/bash

# Local deployment script for delerium-paste-server
# This script sets up and runs the server locally

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Create data directory if it doesn't exist
mkdir -p data

# Set database path to local data directory
export DB_PATH="jdbc:sqlite:${SCRIPT_DIR}/data/pastes.db"

# Generate a pepper if not set (optional for local dev)
if [ -z "$DELETION_TOKEN_PEPPER" ]; then
    echo "ℹ️  DELETION_TOKEN_PEPPER not set. Using auto-generated pepper for local development."
    echo "   For production, set DELETION_TOKEN_PEPPER explicitly."
fi

# Check if distribution is built
if [ ! -d "build/install/delerium-paste-server" ]; then
    echo "📦 Building distribution..."
    ./gradlew installDist --no-daemon
fi

echo "🚀 Starting delerium-paste-server..."
echo "   Database: ${DB_PATH}"
echo "   Port: 8080"
echo "   Access: http://localhost:8080"
echo ""
echo "Press Ctrl+C to stop the server"
echo ""

# Run the server
exec ./build/install/delerium-paste-server/bin/delerium-paste-server
