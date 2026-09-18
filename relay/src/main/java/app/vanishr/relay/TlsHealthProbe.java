package app.vanishr.relay;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.nio.file.*;
import java.net.URI;
import java.net.http.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;

public final class TlsHealthProbe {
    public static void main(String[] arguments) {
        try {
            KeyStore trusted = KeyStore.getInstance(KeyStore.getDefaultType());
            trusted.load(null);
            try (var input = Files.newInputStream(Path.of("/tls/ca.crt"))) {
                trusted.setCertificateEntry("internal-ca", CertificateFactory.getInstance("X.509").generateCertificate(input));
            }
            TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            managers.init(trusted);
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null, managers.getTrustManagers(), null);
            try (HttpClient client = HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(3)).build()) {
                HttpResponse<Void> response = client.send(HttpRequest.newBuilder(URI.create("https://localhost:8443/health"))
                        .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() != 200) System.exit(1);
            }
        } catch (Exception failure) { System.exit(1); }
    }
}