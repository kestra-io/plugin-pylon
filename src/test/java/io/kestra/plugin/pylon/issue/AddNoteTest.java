package io.kestra.plugin.pylon.issue;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
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
class AddNoteTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void run(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlPathEqualTo("/issues/issue-42/note"))
            .withRequestBody(equalToJson("""
                {"body_html": "Automated check passed."}
                """))
            .willReturn(okJson("""
                {"data": {"id": "msg-3", "issue_id": "issue-42"}}
                """)));

        var task = AddNote.builder()
            .id(IdUtils.create())
            .type(AddNote.class.getName())
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .apiToken(Property.ofValue("test-token"))
            .issueId(Property.ofValue("issue-42"))
            .bodyHtml(Property.ofValue("Automated check passed."))
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getMessageId(), is("msg-3"));
        assertThat(output.getIssueId(), is("issue-42"));
    }
}
