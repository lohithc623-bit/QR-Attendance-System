package com.attendance.api;

import com.attendance.config.AppConfig;
import com.attendance.db.DatabaseConnection;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

/**
 * Starts the REST API + mobile web app.
 *   java -cp target/qr-attendance-system-1.0.0.jar com.attendance.api.ServerMain
 * HTTPS is switched on when api.tls.keystore is set. Phone browsers only allow camera access on
 * HTTPS (or localhost), so use TLS for anything other than testing on the same machine.
 */
public final class ServerMain {

    private ServerMain() {
    }

    public static void main(String[] args) throws Exception {
        String problem = DatabaseConnection.describeProblem();
        if (problem != null) {
            System.err.println("Cannot start: " + problem);
            System.exit(1);
        }

        int port = AppConfig.getInt("api.port", 8080);
        String keystore = AppConfig.get("api.tls.keystore", "");
        HttpServer server;
        String scheme;

        if (keystore.isBlank()) {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            scheme = "http";
        } else {
            char[] password = AppConfig.get("api.tls.password", "").toCharArray();
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(Path.of(keystore))) {
                ks.load(in, password);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, password);
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(kmf.getKeyManagers(), null, null);
            HttpsServer https = HttpsServer.create(new InetSocketAddress(port), 0);
            https.setHttpsConfigurator(new HttpsConfigurator(ssl));
            server = https;
            scheme = "https";
        }

        new ApiServer().attachTo(server);
        server.start();
        System.out.println("QR Attendance server running at " + scheme + "://localhost:" + port + "/");
        if (scheme.equals("http")) {
            System.out.println("NOTE: plain HTTP - phone cameras only work on localhost. Enable HTTPS (see README).");
        }
    }
}
