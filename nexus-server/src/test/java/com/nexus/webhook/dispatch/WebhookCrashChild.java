package com.nexus.webhook.dispatch;

import com.nexus.NexusServerApplication;
import com.nexus.auth.config.JwtProperties;
import java.time.Duration;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.sql.DriverManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Separate-process entry point for the crash acceptance test, never production configuration. */
public final class WebhookCrashChild {
    public static void main(String[] args) throws Exception {
        try (var c = DriverManager.getConnection(System.getenv("SPRING_DATASOURCE_URL"),
                System.getenv("SPRING_DATASOURCE_USERNAME"), System.getenv("SPRING_DATASOURCE_PASSWORD"));
             var rs = c.createStatement().executeQuery("SELECT DATABASE(), CURRENT_USER(), VERSION()")) {
            if (!rs.next()) throw new IllegalStateException("Missing crash acceptance database identity");
            WebhookJvmCrashIntegrationTests.requireAcceptanceDatabase(rs.getString(1), rs.getString(2), rs.getString(3));
        }
        SpringApplication.run(new Class<?>[]{NexusServerApplication.class, LocalTls.class}, args);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LocalTls {
        @Bean
        ApplicationRunner verifyChildJwtBinding(JwtProperties properties) {
            return args -> {
                if (!Duration.ofMinutes(15).equals(properties.accessTokenTtl()))
                    throw new IllegalStateException("Crash child JWT accessTokenTtl did not bind to 15m");
                System.out.println("Crash child JWT accessTokenTtl binding verified: 15m");
            };
        }

        @Bean(destroyMethod = "close") @Primary
        WebhookHttpClient localWebhookClient() throws Exception {
            var store = KeyStore.getInstance("PKCS12");
            try (var in = Files.newInputStream(Path.of(System.getenv("NEXUS_CRASH_TEST_KEYSTORE")))) {
                store.load(in, "testpass".toCharArray());
            }
            var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tm.init(store);
            var tls = SSLContext.getInstance("TLS");
            tls.init(null, tm.getTrustManagers(), null);
            return new WebhookHttpClient(host -> new InetAddress[]{InetAddress.getLoopbackAddress()}, tls, true);
        }
    }
}
