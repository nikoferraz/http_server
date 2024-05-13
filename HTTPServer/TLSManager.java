package HTTPServer;

import javax.net.ssl.*;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.*;
import java.security.cert.CertificateException;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.lang.reflect.Method;

/**
 * Manages TLS/SSL configuration and server socket creation.
 * Supports TLS 1.2 and TLS 1.3 with secure cipher suites.
 */
public class TLSManager {

    private static final Logger logger = Logger.getLogger(TLSManager.class.getName());

    // Recommended cipher suites for TLS 1.2 and 1.3 (2025 standards)
    private static final String[] PREFERRED_CIPHER_SUITES = {
        // TLS 1.3 cipher suites
        "TLS_AES_256_GCM_SHA384",
        "TLS_AES_128_GCM_SHA256",
        "TLS_CHACHA20_POLY1305_SHA256",
        // TLS 1.2 cipher suites (avoiding CBC-based ciphers)
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"
    };

    // Enable TLS 1.2 and 1.3 only (disable older versions)
    private static final String[] ENABLED_PROTOCOLS = {
        "TLSv1.3",
        "TLSv1.2"
    };

    private ServerConfig config;
    private SSLContext sslContext;
    private boolean alpnSupported;

    public TLSManager(ServerConfig config) {
        this.config = config;
        this.alpnSupported = checkALPNSupport();
    }

    private boolean checkALPNSupport() {
        try {
            SSLSocket testSocket = null;
            return testSocket != null || testSocketClassHasALPN();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean testSocketClassHasALPN() {
        try {
            SSLSocket.class.getMethod("getApplicationProtocol");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * Initializes the SSL context with the configured keystore.
     * @throws Exception if SSL context initialization fails
     */
    public void initialize() throws Exception {
        if (!config.isTlsEnabled()) {
            logger.info("TLS is disabled in configuration");
            return;
        }

        logger.info("Initializing TLS/SSL context...");

        // Load the keystore
        KeyStore keyStore = KeyStore.getInstance("JKS");
        try (FileInputStream keyStoreStream = new FileInputStream(config.getKeystorePath())) {
            keyStore.load(keyStoreStream, config.getKeystorePassword().toCharArray());
        }

        // Initialize KeyManagerFactory
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, config.getKeyPassword().toCharArray());

        // Initialize TrustManagerFactory (optional, for client authentication)
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(keyStore);

        // Create SSL context
        sslContext = SSLContext.getInstance("TLS");
        sslContext.init(
            keyManagerFactory.getKeyManagers(),
            trustManagerFactory.getTrustManagers(),
            new SecureRandom()
        );

        logger.info("TLS/SSL context initialized successfully");
    }

    /**
     * Creates an SSLServerSocket with secure configuration and ALPN support.
     * @param port The port to bind to
     * @param backlog The connection backlog
     * @return Configured SSLServerSocket
     * @throws IOException if socket creation fails
     */
    public SSLServerSocket createSSLServerSocket(int port, int backlog) throws IOException {
        if (sslContext == null) {
            throw new IllegalStateException("SSL context not initialized. Call initialize() first.");
        }

        SSLServerSocketFactory socketFactory = sslContext.getServerSocketFactory();
        SSLServerSocket serverSocket = (SSLServerSocket) socketFactory.createServerSocket(port, backlog);

        // Configure protocols (TLS 1.2 and 1.3 only)
        serverSocket.setEnabledProtocols(ENABLED_PROTOCOLS);

        // Configure cipher suites
        configureCipherSuites(serverSocket);

        // Configure ALPN if supported
        configureALPN(serverSocket);

        logger.info("SSLServerSocket created on port " + port + " with TLS 1.2/1.3" +
                   (alpnSupported ? " and ALPN support" : ""));
        return serverSocket;
    }

    private void configureALPN(SSLServerSocket serverSocket) {
        if (!alpnSupported) {
            logger.fine("ALPN not supported on this JVM");
            return;
        }

        try {
            Method setApplicationProtocolsMethod = SSLSocket.class.getDeclaredMethod(
                "setApplicationProtocols", String[].class
            );
            setApplicationProtocolsMethod.setAccessible(true);

            String[] protocols = {"h2", "http/1.1"};
            setApplicationProtocolsMethod.invoke(serverSocket, (Object) protocols);

            logger.info("ALPN configured with protocols: h2, http/1.1");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to configure ALPN", e);
        }
    }

    /**
     * Configures secure cipher suites, filtering to only use available and preferred ciphers.
     */
    private void configureCipherSuites(SSLServerSocket serverSocket) {
        String[] supportedCiphers = serverSocket.getSupportedCipherSuites();

        // Filter to only include preferred ciphers that are supported
        java.util.List<String> enabledCiphers = new java.util.ArrayList<>();
        for (String preferredCipher : PREFERRED_CIPHER_SUITES) {
            for (String supportedCipher : supportedCiphers) {
                if (supportedCipher.equals(preferredCipher)) {
                    enabledCiphers.add(preferredCipher);
                    break;
                }
            }
        }

        if (enabledCiphers.isEmpty()) {
            logger.warning("No preferred cipher suites are supported. Using default cipher suites.");
        } else {
            serverSocket.setEnabledCipherSuites(enabledCiphers.toArray(new String[0]));
            logger.info("Enabled " + enabledCiphers.size() + " secure cipher suites");
        }
    }

    /**
     * Returns true if TLS is enabled and initialized.
     */
    public boolean isEnabled() {
        return config.isTlsEnabled() && sslContext != null;
    }

    /**
     * Gets the SSL context (for testing or advanced configuration).
     */
    public SSLContext getSSLContext() {
        return sslContext;
    }

    /**
     * Returns true if ALPN is supported on this JVM.
     */
    public boolean isALPNSupported() {
        return alpnSupported;
    }

    /**
     * Gets the negotiated ALPN protocol for a given socket.
     */
    public String getApplicationProtocol(SSLSocket socket) {
        if (!alpnSupported) {
            return null;
        }

        try {
            Method getApplicationProtocolMethod = SSLSocket.class.getDeclaredMethod("getApplicationProtocol");
            getApplicationProtocolMethod.setAccessible(true);
            return (String) getApplicationProtocolMethod.invoke(socket);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to get application protocol", e);
            return null;
        }
    }
}
