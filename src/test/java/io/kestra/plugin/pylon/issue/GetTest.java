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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest
class GetTest {
    @Inject
    private RunContextFactory runContextFactory;

    private Get.GetBuilder<?, ?> task(WireMockRuntimeInfo wireMockRuntimeInfo, String issueId) {
        return Get.builder()
            .id(IdUtils.create())
            .type(Get.class.getName())
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .apiToken(Property.ofValue("test-token"))
            .issueId(Property.ofValue(issueId));
    }

    @Test
    void run(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(get(urlPathEqualTo("/issues/issue-42")).willReturn(okJson("""
            {"data": {"id": "issue-42", "number": 42, "title": "Cannot log in", "state": "new"}}
            """)));

        var output = task(wireMockRuntimeInfo, "issue-42").build().run(runContextFactory.of(Map.of()));

        assertThat(output.getIssueId(), is("issue-42"));
        assertThat(output.getIssue().get("title"), is("Cannot log in"));
        assertThat(output.getIssue().get("state"), is("new"));

        verify(getRequestedFor(urlPathEqualTo("/issues/issue-42"))
            .withHeader("Authorization", equalTo("Bearer test-token")));
    }

    @Test
    void notFoundNamesTheIssueId(WireMockRuntimeInfo wireMockRuntimeInfo) {
        stubFor(get(urlPathEqualTo("/issues/missing-id")).willReturn(aResponse().withStatus(404)
            .withHeader("Content-Type", "application/json")
            .withBody("""
                {"errors": ["issue not found"]}
                """)));

        var task = task(wireMockRuntimeInfo, "missing-id").build();
        var runContext = runContextFactory.of(Map.of());

        var ex = assertThrows(Exception.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("missing-id"));
        assertThat(ex.getMessage(), containsString("404"));
    }

    @Test
    void unauthorizedMentionsApiToken(WireMockRuntimeInfo wireMockRuntimeInfo) {
        stubFor(get(urlPathEqualTo("/issues/issue-42")).willReturn(aResponse().withStatus(401)
            .withHeader("Content-Type", "application/json")
            .withBody("""
                {"errors": ["invalid token"]}
                """)));

        var task = task(wireMockRuntimeInfo, "issue-42").build();
        var runContext = runContextFactory.of(Map.of());

        var ex = assertThrows(Exception.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("apiToken"));
    }

    @Test
    void blankApiTokenFailsFast(WireMockRuntimeInfo wireMockRuntimeInfo) {
        var task = Get.builder()
            .id(IdUtils.create())
            .type(Get.class.getName())
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .apiToken(Property.ofValue(""))
            .issueId(Property.ofValue("issue-42"))
            .build();
        var runContext = runContextFactory.of(Map.of());

        var ex = assertThrows(Exception.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("apiToken"));
    }
}
