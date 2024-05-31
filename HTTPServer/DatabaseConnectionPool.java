package HTTPServer;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;
import java.util.logging.Level;

public class DatabaseConnectionPool {
    private static final Logger logger = Logger.getLogger(DatabaseConnectionPool.class.getName());
    private static DatabaseConnectionPool instance;
    private final HikariDataSource dataSource;

    private DatabaseConnectionPool(String dbUrl, String dbUser, String dbPassword, int maxPoolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(dbUrl);
        config.setUsername(dbUser);
        config.setPassword(dbPassword);
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(5);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        config.setAutoCommit(true);
        config.setPoolName("BenchmarkPool");
        config.setLeakDetectionThreshold(60000);

        this.dataSource = new HikariDataSource(config);
        logger.info("DatabaseConnectionPool initialized with maxPoolSize=" + maxPoolSize);
    }

    public static synchronized void initialize(String dbUrl, String dbUser, String dbPassword, int maxPoolSize) {
        if (instance == null) {
            instance = new DatabaseConnectionPool(dbUrl, dbUser, dbPassword, maxPoolSize);
        }
    }

    public static DatabaseConnectionPool getInstance() {
        if (instance == null) {
            String dbUrl = System.getenv("DB_URL");
            String dbUser = System.getenv("DB_USER");
            String dbPassword = System.getenv("DB_PASSWORD");
            int maxPoolSize = 200;

            if (dbUrl == null) {
                dbUrl = "jdbc:postgresql://localhost:5432/benchmarkdb";
            }
            if (dbUser == null) {
                dbUser = "benchmarkdbuser";
            }
            if (dbPassword == null || dbPassword.isEmpty()) {
                throw new IllegalStateException("DB_PASSWORD environment variable must be set");
            }

            initialize(dbUrl, dbUser, dbPassword, maxPoolSize);
        }
        return instance;
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("DatabaseConnectionPool closed");
        }
    }

    public HikariDataSource getDataSource() {
        return dataSource;
    }
}
