package io.kestra.plugin.pylon.issue;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest
class TriggerTest {
    @Inject
    private RunContextFactory runContextFactory;

    private Trigger trigger(WireMockRuntimeInfo wireMockRuntimeInfo) {
        return Trigger.builder()
            .id(IdUtils.create())
            .type(Trigger.class.getName())
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .apiToken(Property.ofValue("test-token"))
            .interval(Duration.ofMinutes(1))
            .build();
    }

    @Test
    void firstPollSeedsBaselineAndDoesNotFire(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        var trigger = trigger(wireMockRuntimeInfo);
        var ctx = TestsUtils.mockTrigger(runContextFactory, trigger);

        var result = trigger.evaluate(ctx.getKey(), ctx.getValue());

        assertThat("First poll must not fire", result.isPresent(), is(false));
    }

    @Test
    void firesOnlyOnNewIssueAndDoesNotRefire(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        // The trigger never calls the Pylon API on its very first poll (the baseline is seeded from
        // the local clock, not from a fetch) — so the first HTTP call happens on the *second* evaluate().
        stubFor(get(urlPathEqualTo("/issues")).inScenario("new-issue")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(okJson("""
                {
                  "data": [{"id": "issue-1", "number": 1, "title": "New login issue", "updated_at": "2030-01-01T00:00:00Z"}],
                  "pagination": {"cursor": "", "has_next_page": false}
                }
                """))
            .willSetStateTo("second-poll-done"));

        stubFor(get(urlPathEqualTo("/issues")).inScenario("new-issue")
            .whenScenarioStateIs("second-poll-done")
            .willReturn(okJson("""
                {
                  "data": [{"id": "issue-1", "number": 1, "title": "New login issue", "updated_at": "2030-01-01T00:00:00Z"}],
                  "pagination": {"cursor": "", "has_next_page": false}
                }
                """)));

        var trigger = trigger(wireMockRuntimeInfo);
        var ctx = TestsUtils.mockTrigger(runContextFactory, trigger);

        var firstPoll = trigger.evaluate(ctx.getKey(), ctx.getValue());
        assertThat("First poll must not fire", firstPoll.isPresent(), is(false));

        var secondPoll = trigger.evaluate(ctx.getKey(), ctx.getValue());
        assertThat("Second poll must fire on the new issue", secondPoll.isPresent(), is(true));

        var variables = secondPoll.get().getTrigger().getVariables();
        assertThat(variables.get("count"), is(1));

        var thirdPoll = trigger.evaluate(ctx.getKey(), ctx.getValue());
        assertThat("Third poll must not re-fire the already-delivered issue", thirdPoll.isPresent(), is(false));
    }

    @Test
    void lookbackPeriodSeedsAnEarlierBaselineOnFirstPoll(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(get(urlPathEqualTo("/issues")).willReturn(okJson("""
            {"data": [], "pagination": {"cursor": "", "has_next_page": false}}
            """)));

        var trigger = Trigger.builder()
            .id(IdUtils.create())
            .type(Trigger.class.getName())
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .apiToken(Property.ofValue("test-token"))
            .interval(Duration.ofMinutes(1))
            .lookbackPeriod(Property.ofValue(Duration.ofHours(1)))
            .build();
        var ctx = TestsUtils.mockTrigger(runContextFactory, trigger);

        var result = trigger.evaluate(ctx.getKey(), ctx.getValue());

        assertThat("First poll must not fire even with a lookback period", result.isPresent(), is(false));
    }
    @Test
    void capsIssuesPerExecutionAndCarriesTheRestToTheNextPoll(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(get(urlPathEqualTo("/issues")).willReturn(okJson("""
            {
              "data": [
                {"id": "issue-3", "number": 3, "updated_at": "2030-01-01T00:00:03Z"},
                {"id": "issue-1", "number": 1, "updated_at": "2030-01-01T00:00:01Z"},
                {"id": "issue-2", "number": 2, "updated_at": "2030-01-01T00:00:02Z"}
              ],
              "pagination": {"cursor": "", "has_next_page": false}
            }
            """)));

        var trigger = Trigger.builder()
            .id(IdUtils.create())
            .type(Trigger.class.getName())
            .baseUrl(Property.ofValue(wireMockRuntimeInfo.getHttpBaseUrl()))
            .apiToken(Property.ofValue("test-token"))
            .interval(Duration.ofMinutes(1))
            .maxIssuesPerExecution(Property.ofValue(2))
            .build();
        var ctx = TestsUtils.mockTrigger(runContextFactory, trigger);

        assertThat("First poll must not fire", trigger.evaluate(ctx.getKey(), ctx.getValue()).isPresent(), is(false));

        var firstFire = trigger.evaluate(ctx.getKey(), ctx.getValue());
        assertThat(firstFire.isPresent(), is(true));
        assertThat(firstFire.get().getTrigger().getVariables().get("count"), is(2));
        assertThat("Oldest issues are delivered first", issueIds(firstFire.get().getTrigger().getVariables()), contains("issue-1", "issue-2"));

        var secondFire = trigger.evaluate(ctx.getKey(), ctx.getValue());
        assertThat("The capped remainder must be delivered by the next poll", secondFire.isPresent(), is(true));
        assertThat(secondFire.get().getTrigger().getVariables().get("count"), is(1));
        assertThat(issueIds(secondFire.get().getTrigger().getVariables()), contains("issue-3"));

        assertThat("Nothing is re-delivered once drained", trigger.evaluate(ctx.getKey(), ctx.getValue()).isPresent(), is(false));
    }

    @Test
    void unparseableUpdatedAtNamesTheOffendingIssue(WireMockRuntimeInfo wireMockRuntimeInfo) throws Exception {
        stubFor(get(urlPathEqualTo("/issues")).willReturn(okJson("""
            {
              "data": [{"id": "issue-9", "number": 9, "updated_at": "yesterday"}],
              "pagination": {"cursor": "", "has_next_page": false}
            }
            """)));

        var trigger = trigger(wireMockRuntimeInfo);
        var ctx = TestsUtils.mockTrigger(runContextFactory, trigger);

        assertThat("First poll must not fire", trigger.evaluate(ctx.getKey(), ctx.getValue()).isPresent(), is(false));

        var ex = assertThrows(Exception.class, () -> trigger.evaluate(ctx.getKey(), ctx.getValue()));
        assertThat(ex.getMessage(), containsString("issue-9"));
        assertThat(ex.getMessage(), containsString("updated_at"));
    }

    @SuppressWarnings("unchecked")
    private List<String> issueIds(Map<String, Object> variables) {
        return ((List<Map<String, Object>>) variables.get("issues")).stream()
            .map(issue -> String.valueOf(issue.get("id")))
            .toList();
    }
}
