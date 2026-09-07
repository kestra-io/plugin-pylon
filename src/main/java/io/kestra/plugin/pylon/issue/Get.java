package io.kestra.plugin.pylon.issue;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.pylon.AbstractPylon;
import io.kestra.plugin.pylon.PylonClient;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Get a Pylon issue",
    description = "Calls `GET /issues/{id}` to fetch a single Pylon issue by its ID or issue number."
)
@Plugin(
    examples = {
        @Example(
            title = "Fetch a Pylon issue by ID",
            full = true,
            code = """
                id: get_pylon_issue
                namespace: company.team

                inputs:
                  - id: issue_id
                    type: STRING

                tasks:
                  - id: get_issue
                    type: io.kestra.plugin.pylon.issue.Get
                    apiToken: "{{ secret('PYLON_API_TOKEN') }}"
                    issueId: "{{ inputs.issue_id }}"
                """
        )
    }
)
public class Get extends AbstractPylon implements RunnableTask<Get.Output> {

    @Schema(
        title = "Issue ID or number",
        description = "The Pylon issue ID (opaque string) or issue number to fetch."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> issueId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();
        var rIssueId = runContext.render(this.issueId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("Pylon 'issueId' is required."));

        logger.info("Fetching Pylon issue '{}'", rIssueId);

        try (var client = client(runContext)) {
            var issue = client.getForData(
                "/issues/" + PylonClient.encodePathSegment(rIssueId),
                "get Pylon issue '" + rIssueId + "'"
            );

            return Output.builder()
                .issue(issue)
                .issueId(String.valueOf(issue.get("id")))
                .build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Issue", description = "Full Pylon issue object as returned by the API.")
        private final Map<String, Object> issue;

        @Schema(title = "Issue ID", description = "Pylon-assigned identifier of the issue.")
        private final String issueId;
    }
}
