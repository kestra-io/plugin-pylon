package io.kestra.plugin.pylon.issue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVStore;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.kestra.plugin.pylon.AbstractPylon;
import io.kestra.plugin.pylon.PylonClient;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger a flow on new or updated Pylon issues",
    description = """
        Polls `GET /issues` at the configured interval and fires one execution per poll carrying \
        the issues whose `updated_at` is newer than the last delivered watermark, oldest first and up \
        to `maxIssuesPerExecution` per execution, so no issue is re-delivered. The watermark is \
        persisted in the flow's namespace KV store, keyed by flow and trigger ID. On first activation, only the current baseline is recorded (seeded to \
        `now - lookbackPeriod`) — no execution fires — to avoid replaying the entire backlog. Equal \
        `updated_at` timestamps are compared strictly (`>`), and issues sharing the newest \
        `updated_at` are tracked individually so a same-instant tie is neither skipped nor re-fired.

        Assumption: Pylon's `GET /issues` time-range filter is not documented as filtering on a \
        specific timestamp field; this trigger assumes it is (or includes) `updated_at`, since that \
        is the only interpretation useful for detecting updates, not just creations."""
)
@Plugin(
    examples = {
        @Example(
            title = "React to new or updated Pylon issues with a polling trigger",
            full = true,
            code = """
                id: on_new_pylon_issue
                namespace: company.team

                triggers:
                  - id: new_issue
                    type: io.kestra.plugin.pylon.issue.Trigger
                    apiToken: "{{ secret('PYLON_API_TOKEN') }}"
                    interval: PT1M

                tasks:
                  - id: log_new_issues
                    type: io.kestra.plugin.core.log.Log
                    message: "Found {{ trigger.count }} new/updated Pylon issue(s)"
                """
        )
    }
)
public class Trigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<Trigger.Output> {

    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();
    private static final int DEFAULT_LIMIT = 100;
    private static final int DEFAULT_MAX_ISSUES_PER_EXECUTION = 1000;

    // Duplicated from AbstractPylon (a Trigger cannot extend Task) — kept in lockstep via the
    // shared title/description/default constants so both declarations evolve together.
    @Schema(title = AbstractPylon.API_TOKEN_TITLE, description = AbstractPylon.API_TOKEN_DESCRIPTION)
    @NotNull
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    private Property<String> apiToken;

    @Schema(title = AbstractPylon.BASE_URL_TITLE, description = AbstractPylon.BASE_URL_DESCRIPTION)
    @Builder.Default
    @PluginProperty(group = "connection")
    private Property<String> baseUrl = Property.ofValue(AbstractPylon.DEFAULT_BASE_URL);

