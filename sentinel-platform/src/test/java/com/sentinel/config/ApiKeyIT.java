package com.sentinel.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinel.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * The fence around {@code /api/v1}, and the two things that must be outside it.
 *
 * <p>The base class attaches the key to {@code TestRestTemplate}, so these tests build their own
 * requests to control the header precisely.
 */
class ApiKeyIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Value("${sentinel.api-key}")
    private String apiKey;

    @Test
    @DisplayName("the API rejects a request with no key")
    void missingKeyIsUnauthorized() {
        assertThat(get("/api/v1/incidents", null)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("the API rejects a wrong key, including one that is a prefix of the real one")
    void wrongKeyIsUnauthorized() {
        assertThat(get("/api/v1/incidents", "nonsense")).isEqualTo(HttpStatus.UNAUTHORIZED);
        // A prefix is the case a short-circuiting comparison would leak timing on. It must be
        // rejected exactly like any other wrong value.
        assertThat(get("/api/v1/incidents", apiKey.substring(0, apiKey.length() - 1)))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/v1/incidents", apiKey + "x")).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("the API accepts the configured key")
    void correctKeyIsAccepted() {
        assertThat(get("/api/v1/incidents", apiKey)).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Actuator stays open — Prometheus scrapes it and Kubernetes probes it")
    void actuatorIsNotBehindTheKey() {
        assertThat(get("/actuator/health", null)).isEqualTo(HttpStatus.OK);
        assertThat(get("/actuator/prometheus", null)).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("the OpenAPI document stays open, so Swagger UI can load it")
    void openApiDocumentIsNotBehindTheKey() {
        assertThat(get("/v3/api-docs", null)).isEqualTo(HttpStatus.OK);
    }

    /**
     * A client with no interceptors, so "no key" genuinely means no header.
     *
     * <p>The autowired {@code TestRestTemplate} has the base class's API-key interceptor attached
     * and would quietly authenticate the very requests these tests need to arrive unauthenticated.
     */
    private final TestRestTemplate anonymous = new TestRestTemplate();

    private HttpStatusCode get(String path, String key) {
        var headers = new HttpHeaders();
        if (key != null) {
            headers.add(ApiKeyFilter.HEADER, key);
        }
        return anonymous
                .exchange(rest.getRootUri() + path, HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getStatusCode();
    }
}
