/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

/**
 * Concurrency + rate-limit guard for the create_fbdl_run tool.
 *
 * Two rules enforced together:
 *   1. Only ONE create_fbdl_run can be in flight at a time. A second call while
 *      one is running is rejected immediately.
 *   2. After a completed call, a cooldown blocks further calls — 30s after a
 *      successful submission, 60s after a failed one. Failures get a longer
 *      backoff so a retry storm doesn't hammer the API.
 *
 * Validation-only rejections (the script never hit the API) do NOT trigger a
 * cooldown — the caller can fix the script and resubmit immediately.
 */

export const SUCCESS_COOLDOWN_MS = 30_000;
export const FAILURE_COOLDOWN_MS = 60_000;

export type RunOutcome = "success" | "failure";

export interface RunSlot {
  release(outcome: RunOutcome): void;
}

export type AcquireResult =
  | { ok: true; slot: RunSlot }
  | {
      ok: false;
      reason: "in_flight" | "cooldown";
      message: string;
      cooldownRemainingMs?: number;
    };

interface GuardState {
  inFlight: boolean;
  cooldownUntil: number;
  lastOutcome: RunOutcome | null;
}

const state: GuardState = {
  inFlight: false,
  cooldownUntil: 0,
  lastOutcome: null,
};

/**
 * Try to claim the single create_fbdl_run slot.
 *
 * On success the caller gets a {@link RunSlot} and MUST call `slot.release()`
 * exactly once when the run finishes (success or failure), so the next call
 * can proceed and the cooldown clock is started. Use a `try { … } finally { }`
 * around the work to guarantee the release.
 *
 * On failure the result describes whether another call is in flight or a
 * cooldown is active, with a human-readable `message` ready to bubble up to
 * the MCP client.
 */
export function tryAcquireCreateSlot(now: number = Date.now()): AcquireResult {
  if (state.inFlight) {
    return {
      ok: false,
      reason: "in_flight",
      message:
        "Another create_fbdl_run call is already in flight. Only one run can be submitted at a time — wait for it to finish before submitting another.",
    };
  }

  if (state.cooldownUntil > now) {
    const remaining = state.cooldownUntil - now;
    const previous = state.lastOutcome === "failure" ? "failed" : "successful";
    return {
      ok: false,
      reason: "cooldown",
      message: `Cooldown active after a ${previous} create_fbdl_run call — wait ${Math.ceil(
        remaining / 1000,
      )}s before submitting another run.`,
      cooldownRemainingMs: remaining,
    };
  }

  state.inFlight = true;
  return {
    ok: true,
    slot: {
      release(outcome: RunOutcome): void {
        state.inFlight = false;
        state.lastOutcome = outcome;
        state.cooldownUntil =
          Date.now() + (outcome === "success" ? SUCCESS_COOLDOWN_MS : FAILURE_COOLDOWN_MS);
      },
    },
  };
}

/** Reset all guard state. Intended for tests. */
export function resetRunGuard(): void {
  state.inFlight = false;
  state.cooldownUntil = 0;
  state.lastOutcome = null;
}
