# High-Performance Java HTTP Server

A production-ready, multi-protocol HTTP server built with Java 21+ Virtual Threads, featuring HTTP/2, WebSocket, Server-Sent Events, and enterprise-grade performance.

[![Java Version](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://openjdk.java.net/)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Test Coverage](https://img.shields.io/badge/tests-400%2B%20passing-brightgreen.svg)](#testing)

---

## Table of Contents

- [Overview](#overview)
- [Key Features](#key-features)
- [Architecture](#architecture)
- [Performance](#performance)
- [Installation](#installation)
- [Configuration](#configuration)
- [Usage](#usage)
  - [Basic Server](#basic-server)
  - [HTTP/2](#http2)
  - [WebSocket](#websocket)
  - [Server-Sent Events](#server-sent-events)
  - [TechEmpower Benchmarks](#techempowerr-benchmarks)
- [Security](#security)
- [Testing](#testing)
- [Deployment](#deployment)
- [API Documentation](#api-documentation)
- [Contributing](#contributing)
- [License](#license)

---

## Overview

This is a modern HTTP server implementation showcasing Java 21's Virtual Threads (Project Loom) for massive concurrency. The server implements multiple protocols and modern web standards while maintaining high performance and security.

**Built for:**
- High-concurrency applications (50,000+ concurrent connections)
- Real-time web applications (WebSocket, SSE)
- Modern HTTP/2 clients
- Performance benchmarking (TechEmpower-ready)
- Educational purposes (clean, well-tested codebase)

---

## Key Features

### Protocol Support
- ✅ **HTTP/1.1** - Full RFC 2616/7230 compliance
- ✅ **HTTP/2** - Binary framing, HPACK compression, stream multiplexing (RFC 7540/7541)
- ✅ **WebSocket** - Full-duplex communication (RFC 6455)
- ✅ **Server-Sent Events** - W3C-compliant server push
- ✅ **TLS 1.2/1.3** - ALPN negotiation, modern cipher suites only

### Performance Features
- 🚀 **Virtual Threads** - Java 21+ lightweight concurrency (50,000+ connections)
- ⚡ **Zero-Copy File Transfer** - FileChannel.transferTo() for large files
- 💾 **ETag Caching** - 2.54µs cache hit vs 4.59ms uncached (1,806x faster)
- 🗜️ **Compression Caching** - 0.007ms cache hit vs 4.64ms uncached (663x faster)
- 🔄 **Buffer Pooling** - 80% GC reduction (5 → 1 GC per 10K requests)
- 🔥 **HTTP Keep-Alive** - Connection reuse

### Security Features
- 🔒 **TLS/SSL** - Modern cipher suites, TLS 1.2+ only
- 🔑 **Authentication** - API Key, HTTP Basic Auth
- 🛡️ **Rate Limiting** - Token bucket algorithm
- 🔐 **OWASP Headers** - HSTS, CSP, X-Frame-Options
- 🚫 **Input Validation** - Path traversal protection, XSS prevention
- 📊 **Security Logging** - Structured JSON logs

### Observability
- 📈 **Prometheus Metrics** - Request counts, latencies, cache stats
- 🔍 **W3C Trace Context** - Distributed tracing support
- 📝 **Structured Logging** - JSON-formatted logs
- ❤️ **Health Checks** - Liveness and readiness endpoints
- 🎯 **Graceful Shutdown** - Kubernetes-compatible

### Enterprise Features
- 🔄 **Virtual Hosting** - Name-based virtual hosts
- 🔀 **URL Routing** - Redirects, rewrites, pattern matching
- 🗄️ **Database Pooling** - HikariCP connection pooling
- 🎭 **Compression** - Gzip and Brotli support
- 📦 **Content Negotiation** - Accept-Encoding, MIME types

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    HTTP Server (Servlet)                 │
│                   Virtual Thread Executor                │
└───────────────────────┬─────────────────────────────────┘
                        │
                        ▼
            ┌───────────────────────┐
            │   ProcessRequest      │
            │   (Request Handler)   │
            └───────────┬───────────┘
                        │
        ┌───────────────┼───────────────┐
        ▼               ▼               ▼
   ┌─────────┐   ┌──────────┐   ┌──────────┐
   │ HTTP/1.1│   │  HTTP/2  │   │ WebSocket│
   └─────────┘   └──────────┘   └──────────┘
        │              │               │
        └──────────────┼───────────────┘
                       ▼
        ┌─────────────────────────────┐
        │      Core Components         │
        ├─────────────────────────────┤
        │ • CacheManager (ETag)        │
        │ • CompressionHandler         │
        │ • RateLimiter                │
        │ • AuthenticationManager      │
        │ • MetricsCollector           │
        │ • BufferPool                 │
        │ • SecurityHeadersHandler     │
        │ • TraceContextHandler        │
        └─────────────────────────────┘
```

### Component Overview

**Core Server:**
- `Servlet.java` - Main server entry point, socket listener
- `ProcessRequest.java` - Request routing and protocol handling
- `ServerConfig.java` - Configuration management
- `TLSManager.java` - TLS/SSL with ALPN support

**HTTP/2 Implementation:**
- `HTTP2Handler.java` - Connection and stream management
- `HTTP2FrameParser.java` - Binary frame encoding/decoding
- `HPACKEncoder/Decoder.java` - Header compression (RFC 7541)
- `HTTP2Stream.java` - Stream state machine and flow control

**WebSocket Implementation:**
- `WebSocketConnection.java` - Connection lifecycle
- `WebSocketFrame.java` - Frame parsing and masking
- `WebSocketHandler.java` - Application callback interface

**SSE Implementation:**
- `SSEManager.java` - Topic-based broadcasting
- `SSEConnection.java` - Event streaming
- `SSEEvent.java` - W3C event formatting

---

## Performance

### Internal Benchmark Results

These are performance characteristics of this server's features:

| Feature | Performance | Notes |
|---------|-------------|-------|
| **ETag Cache Hit** | 2.54µs | Cached vs 4.59ms uncached (1MB files) |
| **Compression Cache Hit** | 0.007ms | Cached vs 4.64ms uncached |
| **Buffer Pool** | 80% GC reduction | 5 → 1 GC per 10K requests |
| **Virtual Threads** | 50,000+ connections | vs 1,000-2,000 with platform threads |
| **Thread Memory** | 1KB per thread | vs 1MB per platform thread |
| **Rate Limiter** | 2.4M checks/sec | Token bucket with LRU eviction |

### TechEmpower Targets

| Test Type | Target | Description |
|-----------|--------|-------------|
| Plaintext | 1-5M req/sec | Raw server performance |
| JSON | 500K-2M req/sec | Serialization overhead |
| Single Query | 20-50K req/sec | Database driver efficiency |
| Multiple Queries | 5-15K req/sec | Connection pooling (20 queries) |
| Updates | 3-10K req/sec | Transaction handling (20 updates) |
| Fortunes | 10-30K req/sec | Server-side rendering |

### Optimization Opportunities

From performance profiling, identified improvements:
- **HPACK encoding** - 30-40% faster with HashMap lookups
- **Buffer allocation** - 20-30% improvement with ThreadLocal pools
- **Frame parsing** - 20-30% faster with ByteBuffer.slice()

See `HTTP2_PERFORMANCE_ANALYSIS.md` for details.

---

## Installation

### Prerequisites

- **Java 21+** (for Virtual Threads support)
- **Maven 3.8+** (for dependency management)
- **PostgreSQL 12+** (for TechEmpower benchmarks, optional)

### Clone and Build

```bash
# Clone repository
git clone https://github.com/yourusername/http_server.git
cd http_server

# Compile
mvn clean compile

# Or compile manually
javac -d . HTTPServer/*.java
```

### Dependencies

The server uses minimal dependencies:

```xml
<!-- pom.xml -->
<dependencies>
    <!-- PostgreSQL JDBC Driver (for TechEmpower) -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <version>42.7.0</version>
    </dependency>

    <!-- HikariCP Connection Pool -->
    <dependency>
        <groupId>com.zaxxer</groupId>
        <artifactId>HikariCP</artifactId>
        <version>5.1.0</version>
    </dependency>

    <!-- Brotli Compression -->
    <dependency>
        <groupId>com.aayushatharva.brotli4j</groupId>
        <artifactId>brotli4j</artifactId>
        <version>1.12.0</version>
    </dependency>

    <!-- JUnit 5 (testing) -->
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>5.10.0</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

---

## Configuration

### Environment Variables

**Authentication (Required):**
```bash
# HTTP Basic Auth - Format: "user1:pass1,user2:pass2"
export HTTP_BASIC_AUTH="admin:securepassword,user:anotherpassword"

# API Keys - Format: "key1,key2,key3"
export HTTP_API_KEYS="sk_live_abc123,sk_test_xyz789"
```

**Database (Optional - for TechEmpower):**
```bash
export DB_HOST="localhost"
export DB_PORT="5432"
export DB_NAME="benchmark"
export DB_USER="benchmarkdbuser"
export DB_PASSWORD="securedbpassword"
```

**Server Configuration:**
```bash
export SERVER_PORT="8080"
export SERVER_WEBROOT="./public"
export SERVER_TLS_ENABLED="true"
export SERVER_TLS_KEYSTORE="./keystore.jks"
export SERVER_TLS_KEYSTORE_PASSWORD="changeit"
```

### ServerConfig.java

Configure programmatically:

```java
ServerConfig config = new ServerConfig();
config.setPort(8443);
config.setWebroot("./public");
config.setTlsEnabled(true);
config.setTlsKeystorePath("./certs/keystore.jks");
config.setTlsKeystorePassword("changeit");

Servlet server = new Servlet(config);
server.start();
```

---

## Usage

### Basic Server

```java
// Simple HTTP server
public static void main(String[] args) {
    ServerConfig config = new ServerConfig();
    config.setPort(8080);
    config.setWebroot("./public");

    Servlet server = new Servlet(config);
    server.start();
}
```

Access at: `http://localhost:8080/`

### HTTP/2

HTTP/2 is automatically negotiated via ALPN when using HTTPS:

```bash
# Start with TLS
export SERVER_TLS_ENABLED=true
java HTTPServer.Servlet

# Test with curl
curl --http2 https://localhost:8443/
```

**Features:**
- Binary framing with 10 frame types
- HPACK header compression (4KB dynamic table)
- Stream multiplexing (up to 2^31-1 concurrent streams)
- Flow control (connection and stream level)
- Server push capability

### WebSocket

**Server-side handler:**

```java
public class ChatHandler implements WebSocketHandler {
    @Override
    public void onOpen(WebSocketConnection conn) {
        System.out.println("Client connected: " + conn.getId());
    }

    @Override
    public void onMessage(WebSocketConnection conn, String message) {
        // Echo message back
        conn.sendText("Echo: " + message);
    }

    @Override
    public void onClose(WebSocketConnection conn, int code, String reason) {
        System.out.println("Client disconnected: " + reason);
    }

    @Override
    public void onError(WebSocketConnection conn, Throwable error) {
        error.printStackTrace();
    }
}

// Register handler
request.setWebSocketHandler(new ChatHandler());
```

**Client-side JavaScript:**

```javascript
const ws = new WebSocket('ws://localhost:8080/chat');

ws.onopen = () => {
    console.log('Connected');
    ws.send('Hello Server!');
};

ws.onmessage = (event) => {
    console.log('Received:', event.data);
};

ws.onclose = () => {
    console.log('Disconnected');
};
```

### Server-Sent Events

**Server-side:**

```java
// Clock handler - sends time every second
public class ClockHandler implements SSEHandler {
    @Override
    public void onOpen(SSEConnection conn) {
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
        executor.scheduleAtFixedRate(() -> {
            SSEEvent event = new SSEEvent();
            event.setEvent("time");
            event.setData(new Date().toString());
            conn.sendEvent(event);
        }, 0, 1, TimeUnit.SECONDS);
    }
}

// Register handler
request.setSSEHandler(new ClockHandler());
```

**Client-side JavaScript:**

```javascript
const eventSource = new EventSource('/events/clock');

eventSource.addEventListener('time', (event) => {
    console.log('Current time:', event.data);
});

eventSource.onerror = () => {
    console.error('Connection error');
    eventSource.close();
};
```

### TechEmpower Benchmarks

**Endpoints:**

```bash
# JSON serialization
curl http://localhost:8080/json
# {"message":"Hello, World!"}

# Single database query
curl http://localhost:8080/db
# {"id":123,"randomNumber":456}

# Multiple queries
curl http://localhost:8080/queries?queries=20
# [{"id":1,"randomNumber":234},...]

# Database updates
curl http://localhost:8080/updates?queries=10
# [{"id":5,"randomNumber":789},...]

# Plaintext
curl http://localhost:8080/plaintext
# Hello, World!

# Fortunes (HTML)
curl http://localhost:8080/fortunes
# <html>...</html>
```

**Setup database:**

```bash
# Run setup script
./setup_postgresql.sh

# Or manually
psql -U postgres -f schema.sql
```

---

## Security

### Security Features

1. **Input Validation**
   - URL decoding with path traversal protection
   - Request size limits (16KB headers, configurable body)
   - HPACK bomb protection (8KB decompressed header limit)

2. **Authentication**
   - HTTP Basic Auth (environment-configured)
   - API Key authentication (Bearer token)
   - No hardcoded credentials

3. **TLS/SSL**
   - TLS 1.2+ only (1.0/1.1 disabled)
   - Modern cipher suites only
   - ALPN for HTTP/2 negotiation

4. **OWASP Headers**
   - Strict-Transport-Security (HSTS)
   - Content-Security-Policy (CSP)
   - X-Frame-Options: DENY
   - X-Content-Type-Options: nosniff
   - Referrer-Policy
   - Permissions-Policy

5. **Rate Limiting**
   - Token bucket algorithm (configurable)
   - Per-IP tracking with LRU eviction
   - Whitelist support

6. **XSS Protection**
   - HTML entity escaping
   - CSP headers
   - Input sanitization

### Security Best Practices

**Production Checklist:**

- [ ] Set `HTTP_BASIC_AUTH` environment variable (never hardcode)
- [ ] Set `DB_PASSWORD` environment variable
- [ ] Use TLS in production (`SERVER_TLS_ENABLED=true`)
- [ ] Configure rate limiting thresholds
- [ ] Review and customize OWASP headers
- [ ] Enable structured logging
- [ ] Monitor security logs for attacks
- [ ] Keep dependencies updated
- [ ] Run security audit regularly

**Vulnerability Reporting:**

Found a security issue? Email: security@example.com

---

## Testing

### Test Coverage

- **400+ total tests** across all components
- **100% pass rate** on HTTP/2 test suite (283/283)
- **Unit tests** for all core components
- **Integration tests** for end-to-end scenarios
- **Security tests** for XSS, path traversal, etc.

### Run Tests

```bash
# Compile tests
javac -d . -cp ".:lib/*" HTTPServer/tests/*.java

# Run all tests
java -cp ".:lib/*" org.junit.platform.console.ConsoleLauncher --scan-classpath

# Run specific test class
java -cp ".:lib/*" org.junit.platform.console.ConsoleLauncher -c HTTPServer.tests.HTTP2FrameParserTest

# Run with profiling
java -cp ".:lib/*" HTTPServer.utils.ProfiledTestRunner
```

### Test Structure

```
HTTPServer/tests/
├── HTTP2FrameParserTest.java         (11 tests)
├── HTTP2HPACKTest.java                (13 tests)
├── HTTP2StreamTest.java               (14 tests)
├── HTTP2IntegrationTest.java          (16 tests)
├── HTTP2FrameEdgeCasesTest.java       (30 tests)
├── HTTP2HPACKComprehensiveTest.java   (38 tests)
├── HTTP2FlowControlTest.java          (53 tests)
├── HTTP2ConcurrentStreamsTest.java    (20 tests)
├── HTTP2ConnectionLifecycleTest.java  (35 tests)
├── HTTP2ProtocolErrorsTest.java       (33 tests)
├── HTTP2FullIntegrationTest.java      (20 tests)
├── WebSocketFrameTest.java            (25 tests)
├── WebSocketHandshakeTest.java        (19 tests)
├── WebSocketConnectionTest.java       (9 tests)
├── WebSocketSecurityTest.java         (19 tests)
├── WebSocketIntegrationTest.java      (12 tests)
├── SSEEventTest.java                  (9 tests)
├── SSEManagerTest.java                (9 tests)
├── SSEIntegrationTest.java            (5 tests)
├── SSESecurityTest.java               (5 tests)
├── TechEmpowerHandlerTest.java        (17 tests)
├── XSSProtectionTest.java             (5 tests)
└── ...
```

---

## Deployment

### Docker

```dockerfile
FROM openjdk:21-slim

WORKDIR /app

# Copy application
COPY HTTPServer/ ./HTTPServer/
COPY lib/ ./lib/

# Compile
RUN javac -d . HTTPServer/*.java

# Environment
ENV SERVER_PORT=8080
ENV HTTP_BASIC_AUTH=""
ENV DB_PASSWORD=""

# Expose ports
EXPOSE 8080 8443

# Run
CMD ["java", "HTTPServer.Servlet"]
```

**Build and run:**

```bash
docker build -t http-server .
docker run -p 8080:8080 \
  -e HTTP_BASIC_AUTH="admin:password" \
  -e SERVER_TLS_ENABLED=false \
  http-server
```

### Kubernetes

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: http-server
spec:
  replicas: 3
  selector:
    matchLabels:
      app: http-server
  template:
    metadata:
      labels:
        app: http-server
    spec:
      containers:
      - name: http-server
        image: http-server:latest
        ports:
        - containerPort: 8080
        - containerPort: 8443
        env:
        - name: HTTP_BASIC_AUTH
          valueFrom:
            secretKeyRef:
              name: http-server-secrets
              key: basic-auth
        - name: DB_PASSWORD
          valueFrom:
            secretKeyRef:
              name: http-server-secrets
              key: db-password
        livenessProbe:
          httpGet:
            path: /health
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /health/ready
            port: 8080
          initialDelaySeconds: 5
          periodSeconds: 5
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "2Gi"
            cpu: "2000m"
---
apiVersion: v1
kind: Service
metadata:
  name: http-server
spec:
  selector:
    app: http-server
  ports:
  - name: http
    port: 80
    targetPort: 8080
  - name: https
    port: 443
    targetPort: 8443
  type: LoadBalancer
```

### Production Considerations

1. **Resource Limits**
   - Virtual Threads scale well, but set reasonable limits
   - Monitor memory usage (especially buffer pools)
   - Set JVM heap size: `-Xmx2G -Xms2G`

2. **Monitoring**
   - Export Prometheus metrics: `/metrics`
   - Health checks: `/health` (liveness), `/health/ready` (readiness)
   - Structured logs to stdout (JSON format)
   - Distributed tracing with W3C Trace Context

3. **Scaling**
   - Horizontal scaling works out of the box
   - Stateless design (no session affinity needed)
   - Database connection pooling handles load

4. **Security**
   - Always use TLS in production
   - Keep secrets in environment variables or secret managers
   - Regular security audits
   - Update dependencies for patches

---

## API Documentation

### HTTP Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/` | GET | Serve static files from webroot |
| `/json` | GET | JSON serialization test |
| `/plaintext` | GET | Plaintext response |
| `/db` | GET | Single database query |
| `/queries` | GET | Multiple database queries (?queries=N) |
| `/updates` | GET | Database updates (?queries=N) |
| `/fortunes` | GET | HTML server-side rendering |
| `/health` | GET | Health check (liveness) |
| `/health/ready` | GET | Readiness check |
| `/metrics` | GET | Prometheus metrics |

### WebSocket Endpoints

| Endpoint | Protocol | Description |
|----------|----------|-------------|
| `/ws/echo` | WebSocket | Echo server |
| `/ws/chat` | WebSocket | Chat room |

### SSE Endpoints

| Endpoint | Protocol | Description |
|----------|----------|-------------|
| `/events/clock` | SSE | Server time every second |
| `/events/stocks` | SSE | Stock ticker simulation |
| `/events/notifications` | SSE | Notification stream |

### Headers

**Request Headers:**
- `Authorization`: Bearer token or Basic auth
- `Upgrade`: websocket (for WebSocket upgrade)
- `Sec-WebSocket-Key`: WebSocket handshake key
- `Accept`: text/event-stream (for SSE)
- `Accept-Encoding`: gzip, br (compression)
- `If-None-Match`: ETag for conditional requests

**Response Headers:**
- `Content-Type`: MIME type
- `Content-Encoding`: Compression algorithm
- `ETag`: Resource identifier for caching
- `Cache-Control`: Caching directives
- `Strict-Transport-Security`: HSTS header
- `Content-Security-Policy`: CSP header
- All OWASP security headers

---

## Contributing

Contributions welcome! Please follow these guidelines:

### Development Setup

```bash
# Clone
git clone https://github.com/yourusername/http_server.git
cd http_server

# Create branch
git checkout -b feature/your-feature-name

# Make changes and test
javac -d . HTTPServer/*.java HTTPServer/tests/*.java
java -cp ".:lib/*" org.junit.platform.console.ConsoleLauncher --scan-classpath

# Commit
git commit -m "Add feature: your feature description"

# Push
git push origin feature/your-feature-name
```

### Code Style

- Follow existing code conventions
- Use descriptive variable names
- Add JavaDoc for public APIs
- Write tests for new features
- Keep methods under 50 lines when possible
- Use Java 21+ features (Virtual Threads, pattern matching, etc.)

### Testing Requirements

- All tests must pass: `mvn test`
- Add unit tests for new components
- Add integration tests for new protocols
- Security tests for input validation

### Pull Request Process

1. Update README.md with new features
2. Add tests with 100% pass rate
3. Update CHANGES.md with your changes
4. Ensure no hardcoded credentials or secrets
5. Follow commit message format: "Add/Fix/Update: description"

---

## License

MIT License

Copyright (c) 2024

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

---

## Acknowledgments

- **Java 21 Project Loom** - Virtual Threads foundation
- **TechEmpower** - Performance benchmarking framework
- **RFC Authors** - HTTP/2 (7540/7541), WebSocket (6455), SSE specs
- **OWASP** - Security best practices
- **Prometheus** - Metrics format

---

**Built with ❤️ using Java 21 Virtual Threads**
