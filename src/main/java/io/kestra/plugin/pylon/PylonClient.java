package io.kestra.plugin.pylon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.http.client.configurations.TimeoutConfiguration;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Closeable;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Wraps a Kestra {@link HttpClient} with Pylon's bearer-token auth, `data`-envelope unwrapping,
 * cursor pagination over {@code /issues}, and non-2xx error mapping. Shared by every task (via
 * {@link AbstractPylon}) and the polling trigger (which cannot extend {@code Task} and therefore
 * builds one directly) so the HTTP/error-handling logic lives in exactly one place.
 */
public final class PylonClient implements Closeable {
    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();
    private static final int MAX_RATE_LIMIT_RETRIES = 3;
    private static final long DEFAULT_RETRY_AFTER_SECONDS = 2;
    /** Upper bound on a server-supplied `Retry-After`, so an extreme value can't pin a worker thread indefinitely. */
    private static final long MAX_RETRY_AFTER_SECONDS = 60;
    /** Safety cap on `/issues` cursor pagination so a `has_next_page=true` response with a stuck cursor can't loop forever. */
    private static final int MAX_PAGES = 1000;
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration HTTP_READ_IDLE_TIMEOUT = Duration.ofSeconds(30);

    private final RunContext runContext;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiToken;

    public PylonClient(RunContext runContext, HttpClient httpClient, String baseUrl, String apiToken) {
        this.runContext = runContext;
        this.httpClient = httpClient;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiToken = apiToken;
    }

    /**
     * Builds a client with the plugin's shared HTTP timeout configuration. The single place where
     * an {@link HttpClient} is constructed, so tasks (via {@link AbstractPylon#client}) and the
     * polling trigger cannot drift apart when the timeout tuning changes.
     */
    public static PylonClient connect(RunContext runContext, String baseUrl, String apiToken) throws IllegalVariableEvaluationException {
        var httpClient = HttpClient.builder()
            .runContext(runContext)
            .configuration(HttpConfiguration.builder()
                .timeout(TimeoutConfiguration.builder()
                    .connectTimeout(Property.ofValue(HTTP_CONNECT_TIMEOUT))
                    .readIdleTimeout(Property.ofValue(HTTP_READ_IDLE_TIMEOUT))
                    .build())
                .build())
            .build();

        return new PylonClient(runContext, httpClient, baseUrl, apiToken);
    }

    public Map<String, Object> getForData(String path, String action) throws Exception {
        return unwrapData(raw("GET", path, null, null, action).getBody(), action);
    }

    public Map<String, Object> postForData(String path, Object jsonBody, String action) throws Exception {
        return unwrapData(raw("POST", path, null, jsonBody, action).getBody(), action);
    }

    public Map<String, Object> patchForData(String path, Object jsonBody, String action) throws Exception {
        return unwrapData(raw("PATCH", path, null, jsonBody, action).getBody(), action);
    }

    /**
     * All issues in `[startTime, endTime]`, walking every cursor page. Buffers the full result in
     * memory — use {@link #walkIssues} instead for a dataset that must not be held whole.
     */
    public List<Map<String, Object>> listAllIssues(Instant startTime, Instant endTime, int limit, String action) throws Exception {
        var all = new ArrayList<Map<String, Object>>();
        walkIssues(startTime, endTime, limit, action, all::addAll);
        return all;
    }

    /** Streams each `/issues` page to {@code consumer} instead of buffering the whole result set. */
    public void walkIssues(Instant startTime, Instant endTime, int limit, String action, Consumer<List<Map<String, Object>>> consumer) throws Exception {
        String cursor = null;
        for (var page = 0; page < MAX_PAGES; page++) {
            var result = listPage("/issues", issuesQuery(startTime, endTime, limit, cursor), action);
            consumer.accept(result.data());
            if (!result.hasNextPage() || result.cursor() == null || result.cursor().equals(cursor)) {
                return;
            }
            cursor = result.cursor();
        }
        runContext.logger().warn(
            "Pylon issue pagination stopped after {} pages (safety cap) while trying to {} — narrow the time range if more issues are expected.",
            MAX_PAGES, action
        );
    }

    /** A single page of `/issues`, without walking subsequent cursors. */
    public ListPage firstIssuesPage(Instant startTime, Instant endTime, int limit, String action) throws Exception {
        return listPage("/issues", issuesQuery(startTime, endTime, limit, null), action);
    }

    @SuppressWarnings("unchecked")
    private ListPage listPage(String path, Map<String, Object> query, String action) throws Exception {
        var body = raw("GET", path, query, null, action).getBody();
        if (body == null || body.isBlank()) {
            return new ListPage(List.of(), null, false);
        }
        Map<String, Object> envelope = MAPPER.readValue(body, new TypeReference<Map<String, Object>>() {});
        var data = envelope.get("data");
        var rows = data instanceof List<?> list ? (List<Map<String, Object>>) (List<?>) list : List.<Map<String, Object>>of();
        var pagination = envelope.get("pagination") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.<String, Object>of();
        var cursor = (String) pagination.get("cursor");
        var hasNextPage = Boolean.TRUE.equals(pagination.get("has_next_page"));
        return new ListPage(rows, cursor, hasNextPage);
    }

