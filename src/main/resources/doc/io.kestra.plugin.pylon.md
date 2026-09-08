# How to use the Pylon plugin

Manage Pylon support issues (tickets) from a Kestra flow, backed by the [Pylon REST API](https://docs.usepylon.com/pylon-docs/developer/api).

## Authentication

Every task and the trigger authenticate with a Pylon API token:

- `apiToken` (required, [secret](https://kestra.io/docs/concepts/secret)) — an API token created by a Pylon Admin
  user (Pylon Settings > API), sent as `Authorization: Bearer <token>` on every request.
- `baseUrl` (optional) — defaults to the US region (`https://api.usepylon.com`); override with
  `https://api.eu.usepylon.com` for the EU region.

Store the token as a Kestra secret and reference it with `{{ secret('PYLON_API_TOKEN') }}`, or set it once via
[plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults) so every task and the trigger inherit
it without repeating it in each flow.

## Gotchas

- **`GET /issues` requires a bounded time range.** `startTime`/`endTime` (RFC3339) must not span more than 365 days;
  `issue.List` defaults to the last 24 hours if left unset.
- **Replying requires an existing message ID.** `issue.Reply`'s `messageId` must be the top-level `id` of an
  existing customer-visible message on the issue (from Pylon's `GET /issues/{id}/messages` endpoint, not implemented
  by this plugin) — it selects which conversation/thread the reply is delivered on and, for email, the reply-chain
  headers. `issue.AddNote` has no such requirement: with no `threadId`/`messageId` set, it posts to the most
  recently created internal thread (or creates one).
- **Custom fields are a map in this plugin, an array on the wire.** Pylon's API expects `custom_fields` as an array
  of `{slug, value}` (or `{slug, values}` for multi-valued fields like multiselect); `issue.Create` and
  `issue.Update` accept a simpler `Map<String, Object>` (slug to value, or slug to a list of values) and convert it.
- **Rate limits.** Roughly: list 30/min, get 300/min, create 30/min, update/delete 120/min, reply/note 30/min. On a
  `429` response, tasks and the trigger automatically retry a bounded number of times honoring `Retry-After`; a
  persistent `429` fails the task with a message suggesting to reduce call frequency or the trigger's polling
  interval.
- **`issue.Update` rejects an empty request.** If none of its optional fields are set, the task fails fast instead
  of silently sending an empty `PATCH`.

## Tasks

### Issues (`io.kestra.plugin.pylon.issue`)

- `List` — lists issues in a time range (`startTime`/`endTime`, defaults to the last 24 hours) with cursor
  pagination and `fetchType` support (`FETCH`, `FETCH_ONE`, `STORE`, `NONE`; defaults to `STORE`).
- `Get` — fetches a single issue by ID or issue number.
- `Create` — creates an issue and its first message; `title` and `bodyHtml` are required, and either `accountId` or
  `requesterEmail` must identify the customer.
- `Update` — updates an issue; only the fields you set are sent (`PATCH` semantics).
- `Reply` — posts a customer-facing reply on an existing message thread.
- `AddNote` — posts an internal note, not visible to the requester.

## Triggers

`issue.Trigger` polls `GET /issues` at the configured `interval` and fires one execution per poll carrying every
issue whose `updated_at` is newer than the last delivered watermark (persisted in the flow's namespace KV store, so
issues are never re-delivered even across worker restarts). On the first poll, only the baseline is recorded — no
execution fires — seeded to `now - lookbackPeriod` (default `PT0S`) so enabling the trigger does not replay the
entire backlog. Output includes `issues` (the matched issue objects, oldest first) and `count`.

Each execution carries at most `maxIssuesPerExecution` issues (default `1000`): a burst of updates — a long trigger
downtime, or a bulk edit in Pylon — delivers the oldest issues first and leaves the rest behind the watermark for the
following poll(s), rather than building one oversized execution payload.

Assumption worth flagging: Pylon's `GET /issues` time-range filter is not documented as filtering on a specific
timestamp field. This trigger assumes it is (or includes) `updated_at`, since that is the only interpretation
useful for detecting updates and not just new issues — verify against your own data if you rely on this trigger for
update detection specifically.