    @Schema(
        title = "Polling interval",
        description = "How often to poll the Pylon API for new or updated issues. Defaults to `PT1M` (every minute)."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Duration interval = Duration.ofMinutes(1);

    @Schema(
        title = "Lookback period",
        description = "How far before the trigger's first activation to look for issues, so enabling the trigger does not miss issues updated just before it was enabled. Defaults to `PT0S` (only issues updated from the moment the trigger is enabled)."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Duration> lookbackPeriod = Property.ofValue(Duration.ZERO);

    @Schema(
        title = "Maximum issues fetched per poll",
        description = "Caps the `limit` query parameter sent to Pylon for each page of `/issues`. Must be between 1 and 20000. Defaults to 100."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<@Min(1) @Max(20000) Integer> limit = Property.ofValue(DEFAULT_LIMIT);

    @Schema(
        title = "Maximum issues delivered per execution",
        description = "Caps how many issues a single fired execution carries, so a burst of updates (a long trigger downtime, a bulk edit in Pylon) cannot produce an oversized execution payload. The oldest issues are delivered first; the remainder stays behind the watermark and is delivered by the following poll(s). Defaults to 1000."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<@Min(1) Integer> maxIssuesPerExecution = Property.ofValue(DEFAULT_MAX_ISSUES_PER_EXECUTION);

    @Override
    public Duration getInterval() {
        return this.interval;
    }

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();
        var logger = runContext.logger();

        var kv = runContext.namespaceKv(context.getNamespace());
        var key = watermarkKey(context.getFlowId(), getId());
        var watermark = readWatermark(kv, key);

        var rApiToken = runContext.render(this.apiToken).as(String.class).orElse(null);
        if (rApiToken == null || rApiToken.isBlank()) {
            throw new IllegalArgumentException(
                "Pylon 'apiToken' is required — create an API token as a Pylon Admin user (Settings > API) and store it as a Kestra secret."
            );
        }
        var rBaseUrl = runContext.render(this.baseUrl).as(String.class).orElse(AbstractPylon.DEFAULT_BASE_URL);
        var rLimit = runContext.render(this.limit).as(Integer.class).orElse(DEFAULT_LIMIT);
        var rLookback = runContext.render(this.lookbackPeriod).as(Duration.class).orElse(Duration.ZERO);
        var rMaxIssues = runContext.render(this.maxIssuesPerExecution).as(Integer.class).orElse(DEFAULT_MAX_ISSUES_PER_EXECUTION);

        var now = Instant.now();

        if (watermark == null) {
            var seeded = now.minus(rLookback);
            logger.info("First poll: seeding Pylon issue trigger watermark at updated_at={}.", seeded);
            persistWatermark(kv, key, new Watermark(seeded, Set.of()), logger);
            return Optional.empty();
        }

        try (var client = PylonClient.connect(runContext, rBaseUrl, rApiToken)) {
            var batch = new OldestFirstBatch(rMaxIssues);
            client.walkIssues(watermark.getTimestamp(), now, rLimit, "poll Pylon issues", page -> page.forEach(issue -> {
                if (issue.get("updated_at") == null || issue.get("id") == null) {
                    return;
                }
                var id = String.valueOf(issue.get("id"));
                var updatedAt = parseUpdatedAt(issue.get("updated_at"), id);
                if (isNewerThanWatermark(updatedAt, id, watermark)) {
                    batch.offer(updatedAt, issue);
                }
            }));

            var candidates = batch.oldestFirst();

            if (candidates.isEmpty()) {
                return Optional.empty();
            }

            if (batch.matched() > candidates.size()) {
                logger.warn(
                    "Pylon issue trigger matched {} issue(s) updated since {} but delivers only the oldest {} in this execution ('maxIssuesPerExecution'); the rest stays behind the watermark and is delivered by the next poll(s).",
                    batch.matched(), watermark.getTimestamp(), candidates.size()
                );
            }

            var newestTimestamp = candidates.getLast().getKey();
            var boundaryIds = new HashSet<String>();
            if (newestTimestamp.equals(watermark.getTimestamp())) {
                boundaryIds.addAll(watermark.getBoundaryIds());
            }
            candidates.stream()
                .filter(entry -> entry.getKey().equals(newestTimestamp))
                .forEach(entry -> boundaryIds.add(String.valueOf(entry.getValue().get("id"))));

            var matchedIssues = candidates.stream().map(Map.Entry::getValue).toList();
            logger.info("Found {} new or updated Pylon issue(s) since {}.", matchedIssues.size(), watermark.getTimestamp());

            var output = Output.builder().issues(matchedIssues).count(matchedIssues.size()).build();
            var execution = TriggerService.generateExecution(this, conditionContext, context, output);

            persistWatermark(kv, key, new Watermark(newestTimestamp, boundaryIds), logger);

            return Optional.of(execution);
        }
    }

    private Instant parseUpdatedAt(Object rawUpdatedAt, String issueId) {
        try {
            return Instant.parse(String.valueOf(rawUpdatedAt));
        } catch (DateTimeParseException e) {
            throw new IllegalStateException(
                "Unparseable 'updated_at' on Pylon issue '" + issueId + "': '" + rawUpdatedAt +
                    "' is not an ISO-8601 instant — the trigger cannot place it against its watermark.", e
            );
        }
    }

    /** True when `updatedAt`/`id` was not yet delivered by a previous poll (strict `>`, tie-broken by boundary IDs). */
    private boolean isNewerThanWatermark(Instant updatedAt, String id, Watermark watermark) {
        if (updatedAt.isAfter(watermark.getTimestamp())) {
            return true;
        }
        return updatedAt.equals(watermark.getTimestamp()) && !watermark.getBoundaryIds().contains(id);
    }

    /**
     * Length-prefixes each segment so two distinct (flowId, triggerId) pairs whose concatenation
     * would otherwise collide (e.g. ("ab", "c") vs. ("a", "bc")) never share a KV key.
     */
    private String watermarkKey(String flowId, String triggerId) {
        return "pylon_watermark_" + flowId.length() + "_" + flowId + "_" + triggerId.length() + "_" + triggerId;
    }

    /**
     * A read failure here must never be treated as "no watermark yet" — that would silently reset
     * the trigger to first-poll and drop every issue accumulated since the last successful poll.
     */
    private Watermark readWatermark(KVStore kv, String key) {
        try {
            return kv.getValue(key)
                .map(v -> parseWatermark(String.valueOf(v.value())))
                .orElse(null);
        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to read Pylon trigger watermark '" + key + "' from the namespace KV store — refusing to " +
                    "silently treat this as the first poll, which would re-seed the baseline and drop the backlog: " + e.getMessage(), e
            );
        }
    }

    /**
     * Persisted every time a new watermark is established, whether or not the poll fires an
     * execution. A write failure here must fail the poll loudly instead of returning an execution
     * whose delivery was never durably recorded — otherwise the next poll re-delivers the same issue(s).
     */
    private void persistWatermark(KVStore kv, String key, Watermark watermark, Logger logger) {
        try {
            kv.put(key, new KVValueAndMetadata(new KVMetadata(null, (Instant) null), serializeWatermark(watermark, logger)));
        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to persist Pylon trigger watermark '" + key + "' — the next poll would otherwise re-deliver the same issue(s): " + e.getMessage(), e
            );
        }
    }

    private String serializeWatermark(Watermark watermark, Logger logger) {
        try {
            return MAPPER.writeValueAsString(watermark);
        } catch (Exception e) {
            logger.warn("Failed to serialize Pylon trigger watermark, falling back to timestamp-only tracking: {}", e.getMessage());
            return watermark.getTimestamp().toString();
        }
    }

    private Watermark parseWatermark(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(raw, Watermark.class);
        } catch (Exception e) {
            throw new IllegalStateException(
                "Unparseable Pylon trigger watermark '" + raw + "' — refusing to silently treat this as the first " +
                    "poll, which would re-seed the baseline and drop the backlog: " + e.getMessage(), e
            );
        }
    }

