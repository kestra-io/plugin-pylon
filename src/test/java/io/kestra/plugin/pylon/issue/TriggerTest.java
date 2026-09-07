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

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

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
}
