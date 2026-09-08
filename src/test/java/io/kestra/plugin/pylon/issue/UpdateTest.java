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

import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest
class UpdateTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void run(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(patch(urlPathEqualTo("/issues/issue-42"))
            .withRequestBody(equalToJson("""
                {"state": "closed", "tags": ["resolved"]}
                """))
            .willReturn(okJson("""
                {"data": {"id": "issue-42", "number": 42, "title": "Cannot log in", "state": "closed"}}
                """)));

        var task = Update.builder()
            .id(IdUtils.create())
            .type(Update.class.getName())
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .apiToken(Property.ofValue("test-token"))
            .issueId(Property.ofValue("issue-42"))
            .state(Property.ofValue("closed"))
            .tags(Property.ofValue(List.of("resolved")))
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getIssueId(), is("issue-42"));
        assertThat(output.getIssue().get("state"), is("closed"));
    }

    @Test
    void emptyPatchIsRejectedWithoutHttpCall(WireMockRuntimeInfo wireMockRuntimeInfo) {
        var task = Update.builder()
            .id(IdUtils.create())
            .type(Update.class.getName())
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .apiToken(Property.ofValue("test-token"))
            .issueId(Property.ofValue("issue-42"))
            .build();
        var runContext = runContextFactory.of(Map.of());

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("at least one field"));
    }
}
