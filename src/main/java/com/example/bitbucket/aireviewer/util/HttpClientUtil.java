package com.example.bitbucket.aireviewer.util;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Named;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * HTTP client utility for communicating with Ollama API.
 *
 * Provides methods for making HTTP requests with:
 * - Configurable timeouts
 * - Retry logic with exponential backoff
 * - JSON request/response handling
 * - Circuit breaker integration
 * - Rate limiting
 *
 * Thread-safe implementation.
 */
@Named
public class HttpClientUtil {

    private static final Logger log = LoggerFactory.getLogger(HttpClientUtil.class);
    private static final Gson gson = new Gson();

    private final int connectTimeout;
    private final int readTimeout;
    private final int maxRetries;
    private final int baseRetryDelayMs;
    private final CircuitBreaker circuitBreaker;
    private final RateLimiter rateLimiter;

    /**
     * Creates a new HTTP client utility.
     *
     * @param connectTimeout connection timeout in milliseconds
     * @param readTimeout read timeout in milliseconds
     * @param maxRetries maximum number of retry attempts
     * @param baseRetryDelayMs base delay for exponential backoff
     * @param apiDelayMs minimum delay between API calls (for rate limiting)
     */
    public HttpClientUtil(
            int connectTimeout,
            int readTimeout,
            int maxRetries,
            int baseRetryDelayMs,
            int apiDelayMs) {
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.maxRetries = maxRetries;
        this.baseRetryDelayMs = baseRetryDelayMs;
        this.circuitBreaker = new CircuitBreaker("ollama-api", 5, Duration.ofMinutes(1));
        this.rateLimiter = new RateLimiter("ollama-api", 10, Duration.ofSeconds(1));
    }

    /**
     * Sends a POST request with JSON payload and returns JSON response.
     *
     * @param url the URL to send the request to
     * @param requestBody the request body as a map
     * @return the response body as a JsonObject
     * @throws IOException if the request fails
     */
    @Nonnull
    public JsonObject postJson(@Nonnull String url, @Nonnull Map<String, Object> requestBody) throws IOException {
        return postJson(url, requestBody, maxRetries);
    }

    /**
     * Sends a POST request with JSON payload and returns JSON response.
     *
     * @param url the URL to send the request to
     * @param requestBody the request body as a map
     * @param retries number of retries to attempt
     * @return the response body as a JsonObject
     * @throws IOException if the request fails after all retries
     */
    @Nonnull
    public JsonObject postJson(@Nonnull String url, @Nonnull Map<String, Object> requestBody, int retries)
            throws IOException {
        Objects.requireNonNull(url, "url cannot be null");
        Objects.requireNonNull(requestBody, "requestBody cannot be null");

        IOException lastException = null;

        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                // Apply rate limiting
                rateLimiter.acquire();

                // Execute with circuit breaker protection
                return circuitBreaker.execute(() -> doPostJson(url, requestBody));

            } catch (CircuitBreaker.CircuitBreakerOpenException e) {
                log.error("Circuit breaker is open, refusing request to: {}", url);
                throw new IOException("Service unavailable (circuit breaker open)", e);

            } catch (IOException e) {
                lastException = e;
                log.warn("HTTP request failed (attempt {}/{}): {}", attempt + 1, retries + 1, e.getMessage());

                if (attempt < retries) {
                    int delayMs = baseRetryDelayMs * (int) Math.pow(2, attempt);
                    log.info("Retrying in {}ms...", delayMs);
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted during retry delay", ie);
                    }
                }

            } catch (Exception e) {
                throw new IOException("Unexpected error during HTTP request", e);
            }
        }

        throw Objects.requireNonNull(lastException, "lastException should not be null");
    }

    /**
     * Performs the actual HTTP POST request.
     *
     * @param urlString the URL
     * @param requestBody the request body
     * @return the response as JsonObject
     * @throws IOException if the request fails
     */
    @Nonnull
    private JsonObject doPostJson(@Nonnull String urlString, @Nonnull Map<String, Object> requestBody)
            throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            // Configure connection
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);
            conn.setDoOutput(true);

            // Send request body
            String jsonRequest = gson.toJson(requestBody);
            log.debug("POST {} - Request: {}", urlString, truncate(jsonRequest, 500));

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonRequest.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            // Check response code
            int responseCode = conn.getResponseCode();
            log.debug("POST {} - Response code: {}", urlString, responseCode);

            if (responseCode < 200 || responseCode >= 300) {
                String errorBody = readErrorStream(conn);
                throw new IOException(String.format("HTTP %d: %s", responseCode, errorBody));
            }

            // Read response
            String responseBody = readInputStream(conn);
            log.debug("POST {} - Response: {}", urlString, truncate(responseBody, 500));

            return gson.fromJson(responseBody, JsonObject.class);

        } finally {
            conn.disconnect();
        }
    }

    /**
     * Tests connectivity to an Ollama endpoint.
     *
     * @param baseUrl the Ollama base URL (e.g., "http://localhost:11434")
     * @return true if connection successful, false otherwise
     */
    public boolean testConnection(@Nonnull String baseUrl) {
        try {
            String url = baseUrl.endsWith("/") ? baseUrl + "api/tags" : baseUrl + "/api/tags";
            URL testUrl = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) testUrl.openConnection();

            try {
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000); // 5 second timeout for test
                conn.setReadTimeout(5000);

                int responseCode = conn.getResponseCode();
                log.info("Ollama connection test - Response code: {}", responseCode);

                return responseCode >= 200 && responseCode < 300;

            } finally {
                conn.disconnect();
            }

        } catch (Exception e) {
            log.warn("Ollama connection test failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Reads the response body from the connection.
     *
     * @param conn the HTTP connection
     * @return the response body as a string
     * @throws IOException if reading fails
     */
    @Nonnull
    private String readInputStream(@Nonnull HttpURLConnection conn) throws IOException {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        }
    }

    /**
     * Reads the error stream from the connection.
     *
     * @param conn the HTTP connection
     * @return the error body as a string
     */
    @Nonnull
    private String readErrorStream(@Nonnull HttpURLConnection conn) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        } catch (Exception e) {
            return "Unable to read error stream: " + e.getMessage();
        }
    }

    /**
     * Truncates a string for logging.
     *
     * @param str the string to truncate
     * @param maxLength maximum length
     * @return truncated string
     */
    @Nonnull
    private String truncate(@Nullable String str, int maxLength) {
        if (str == null) {
            return "";
        }
        if (str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength) + "... (truncated)";
    }

    /**
     * Gets the circuit breaker instance.
     *
     * @return the circuit breaker
     */
    @Nonnull
    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    /**
     * Gets the rate limiter instance.
     *
     * @return the rate limiter
     */
    @Nonnull
    public RateLimiter getRateLimiter() {
        return rateLimiter;
    }
}
