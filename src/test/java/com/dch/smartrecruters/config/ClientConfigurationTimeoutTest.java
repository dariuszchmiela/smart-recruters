package com.dch.smartrecruters.config;

import com.dch.smartrecruters.client.ExternalCallExecutor;
import com.dch.smartrecruters.client.ExternalSystemException;
import com.dch.smartrecruters.client.FailureType;
import com.dch.smartrecruters.client.SapClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Uses a real loopback HTTP server, so the configured read timeout is actually exercised.
 */
class ClientConfigurationTimeoutTest {

    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;

    @BeforeEach
    void startSlowServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void shouldTimeOutAndRetryAsTransientFailure() {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        ClientProperties properties = new ClientProperties(
                new ClientProperties.Endpoint(baseUrl, Duration.ofMillis(500), Duration.ofMillis(100)),
                new ClientProperties.Endpoint(baseUrl, Duration.ofMillis(500), Duration.ofMillis(100)),
                new ClientProperties.Retry(2, Duration.ofMillis(1), 2.0, Duration.ofMillis(5))
        );
        ClientConfiguration configuration = new ClientConfiguration();
        ExternalCallExecutor executor = configuration.externalCallExecutor(properties);

        SapClient sapClient = configuration.sapClient(
                RestClient.builder(),
                ClientHttpRequestFactoryBuilder.detect(),
                properties,
                executor
        );

        ExternalSystemException exception = assertThrows(
                ExternalSystemException.class,
                () -> sapClient.getCandidate("tenant-1", "candidate-1")
        );

        assertEquals(FailureType.TRANSIENT, exception.failureType());
        assertEquals(2, requests.get());
    }
}
