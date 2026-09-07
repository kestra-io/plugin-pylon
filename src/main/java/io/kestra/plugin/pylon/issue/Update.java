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
    title = "Update a Pylon issue",
    description = "Calls `PATCH /issues/{id}`. Only the fields you set are sent, so unset fields are left untouched on the issue."
)
@Plugin(
    examples = {
        @Example(
            title = "Close a Pylon issue and reassign its tags",
            full = true,
            code = """
                id: update_pylon_issue
                namespace: company.team

                inputs:
                  - id: issue_id
                    type: STRING

                tasks:
                  - id: update_issue
                    type: io.kestra.plugin.pylon.issue.Update
                    apiToken: "{{ secret('PYLON_API_TOKEN') }}"
                    issueId: "{{ inputs.issue_id }}"
                    state: closed
                    tags:
                      - resolved
                """
        )
    }
)
public class Update extends AbstractPylon implements RunnableTask<Update.Output> {

    public enum IssueType {
        CONVERSATION,
        TICKET;

        @Override
        public String toString() {
            return super.toString().toLowerCase();
        }
    }

    @Schema(title = "Issue ID or number", description = "The Pylon issue ID or issue number to update.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> issueId;

    @Schema(title = "Title", description = "New title for the issue.")
    @PluginProperty(group = "main")
    private Property<String> title;

    @Schema(
        title = "State",
        description = """
            The state to move the issue to. Standard values are `new`, `waiting_on_you`, \
            `waiting_on_customer`, `on_hold`, and `closed`; custom status slugs configured on your \
            workspace are also supported, which is why this is a free-form string rather than a fixed \
            list of values."""
    )
    @PluginProperty(group = "main")
    private Property<String> state;

    @Schema(title = "Assignee ID", description = "Pylon user ID to assign the issue to. Pass an empty string to unassign.")
    @PluginProperty(group = "main")
    private Property<String> assigneeId;

    @Schema(title = "Team ID", description = "Pylon team to assign the issue to. Pass an empty string to remove the team.")
    @PluginProperty(group = "main")
    private Property<String> teamId;

    @Schema(title = "Account ID", description = "Pylon account to move the issue to. Pass an empty string to remove it (requires `requesterId`).")
    @PluginProperty(group = "processing")
    private Property<String> accountId;

    @Schema(title = "Requester ID", description = "Pylon contact to set as the issue's requester. Pass an empty string to remove it.")
    @PluginProperty(group = "processing")
    private Property<String> requesterId;

    @Schema(title = "Tags", description = "Tags to set on the issue; replaces the existing tag set exactly.")
    @PluginProperty(group = "processing")
    private Property<List<String>> tags;

    @Schema(title = "Issue type", description = "Upgrade a conversation to a support ticket. Cannot be downgraded from `TICKET` back to `CONVERSATION`.")
    @PluginProperty(group = "advanced")
    private Property<IssueType> issueType;

    @Schema(title = "Customer portal visible", description = "Whether the issue should be visible in the customer portal.")
    @PluginProperty(group = "advanced")
    private Property<Boolean> customerPortalVisible;

    @Schema(
        title = "Custom fields",
        description = """
            Map of custom field slug to value to modify; only the fields listed here are changed. \
            Use a list value for a multi-valued field (e.g. multiselect), or a single string \
            otherwise."""
    )
    @PluginProperty(group = "advanced")
    private Property<Map<String, Object>> customFields;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();
        var rIssueId = runContext.render(this.issueId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("Pylon 'issueId' is required."));

        var body = new LinkedHashMap<String, Object>();
        runContext.render(this.title).as(String.class).ifPresent(v -> body.put("title", v));
        runContext.render(this.state).as(String.class).ifPresent(v -> body.put("state", v));
        runContext.render(this.assigneeId).as(String.class).ifPresent(v -> body.put("assignee_id", v));
        runContext.render(this.teamId).as(String.class).ifPresent(v -> body.put("team_id", v));
        runContext.render(this.accountId).as(String.class).ifPresent(v -> body.put("account_id", v));
        runContext.render(this.requesterId).as(String.class).ifPresent(v -> body.put("requester_id", v));
        runContext.render(this.issueType).as(IssueType.class).ifPresent(v -> body.put("type", v.toString()));
        runContext.render(this.customerPortalVisible).as(Boolean.class).ifPresent(v -> body.put("customer_portal_visible", v));

        var rTags = runContext.render(this.tags).asList(String.class);
        if (!rTags.isEmpty()) {
            body.put("tags", rTags);
        }

        var rCustomFields = runContext.render(this.customFields).asMap(String.class, Object.class);
        if (!rCustomFields.isEmpty()) {
            body.put("custom_fields", PylonClient.customFieldsToArray(rCustomFields));
        }

        if (body.isEmpty()) {
            throw new IllegalArgumentException(
                "Pylon Update task requires at least one field to change (title, state, assigneeId, teamId, " +
                    "accountId, requesterId, tags, issueType, customerPortalVisible, customFields) — refusing to send an empty PATCH request."
            );
        }

        logger.info("Updating Pylon issue '{}': {}", rIssueId, body.keySet());

        try (var client = client(runContext)) {
            var issue = client.patchForData(
                "/issues/" + PylonClient.encodePathSegment(rIssueId),
                body,
                "update Pylon issue '" + rIssueId + "'"
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
        @Schema(title = "Issue", description = "Full Pylon issue object after the update, as returned by the API.")
        private final Map<String, Object> issue;

        @Schema(title = "Issue ID", description = "Pylon-assigned identifier of the updated issue.")
        private final String issueId;
    }
}
