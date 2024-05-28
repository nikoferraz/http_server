#!/bin/bash

set -e

echo "Installing TechEmpower Benchmark Server..."

JAVA_VERSION=$(java -version 2>&1 | head -1 | grep -oP 'version "?\K[0-9]+' || echo "0")

if [ "$JAVA_VERSION" -lt 21 ]; then
    echo "Error: Java 21 or higher is required. Found version $JAVA_VERSION"
    exit 1
fi

echo "Java version $JAVA_VERSION detected - OK"

echo "Building project with Maven..."
if [ -f "pom.xml" ]; then
    mvn clean package -q -DskipTests
else
    echo "Error: pom.xml not found"
    exit 1
fi

echo "Setting up PostgreSQL database..."
if ! command -v psql &> /dev/null; then
    echo "Error: PostgreSQL client (psql) not found. Please install PostgreSQL."
    exit 1
fi

if [ -f "setup_postgresql.sh" ]; then
    chmod +x setup_postgresql.sh
    ./setup_postgresql.sh
else
    echo "Error: setup_postgresql.sh not found"
    exit 1
fi

echo "Installation complete!"
echo ""
echo "To start the server, run:"
echo "  java -server -Xmx4G -XX:+UseZGC -XX:+ZGenerational -cp target/http-server-1.0.0.jar HTTPServer.HTTPServer"
echo ""
echo "Or with custom database settings:"
echo "  DB_URL='jdbc:postgresql://localhost:5432/benchmarkdb' \\"
echo "  DB_USER='benchmarkdbuser' \\"
echo "  DB_PASSWORD='benchmarkdbpass' \\"
echo "  java -server -Xmx4G -XX:+UseZGC -XX:+ZGenerational -cp target/http-server-1.0.0.jar HTTPServer.HTTPServer"