    private Map<String, Object> issuesQuery(Instant startTime, Instant endTime, int limit, String cursor) {
        var query = new LinkedHashMap<String, Object>();
        query.put("start_time", startTime.toString());
        query.put("end_time", endTime.toString());
        query.put("limit", limit);
        if (cursor != null) {
            query.put("cursor", cursor);
        }
        return query;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unwrapData(String body, String action) throws Exception {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("Empty response from Pylon API while trying to " + action + ".");
        }
        Map<String, Object> envelope = MAPPER.readValue(body, new TypeReference<Map<String, Object>>() {});
        if (!(envelope.get("data") instanceof Map<?, ?> data)) {
            throw new IllegalStateException("Unexpected Pylon API response while trying to " + action + " — missing 'data' object.");
        }
        return (Map<String, Object>) data;
    }

    /**
     * Converts a user-facing `{slug: value}` map into Pylon's wire format — an array of
     * `{slug, value}` (single-valued) or `{slug, values}` (multi-valued, e.g. multiselect) objects.
     */
    public static List<Map<String, Object>> customFieldsToArray(Map<String, Object> customFields) {
        return customFields.entrySet().stream()
            .map(entry -> {
                Map<String, Object> field = new LinkedHashMap<>();
                field.put("slug", entry.getKey());
                if (entry.getValue() instanceof List<?> values) {
                    field.put("values", values);
                } else {
                    field.put("value", entry.getValue());
                }
                return field;
            })
            .toList();
    }

    private HttpResponse<String> raw(String method, String path, Map<String, Object> query, Object jsonBody, String action) throws Exception {
        return raw(method, path, query, jsonBody, action, 0);
    }

    private HttpResponse<String> raw(String method, String path, Map<String, Object> query, Object jsonBody, String action, int rateLimitRetries) throws Exception {
        var builder = HttpRequest.builder()
            .method(method)
            .uri(buildUri(path, query))
            .addHeader("Authorization", "Bearer " + apiToken)
            .addHeader("Accept", "application/json");

        if (jsonBody != null) {
            builder
                .addHeader("Content-Type", "application/json")
                .body(HttpRequest.JsonRequestBody.builder().content(jsonBody).build());
        }

        try {
            return httpClient.request(builder.build(), String.class);
        } catch (HttpClientResponseException e) {
            var status = e.getResponse().getStatus().getCode();

            if (status == 429 && rateLimitRetries < MAX_RATE_LIMIT_RETRIES) {
                var wait = retryAfterSeconds(e);
                runContext.logger().warn(
                    "Pylon API rate limit hit while trying to {} — retrying in {}s (attempt {}/{}).",
                    action, wait, rateLimitRetries + 1, MAX_RATE_LIMIT_RETRIES
                );
                Thread.sleep(wait * 1000L);
                return raw(method, path, query, jsonBody, action, rateLimitRetries + 1);
            }

            throw mapError(e, status, action);
        }
    }

    private long retryAfterSeconds(HttpClientResponseException e) {
        try {
            var seconds = Long.parseLong(e.getResponse().getHeaders().firstValue("Retry-After").orElse(String.valueOf(DEFAULT_RETRY_AFTER_SECONDS)));
            return Math.min(Math.max(seconds, 1), MAX_RETRY_AFTER_SECONDS);
        } catch (NumberFormatException nfe) {
            return DEFAULT_RETRY_AFTER_SECONDS;
        }
    }

    private IllegalStateException mapError(HttpClientResponseException e, int status, String action) {
        var message = errorMessage(rawBody(e));

        var hint = switch (status) {
            case 401, 403 ->
                " Check that 'apiToken' is a valid, non-revoked API token created by a Pylon Admin user.";
            case 429 -> " Pylon rate limit exceeded — reduce call frequency or the trigger's polling interval.";
            default -> "";
        };

        return new IllegalStateException("Failed to " + action + ": HTTP " + status + " - " + message + hint, e);
    }

    private String rawBody(HttpClientResponseException e) {
        var body = e.getResponse().getBody();
        return switch (body) {
            case null -> "";
            case byte[] bytes -> new String(bytes, StandardCharsets.UTF_8);
            default -> String.valueOf(body);
        };
    }

    private String errorMessage(String body) {
        if (!body.isBlank()) {
            try {
                var envelope = MAPPER.readValue(body, ErrorEnvelope.class);
                if (envelope.getErrors() != null && !envelope.getErrors().isEmpty()) {
                    return String.join("; ", envelope.getErrors());
                }
            } catch (Exception parseFailure) {
                // Body wasn't a JSON error envelope; fall through to using the raw body below.
            }
        }
        return body.isBlank() ? "empty response from Pylon API" : body;
    }

    private URI buildUri(String path, Map<String, Object> query) {
        var resolvedPath = path.startsWith("/") ? path : "/" + path;
        var params = new LinkedHashMap<String, Object>();
        if (query != null) {
            query.forEach((key, value) -> {
                if (value != null) {
                    params.put(key, value);
                }
            });
        }
        return URI.create(baseUrl + resolvedPath + buildQueryString(params));
    }

    private String buildQueryString(Map<String, Object> params) {
        if (params.isEmpty()) {
            return "";
        }
        var parts = new ArrayList<String>();
        params.forEach((key, value) -> parts.add(encode(key) + "=" + encode(String.valueOf(value))));
        return "?" + String.join("&", parts);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Encodes a single path segment (e.g. an issue ID interpolated into `/issues/{id}`). */
    public static String encodePathSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Override
    public void close() {
        try {
            httpClient.close();
        } catch (Exception e) {
            runContext.logger().warn("Failed to close Pylon HTTP client: {}", e.getMessage());
        }
    }

    public record ListPage(List<Map<String, Object>> data, String cursor, boolean hasNextPage) {
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ErrorEnvelope {
        private List<String> errors;
        @JsonProperty("exists_id")
        private String existsId;
        @JsonProperty("request_id")
        private String requestId;
    }
}
