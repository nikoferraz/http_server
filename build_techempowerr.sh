#!/bin/bash

set -e

echo "Building TechEmpower Benchmark Server..."

JAVA_VERSION=$(java -version 2>&1 | head -1 | grep -oP 'version "?\K[0-9]+' || echo "0")

if [ "$JAVA_VERSION" -lt 21 ]; then
    echo "Error: Java 21 or higher is required. Found version $JAVA_VERSION"
    exit 1
fi

echo "Java version $JAVA_VERSION detected - OK"

echo "Attempting to build with Maven..."
if command -v mvn &> /dev/null; then
    mvn clean package -q -DskipTests
    echo "Build successful with Maven"
else
    echo "Maven not found. Installing Maven..."
    if command -v apt-get &> /dev/null; then
        sudo apt-get update && sudo apt-get install -y maven
        mvn clean package -q -DskipTests
        echo "Build successful with Maven"
    elif command -v yum &> /dev/null; then
        sudo yum install -y maven
        mvn clean package -q -DskipTests
        echo "Build successful with Maven"
    else
        echo "Error: Maven not found and cannot install. Please install Maven manually."
        echo "Visit: https://maven.apache.org/download.cgi"
        exit 1
    fi
fi

echo ""
echo "Build complete!"
echo "Compiled JAR: target/http-server-1.0.0.jar"
