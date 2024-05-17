FROM openjdk:21-slim

# Set working directory
WORKDIR /app

# Create necessary directories
RUN mkdir -p /app/webroot /app/logs /app/config

# Copy compiled Java classes
COPY HTTPServer/*.class ./HTTPServer/

# Copy main class files
COPY HTTPServer/Servlet.class .
COPY HTTPServer/HTTPServer.class .

# Copy configuration files (if they exist)
COPY config.properties* . 2>/dev/null || true

# Copy web content
COPY webroot/ ./webroot/ 2>/dev/null || true

# Create non-root user for security
RUN useradd -m -u 1000 appuser && chown -R appuser:appuser /app
USER appuser

# Health check for Kubernetes/Docker
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
  CMD java -version || exit 1

# Expose HTTP port
EXPOSE 8080

# Production JVM settings
ENV JAVA_OPTS="-server \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=50 \
  -Xms2g \
  -Xmx2g \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/app/logs/heapdump.hprof \
  -XX:+PrintGCDetails \
  -XX:+PrintGCTimeStamps \
  -XX:+PrintGCDateStamps \
  -Xloggc:/app/logs/gc.log"

# Run HTTP server
# Default: listens on 0.0.0.0:8080, serves from /app/webroot
CMD ["sh", "-c", "java $JAVA_OPTS HTTPServer.Servlet"]
