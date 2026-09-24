package io.kestra.plugin.pylon.issue;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest
class CreateTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void run(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlPathEqualTo("/issues"))
            .withRequestBody(equalToJson("""
                {"title": "Cannot log in", "body_html": "<p>Help</p>", "requester_email": "customer@example.com", "tags": ["bug"]}
                """))
            .willReturn(okJson("""
                {"data": {"id": "issue-1", "number": 1, "title": "Cannot log in", "state": "new", "link": "https://app.usepylon.com/issues?issueNumber=1"}}
                """)));

        var task = Create.builder()
            .id(IdUtils.create())
            .type(Create.class.getName())
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .apiToken(Property.ofValue("test-token"))
            .title(Property.ofValue("Cannot log in"))
            .bodyHtml(Property.ofValue("<p>Help</p>"))
            .requesterEmail(Property.ofValue("customer@example.com"))
            .tags(Property.ofValue(List.of("bug")))
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getIssueId(), is("issue-1"));
        assertThat(output.getIssue().get("title"), is("Cannot log in"));
        assertThat(output.getIssueUrl(), is("https://app.usepylon.com/issues?issueNumber=1"));
        assertThat(output.getIssueNumber(), is(1));
    }

    @Test
    void customFieldsAreConvertedToWireFormat(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlPathEqualTo("/issues"))
            .withRequestBody(equalToJson("""
                {
                  "title": "Cannot log in",
                  "body_html": "<p>Help</p>",
                  "account_id": "acc-1",
                  "custom_fields": [
                    {"slug": "priority_reason", "value": "outage"},
                    {"slug": "impacted_teams", "values": ["billing", "auth"]}
                  ]
                }
                """, true, false))
            .willReturn(okJson("""
                {"data": {"id": "issue-2", "title": "Cannot log in"}}
                """)));

        var task = Create.builder()
            .id(IdUtils.create())
            .type(Create.class.getName())
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .apiToken(Property.ofValue("test-token"))
            .title(Property.ofValue("Cannot log in"))
            .bodyHtml(Property.ofValue("<p>Help</p>"))
            .accountId(Property.ofValue("acc-1"))
            .customFields(Property.ofValue(Map.of(
                "priority_reason", "outage",
                "impacted_teams", List.of("billing", "auth")
            )))
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getIssueId(), is("issue-2"));
        assertThat(output.getIssueUrl(), is(nullValue()));
        assertThat(output.getIssueNumber(), is(nullValue()));
    }

    @Test
    void nonNumericNumberYieldsNullIssueNumber(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(post(urlPathEqualTo("/issues"))
            .willReturn(okJson("""
                {"data": {"id": "issue-3", "number": "abc", "title": "Cannot log in"}}
                """)));

        var task = Create.builder()
            .id(IdUtils.create())
            .type(Create.class.getName())
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .apiToken(Property.ofValue("test-token"))
            .title(Property.ofValue("Cannot log in"))
            .bodyHtml(Property.ofValue("<p>Help</p>"))
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getIssueNumber(), is(nullValue()));
    }

    @Test
    void badRequestSurfacesPylonErrorMessage(WireMockRuntimeInfo wireMockRuntimeInfo) {
        stubFor(post(urlPathEqualTo("/issues")).willReturn(aResponse().withStatus(400)
            .withHeader("Content-Type", "application/json")
            .withBody("""
                {"errors": ["title is required"]}
                """)));

        var task = Create.builder()
            .id(IdUtils.create())
            .type(Create.class.getName())
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .apiToken(Property.ofValue("test-token"))
            .title(Property.ofValue("Cannot log in"))
            .bodyHtml(Property.ofValue("<p>Help</p>"))
            .build();
        var runContext = runContextFactory.of(Map.of());

        var ex = assertThrows(Exception.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("title is required"));
        assertThat(ex.getMessage(), containsString("400"));
    }

    @Test
    void missingTitleFailsFastWithoutHttpCall(WireMockRuntimeInfo wireMockRuntimeInfo) {
        var task = Create.builder()
            .id(IdUtils.create())
            .type(Create.class.getName())
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .apiToken(Property.ofValue("test-token"))
            .bodyHtml(Property.ofValue("<p>Help</p>"))
            .build();
        var runContext = runContextFactory.of(Map.of());

        var ex = assertThrows(Exception.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("title"));
    }
}
