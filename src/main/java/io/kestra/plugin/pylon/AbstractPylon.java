package io.kestra.plugin.pylon;

import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.http.client.configurations.TimeoutConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;

/**
 * Connection base class for every Pylon task. A {@link io.kestra.core.models.triggers.AbstractTrigger}
 * cannot extend {@code Task}, so {@code io.kestra.plugin.pylon.issue.Trigger} redeclares
 * {@link #apiToken}/{@link #baseUrl} — kept in lockstep via the shared title/description constants
 * below. The HTTP call logic itself lives once in {@link PylonClient}, built by {@link #client}.
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractPylon extends Task {

    public static final String DEFAULT_BASE_URL = "https://api.usepylon.com";

    public static final String API_TOKEN_TITLE = "Pylon API token";
    public static final String API_TOKEN_DESCRIPTION = """
        API token created by a Pylon Admin user (Pylon Settings > API), sent as \
        `Authorization: Bearer <token>` on every request. Store it as a Kestra secret and reference \
        it with `{{ secret('PYLON_API_TOKEN') }}` — never hardcode it in a flow.""";

    public static final String BASE_URL_TITLE = "Pylon API base URL";
    public static final String BASE_URL_DESCRIPTION = """
        Base URL of the Pylon API. Defaults to the US region (`https://api.usepylon.com`); override \
        with `https://api.eu.usepylon.com` for the EU region.""";

    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration HTTP_READ_IDLE_TIMEOUT = Duration.ofSeconds(30);

    @Schema(title = API_TOKEN_TITLE, description = API_TOKEN_DESCRIPTION)
    @NotNull
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    protected Property<String> apiToken;

    @Schema(title = BASE_URL_TITLE, description = BASE_URL_DESCRIPTION)
    @Builder.Default
    @PluginProperty(group = "connection")
    protected Property<String> baseUrl = Property.ofValue(DEFAULT_BASE_URL);

    protected PylonClient client(RunContext runContext) throws Exception {
        var rApiToken = runContext.render(this.apiToken).as(String.class).orElse(null);
        if (rApiToken == null || rApiToken.isBlank()) {
            throw new IllegalArgumentException(
                "Pylon 'apiToken' is required — create an API token as a Pylon Admin user (Settings > API) and store it as a Kestra secret."
            );
        }
        var rBaseUrl = runContext.render(this.baseUrl).as(String.class).orElse(DEFAULT_BASE_URL);

        var httpClient = HttpClient.builder()
            .runContext(runContext)
            .configuration(HttpConfiguration.builder()
                .timeout(TimeoutConfiguration.builder()
                    .connectTimeout(Property.ofValue(HTTP_CONNECT_TIMEOUT))
                    .readIdleTimeout(Property.ofValue(HTTP_READ_IDLE_TIMEOUT))
                    .build())
                .build())
            .build();

        return new PylonClient(runContext, httpClient, rBaseUrl, rApiToken);
    }
}
