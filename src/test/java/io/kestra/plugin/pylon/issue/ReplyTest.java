package io.kestra.plugin.pylon.issue;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@KestraTest
@WireMockTest
class ReplyTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void run(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlPathEqualTo("/issues/issue-42/reply"))
            .withRequestBody(equalToJson("""
                {"message_id": "msg-1", "body_html": "We're on it!"}
                """))
            .willReturn(okJson("""
                {"data": {"id": "msg-2", "issue_id": "issue-42"}}
                """)));

        var task = Reply.builder()
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .apiToken(Property.ofValue("test-token"))
            .issueId(Property.ofValue("issue-42"))
            .messageId(Property.ofValue("msg-1"))
            .bodyHtml(Property.ofValue("We're on it!"))
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getMessageId(), is("msg-2"));
        assertThat(output.getIssueId(), is("issue-42"));
    }
}
