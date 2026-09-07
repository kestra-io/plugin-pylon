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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Create a Pylon issue",
    description = "Calls `POST /issues` to create a new issue and its first message. Requires either `accountId` or `requesterEmail` so Pylon knows which customer the issue is for."
)
@Plugin(
    examples = {
        @Example(
            title = "Create a Pylon issue from a flow",
            full = true,
            code = """
                id: create_pylon_issue
                namespace: company.team

                inputs:
                  - id: subject
                    type: STRING
                  - id: description
                    type: STRING

                tasks:
                  - id: create_issue
                    type: io.kestra.plugin.pylon.issue.Create
                    apiToken: "{{ secret('PYLON_API_TOKEN') }}"
                    title: "{{ inputs.subject }}"
                    bodyHtml: "{{ inputs.description }}"
                    requesterEmail: "customer@example.com"
                """
        )
    }
)
public class Create extends AbstractPylon implements RunnableTask<Create.Output> {

    public enum Priority {
        URGENT,
        HIGH,
        MEDIUM,
        LOW;

        @Override
        public String toString() {
            return super.toString().toLowerCase();
        }
    }

    @Schema(title = "Issue title", description = "Short summary of the issue shown in Pylon.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> title;

    @Schema(title = "Issue body (HTML)", description = "HTML content of the issue's first message.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> bodyHtml;

    @Schema(
        title = "Account ID",
        description = "Pylon account this issue belongs to. Required unless `requesterEmail` (or a requester) is provided."
    )
    @PluginProperty(group = "main")
    private Property<String> accountId;

    @Schema(
        title = "Requester email",
        description = "Email of the customer this issue is for. If no matching contact exists, one is created. Required unless `accountId` is provided."
    )
    @PluginProperty(group = "main")
    private Property<String> requesterEmail;

    @Schema(title = "Assignee ID", description = "Pylon user ID the issue should be assigned to.")
    @PluginProperty(group = "main")
    private Property<String> assigneeId;

    @Schema(title = "Team ID", description = "Pylon team the issue should be assigned to.")
    @PluginProperty(group = "main")
    private Property<String> teamId;

    @Schema(title = "Priority", description = "Priority of the issue. Leave blank to use Pylon's default.")
    @PluginProperty(group = "processing")
    private Property<Priority> priority;

    @Schema(title = "Tags", description = "Tags to apply to the issue.")
    @PluginProperty(group = "processing")
    private Property<List<String>> tags;

    @Schema(
        title = "Custom fields",
        description = """
            Map of custom field slug to value. Use a list value for a multi-valued field (e.g. \
            multiselect), or a single string otherwise. Field slugs are listed by the Pylon \
            `GET /custom-fields` endpoint."""
    )
    @PluginProperty(group = "advanced")
    private Property<Map<String, Object>> customFields;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var rTitle = runContext.render(this.title).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("Pylon 'title' is required to create an issue."));
        var rBodyHtml = runContext.render(this.bodyHtml).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("Pylon 'bodyHtml' is required to create an issue."));

        var body = new LinkedHashMap<String, Object>();
        body.put("title", rTitle);
        body.put("body_html", rBodyHtml);
        runContext.render(this.accountId).as(String.class).ifPresent(v -> body.put("account_id", v));
        runContext.render(this.requesterEmail).as(String.class).ifPresent(v -> body.put("requester_email", v));
        runContext.render(this.assigneeId).as(String.class).ifPresent(v -> body.put("assignee_id", v));
        runContext.render(this.teamId).as(String.class).ifPresent(v -> body.put("team_id", v));
        runContext.render(this.priority).as(Priority.class).ifPresent(v -> body.put("priority", v.toString()));

        var rTags = runContext.render(this.tags).asList(String.class);
        if (!rTags.isEmpty()) {
            body.put("tags", rTags);
        }

        var rCustomFields = runContext.render(this.customFields).asMap(String.class, Object.class);
        if (!rCustomFields.isEmpty()) {
            body.put("custom_fields", PylonClient.customFieldsToArray(rCustomFields));
        }

        logger.info("Creating Pylon issue '{}'", rTitle);

        try (var client = client(runContext)) {
            var issue = client.postForData("/issues", body, "create Pylon issue");

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

        @Schema(title = "Issue ID", description = "Pylon-assigned identifier of the newly created issue.")
        private final String issueId;
    }
}
