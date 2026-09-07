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
    title = "Reply to a Pylon issue",
    description = """
        Calls `POST /issues/{id}/reply` to send a customer-facing reply, visible to the requester. \
        `messageId` must be the top-level `id` of an existing customer-visible message on the issue \
        (from the Pylon `GET /issues/{id}/messages` endpoint) — it selects which conversation or \
        thread the reply is delivered on and, for email, controls the reply-chain headers."""
)
@Plugin(
    examples = {
        @Example(
            title = "Reply to a Pylon issue",
            full = true,
            code = """
                id: reply_pylon_issue
                namespace: company.team

                inputs:
                  - id: issue_id
                    type: STRING
                  - id: message_id
                    type: STRING
                    description: The top-level message ID from `GET /issues/{id}/messages` to reply to.

                tasks:
                  - id: reply_issue
                    type: io.kestra.plugin.pylon.issue.Reply
                    apiToken: "{{ secret('PYLON_API_TOKEN') }}"
                    issueId: "{{ inputs.issue_id }}"
                    messageId: "{{ inputs.message_id }}"
                    bodyHtml: "We're looking into this, thanks for your patience!"
                """
        )
    }
)
public class Reply extends AbstractPylon implements RunnableTask<Reply.Output> {

    @Schema(title = "Issue ID or number", description = "The Pylon issue to reply to.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> issueId;

    @Schema(
        title = "Message ID",
        description = "The top-level Pylon message ID to reply to, from the `id` field returned by `GET /issues/{id}/messages`. Must be customer-visible and belong to `issueId`."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> messageId;

    @Schema(title = "Reply body (HTML)", description = "HTML content of the reply.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> bodyHtml;

    @Schema(title = "User ID", description = "Pylon user to post the reply as. Only one of `userId`/`contactId` may be set.")
    @PluginProperty(group = "advanced")
    private Property<String> userId;

    @Schema(title = "Contact ID", description = "Contact to post the reply as. Only one of `userId`/`contactId` may be set.")
    @PluginProperty(group = "advanced")
    private Property<String> contactId;

    @Schema(title = "Attachment URLs", description = "URLs of files to attach to this reply.")
    @PluginProperty(group = "advanced")
    private Property<List<String>> attachmentUrls;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();
        var rIssueId = runContext.render(this.issueId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("Pylon 'issueId' is required."));
        var rMessageId = runContext.render(this.messageId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("Pylon 'messageId' is required to reply to an issue."));
        var rBodyHtml = runContext.render(this.bodyHtml).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("Pylon 'bodyHtml' is required to reply to an issue."));

        var body = new LinkedHashMap<String, Object>();
        body.put("message_id", rMessageId);
        body.put("body_html", rBodyHtml);
        runContext.render(this.userId).as(String.class).ifPresent(v -> body.put("user_id", v));
        runContext.render(this.contactId).as(String.class).ifPresent(v -> body.put("contact_id", v));

        var rAttachmentUrls = runContext.render(this.attachmentUrls).asList(String.class);
        if (!rAttachmentUrls.isEmpty()) {
            body.put("attachment_urls", rAttachmentUrls);
        }

        logger.info("Replying to Pylon issue '{}'", rIssueId);

        try (var client = client(runContext)) {
            var data = client.postForData(
                "/issues/" + PylonClient.encodePathSegment(rIssueId) + "/reply",
                body,
                "reply to Pylon issue '" + rIssueId + "'"
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
        @Schema(title = "Message ID", description = "Pylon-assigned identifier of the newly created reply message.")
        private final String messageId;

        @Schema(title = "Issue ID", description = "Identifier of the issue the reply was posted on.")
        private final String issueId;
    }
}
