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

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Add an internal note to a Pylon issue",
    description = """
        Calls `POST /issues/{id}/note` to post an internal note, not visible to the requester. If \
        neither `threadId` nor `messageId` is set, the note is posted to the most recently created \
        Slack-backed internal thread, or a new Pylon-only thread if none exists."""
)
@Plugin(
    examples = {
        @Example(
            title = "Leave an internal note after an automated check",
            full = true,
            code = """
                id: add_pylon_note
                namespace: company.team

                inputs:
                  - id: issue_id
                    type: STRING

                tasks:
                  - id: add_note
                    type: io.kestra.plugin.pylon.issue.AddNote
                    apiToken: "{{ secret('PYLON_API_TOKEN') }}"
                    issueId: "{{ inputs.issue_id }}"
                    bodyHtml: "Automated check passed, closing shortly."
                """
        )
    }
)
public class AddNote extends AbstractPylon implements RunnableTask<AddNote.Output> {

    @Schema(title = "Issue ID or number", description = "The Pylon issue to add the note to.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> issueId;

    @Schema(title = "Note body (HTML)", description = "HTML content of the internal note.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> bodyHtml;

    @Schema(
        title = "Thread ID",
        description = "Internal thread to post the note to (the `id` field from `GET /issues/{id}/threads`). Cannot be combined with `messageId`."
    )
    @PluginProperty(group = "advanced")
    private Property<String> threadId;

    @Schema(
        title = "Message ID",
        description = "An existing internal note's `id` (from `GET /issues/{id}/messages`) whose thread should receive the new note. Cannot be combined with `threadId`."
    )
    @PluginProperty(group = "advanced")
    private Property<String> messageId;

    @Schema(
        title = "Thread name",
        description = "Name for a new Pylon-only internal thread. Used only when neither `threadId` nor `messageId` is set and no Slack-backed internal thread exists."
    )
    @PluginProperty(group = "advanced")
    private Property<String> threadName;

    @Schema(title = "User ID", description = "Pylon user to post the note as. Defaults to the API token's user.")
    @PluginProperty(group = "advanced")
    private Property<String> userId;

    @Schema(title = "Attachment URLs", description = "URLs of files to attach to this internal note.")
    @PluginProperty(group = "advanced")
    private Property<List<String>> attachmentUrls;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();
        var rIssueId = runContext.render(this.issueId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("Pylon 'issueId' is required."));
        var rBodyHtml = runContext.render(this.bodyHtml).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("Pylon 'bodyHtml' is required to add a note."));

        var body = new LinkedHashMap<String, Object>();
        body.put("body_html", rBodyHtml);
        runContext.render(this.threadId).as(String.class).ifPresent(v -> body.put("thread_id", v));
        runContext.render(this.messageId).as(String.class).ifPresent(v -> body.put("message_id", v));
        runContext.render(this.threadName).as(String.class).ifPresent(v -> body.put("thread_name", v));
        runContext.render(this.userId).as(String.class).ifPresent(v -> body.put("user_id", v));

        var rAttachmentUrls = runContext.render(this.attachmentUrls).asList(String.class);
        if (!rAttachmentUrls.isEmpty()) {
            body.put("attachment_urls", rAttachmentUrls);
        }

        logger.info("Adding internal note to Pylon issue '{}'", rIssueId);

        try (var client = client(runContext)) {
            var data = client.postForData(
                "/issues/" + PylonClient.encodePathSegment(rIssueId) + "/note",
                body,
                "add note to Pylon issue '" + rIssueId + "'"
            );

            return Output.builder()
                .messageId(String.valueOf(data.get("id")))
                .issueId(String.valueOf(data.get("issue_id")))
                .build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Message ID", description = "Pylon-assigned identifier of the newly created note.")
        private final String messageId;

        @Schema(title = "Issue ID", description = "Identifier of the issue the note was posted on.")
        private final String issueId;
    }
}
