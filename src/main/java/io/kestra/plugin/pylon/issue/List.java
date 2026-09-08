package io.kestra.plugin.pylon.issue;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.pylon.AbstractPylon;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "List Pylon issues",
    description = """
        Calls `GET /issues`, which requires a bounded time range (max 365 days) and returns issues \
        page by page via cursor pagination. Defaults to the last 24 hours if `startTime`/`endTime` \
        are not set."""
)
@Plugin(
    examples = {
        @Example(
            title = "List issues updated in the last 24 hours",
            full = true,
            code = """
                id: list_pylon_issues
                namespace: company.team

                tasks:
                  - id: list_issues
                    type: io.kestra.plugin.pylon.issue.List
                    apiToken: "{{ secret('PYLON_API_TOKEN') }}"
                    fetchType: FETCH
                """
        )
    }
)
public class List extends AbstractPylon implements RunnableTask<List.Output> {

    private static final int DEFAULT_LIMIT = 20000;
    private static final Duration DEFAULT_WINDOW = Duration.ofDays(1);

    @Schema(
        title = "Start time",
        description = "Start (inclusive) of the time range to list issues for, RFC3339. Defaults to `endTime` minus 24 hours. The range must not exceed 365 days."
    )
    @PluginProperty(group = "processing")
    private Property<Instant> startTime;

    @Schema(
        title = "End time",
        description = "End (inclusive) of the time range to list issues for, RFC3339. Defaults to now."
    )
    @PluginProperty(group = "processing")
    private Property<Instant> endTime;

    @Schema(
        title = "Page size",
        description = "Number of issues fetched per page (Pylon's own `limit` query parameter). Must be between 1 and 20000. Defaults to 20000."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<@Min(1) @Max(20000) Integer> limit = Property.ofValue(DEFAULT_LIMIT);

    @Schema(
        title = "Fetch type",
        description = "FETCH returns all rows, FETCH_ONE returns the first row only, STORE streams rows to internal storage (ION), NONE returns no data. Defaults to STORE."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.STORE);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();
        var rFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.STORE);
        var rLimit = runContext.render(this.limit).as(Integer.class).orElse(DEFAULT_LIMIT);
        var rEndTime = runContext.render(this.endTime).as(Instant.class).orElse(Instant.now());
        var rStartTime = runContext.render(this.startTime).as(Instant.class).orElse(rEndTime.minus(DEFAULT_WINDOW));

        logger.info("Listing Pylon issues between {} and {} (fetchType={})", rStartTime, rEndTime, rFetchType);

        var output = Output.builder();

        try (var client = client(runContext)) {
            switch (rFetchType) {
                case FETCH_ONE -> {
                    var page = client.firstIssuesPage(rStartTime, rEndTime, rLimit, "list Pylon issues");
                    var first = page.data().isEmpty() ? null : page.data().getFirst();
                    output.row(first).size(first == null ? 0L : 1L);
                }
                case FETCH -> {
                    var rows = client.listAllIssues(rStartTime, rEndTime, rLimit, "list Pylon issues");
                    output.rows(rows).size((long) rows.size());
                }
                case STORE -> {
                    var tempFile = runContext.workingDir().createTempFile(".ion").toFile();
                    var count = new long[]{0};
                    try (var fileOutput = new BufferedOutputStream(new FileOutputStream(tempFile), FileSerde.BUFFER_SIZE)) {
                        client.walkIssues(rStartTime, rEndTime, rLimit, "list Pylon issues", rows -> {
                            rows.forEach(row -> {
                                try {
                                    FileSerde.write(fileOutput, row);
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            });
                            count[0] += rows.size();
                        });
                    }
                    output.uri(runContext.storage().putFile(tempFile)).size(count[0]);
                }
                case NONE -> output.size(0L);
            }
        }

        return output.build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "First row of fetched data", description = "Only populated when fetchType is FETCH_ONE.")
        private final Map<String, Object> row;

        @Schema(title = "List of all fetched rows", description = "Only populated when fetchType is FETCH.")
        private final java.util.List<Map<String, Object>> rows;

        @Schema(title = "URI of stored results", description = "Only populated when fetchType is STORE; file is stored in internal storage using ION format.")
        private final URI uri;

        @Schema(title = "Number of issues fetched")
        private final Long size;
    }
}
