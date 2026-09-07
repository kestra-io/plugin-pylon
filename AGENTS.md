# Kestra Pylon Plugin

## What

- Provides plugin components under `io.kestra.plugin.pylon` and `io.kestra.plugin.pylon.issue`.
- Manages Pylon support issues (tickets): list, get, create, update, reply, add an internal note, and react to
  new or updated issues via a polling trigger.

## Why

- Teams that route support workflows through Pylon currently script ticket creation/updates and polling for new
  issues by hand (curl in a `Script` task, or a scheduled job outside Kestra).
- This plugin lets a Kestra flow create or update a Pylon issue as a step in an incident-response or
  customer-onboarding flow, post a customer-facing reply or internal note, and react to new or updated tickets
  via a polling trigger instead of hand-rolled polling scripts.
- It complements existing ticketing/issue-tracker plugins (`plugin-zendesk`, `plugin-jira`, `plugin-linear`) and
  fills the same role for Pylon.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `pylon` — `AbstractPylon` (connection properties + shared HTTP helper) and `PylonClient` (bearer-token auth,
  `data`-envelope unwrapping, `/issues` cursor pagination, non-2xx error mapping).
- `pylon.issue` — ticket management tasks and the polling trigger.

Infrastructure dependencies: none — Pylon is a hosted SaaS with no self-hosted mode; tests use WireMock.

### Key Plugin Classes

- `io.kestra.plugin.pylon.AbstractPylon` — connection base class for every task (`apiToken`, `baseUrl`).
- `io.kestra.plugin.pylon.PylonClient` — shared HTTP call logic, used by tasks (via `AbstractPylon`) and by the
  trigger directly (a trigger cannot extend `Task`).
- `io.kestra.plugin.pylon.issue.List` — `GET /issues`, `fetchType`-based (`FETCH`/`FETCH_ONE`/`STORE`/`NONE`).
- `io.kestra.plugin.pylon.issue.Get` — `GET /issues/{id}`.
- `io.kestra.plugin.pylon.issue.Create` — `POST /issues`.
- `io.kestra.plugin.pylon.issue.Update` — `PATCH /issues/{id}`.
- `io.kestra.plugin.pylon.issue.Reply` — `POST /issues/{id}/reply`.
- `io.kestra.plugin.pylon.issue.AddNote` — `POST /issues/{id}/note`.
- `io.kestra.plugin.pylon.issue.Trigger` — polling trigger, watermark dedup on `updated_at` via namespace KV store.

### Project Structure

```
plugin-pylon/
├── src/main/java/io/kestra/plugin/pylon/
│   ├── AbstractPylon.java
│   ├── PylonClient.java
│   └── issue/
│       ├── List.java
│       ├── Get.java
│       ├── Create.java
│       ├── Update.java
│       ├── Reply.java
│       ├── AddNote.java
│       └── Trigger.java
├── src/test/java/io/kestra/plugin/pylon/issue/
├── build.gradle
└── README.md
```

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.
- Pylon has no official Java SDK — all calls go through `io.kestra.core.http.client.HttpClient`.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
- https://docs.usepylon.com/pylon-docs/developer/api