    /**
     * Keeps only the oldest {@code capacity} matched issues while `/issues` pages stream in, so
     * neither the in-memory buffer nor the execution payload grows with the number of matches.
     * Evicted issues are all at or after the delivered watermark, so a later poll picks them up.
     */
    private static final class OldestFirstBatch {
        private final int capacity;
        private final PriorityQueue<Map.Entry<Instant, Map<String, Object>>> newestFirst;
        private int matched;

        private OldestFirstBatch(int capacity) {
            this.capacity = capacity;
            this.newestFirst = new PriorityQueue<>(
                Comparator.<Map.Entry<Instant, Map<String, Object>>, Instant>comparing(Map.Entry::getKey).reversed()
            );
        }

        private void offer(Instant updatedAt, Map<String, Object> issue) {
            this.matched++;
            this.newestFirst.add(Map.entry(updatedAt, issue));
            if (this.newestFirst.size() > this.capacity) {
                this.newestFirst.poll();
            }
        }

        /** Total number of issues newer than the watermark, including those evicted by the cap. */
        private int matched() {
            return this.matched;
        }

        private List<Map.Entry<Instant, Map<String, Object>>> oldestFirst() {
            return this.newestFirst.stream().sorted(Map.Entry.comparingByKey()).toList();
        }
    }

    @Builder
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Watermark {
        private Instant timestamp;
        private Set<String> boundaryIds;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Issues", description = "Every Pylon issue newly created or updated since the last poll.")
        private final List<Map<String, Object>> issues;

        @Schema(title = "Count", description = "Number of issues in `issues`.")
        private final Integer count;
    }
}
