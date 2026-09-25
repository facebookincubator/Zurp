---
name: use-fbdl-mcp
description: |
  How to use the FBDL MCP server — tool selection, submission
  workflow, and run-management rules for the live API.
---

# Using the FBDL MCP server

This skill is the operations manual for `fbdl-mcp`.

## When to use it

Use it whenever the user wants to:

- write or fix an FBDL script
- submit a script as a real FBDL run
- inspect or archive previous runs
- understand why `create_fbdl_run` was rejected

## Tools

Read-only:

- `list_entities`
- `list_actions`
- `validate_fbdl`
- `explain_fbdl`
- resource `fbdl://reference`

API-backed:

- `create_fbdl_run({fbdl_code, note})`
- `list_fbdl_runs({?limit, ?after})`
- `get_fbdl_run({id})`
- `archive_fbdl_run({id})`

## Standard workflow

1. Understand the scenario.
2. Use `list_entities` / `list_actions` if names or params are unclear.
3. Write the script with block grammar.
4. Call `validate_fbdl` before presenting or submitting it.
5. Only when the user explicitly wants a real run, call `create_fbdl_run`.
6. Poll with `get_fbdl_run` if results are needed.
7. Archive old runs when the user is done with them.

## Run-submission rules

`create_fbdl_run` is rate-limited by the server itself:

1. Only one `create_fbdl_run` can be in flight at a time.
2. After success there is a 30 second cooldown.
3. After failure there is a 60 second cooldown.
4. Validation failures do not trigger cooldown because they never hit the API.
5. If the account is at the max active-runs limit, the server may return a `hint` telling the caller to list and archive old runs first.

Do not silently retry or parallelize submissions.

## Never test against a real account

FBDL exists so you do not have to. If a repro needs accounts, pages or groups that do not
exist yet, build them here — testing against real users is out of scope for the program.

## Errors worth handling differently

| `kind` | What it means | Do |
|---|---|---|
| `no-token` | Neither `BB_RESEARCH_TOKEN` nor `FBDL_API_TOKEN` is set | Tell the user to set one and restart the server. Do not retry. |
| `forbidden` | The account is not on the FBDL researcher allowlist | Stop. Every later call is latched off for the session. |
| `unauthorized` | Token expired | They last 60 days. Re-mint at facebook.com/whitehat/fbdl/generate_api_token/. |
| `bad-request` | The API rejected the request | The message carries the API's own words. Fix and resubmit; do not repeat unchanged. |
| `not-found` | No such run, or not yours | Check the id with `list_fbdl_runs`. |
| `rate-limited` | Hourly budget spent, or writes came too fast | Wait. This is the server's limit, not the local cooldown. |
| `local-budget` | The client refused before sending, to protect the same budget | The message says when the next call can run. |
| `network`, `server-error` | Transient | Already retried with backoff before you saw it. Report it; do not loop. |

## Budget

API calls are metered at 1000/hour per researcher, counted **separately for each of the
four routes**, and shared with anything else on the same token — including the
`meta-context` server and Zurp in Burp. Polling `get_fbdl_run` therefore cannot exhaust
your ability to create runs, and vice versa.

The read-only language tools cost nothing, so validate and explain freely; it is only
`create_fbdl_run`, `list_fbdl_runs`, `get_fbdl_run` and `archive_fbdl_run` that spend.

A `rate-limited` error says how long the wait is, and the wait is the rest of the hour.
Do not retry inside it.

If `validate_fbdl` reports that the spec is unavailable, the server could not load the
language reference and could not fall back to its cache. Run management still works; the
language tools do not.
