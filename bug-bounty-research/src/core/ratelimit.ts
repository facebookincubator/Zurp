/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

/**
 * Client-side pacing, mirroring the per-researcher hourly budget the endpoint enforces
 * server-side. The point is not to be the authority — the server is — but to spend the
 * budget on lookups the researcher meant to make. An agent that fans out over a large
 * report can otherwise burn an hour of quota in seconds and leave nothing for the
 * researcher's own tooling, which shares the same allowance on the same token.
 */

export class RateLimitExceededError extends Error {
  readonly retryAfterMs: number;

  constructor(message: string, retryAfterMs: number) {
    super(message);
    this.name = 'RateLimitExceededError';
    this.retryAfterMs = retryAfterMs;
  }
}

export interface RateLimiterOptions {
  readonly perHour: number;
  /** Requests admitted back-to-back before pacing applies. */
  readonly burst: number;
  /** Above this, `acquire` rejects instead of stalling the agent behind a long sleep. */
  readonly maxWaitMs: number;
  readonly now?: () => number;
  readonly sleep?: (ms: number) => Promise<void>;
}

export interface RateLimiterState {
  readonly tokens: number;
  readonly capacity: number;
  /** Milliseconds until the bucket is full again; 0 when it already is. */
  readonly refillsInMs: number;
}

const defaultSleep = (ms: number): Promise<void> =>
  new Promise((resolve) => {
    const timer = setTimeout(resolve, ms);
    // A pending sleep must not be the only thing keeping the process alive.
    timer.unref?.();
  });

export class RateLimiter {
  private readonly perMs: number;
  private readonly capacity: number;
  private readonly maxWaitMs: number;
  private readonly now: () => number;
  private readonly sleep: (ms: number) => Promise<void>;

  private tokens: number;
  private updatedAt: number;
  /** Admissions are chained so concurrent callers cannot both claim the last token. */
  private queue: Promise<void> = Promise.resolve();

  constructor(options: RateLimiterOptions) {
    this.perMs = options.perHour / 3_600_000;
    this.capacity = Math.max(1, options.burst);
    this.maxWaitMs = Math.max(0, options.maxWaitMs);
    this.now = options.now ?? Date.now;
    this.sleep = options.sleep ?? defaultSleep;
    this.tokens = this.capacity;
    this.updatedAt = this.now();
  }

  /**
   * Waits for a slot, or throws if the wait would exceed `maxWaitMs`. Failing fast beats
   * blocking: the caller can tell the researcher what the budget is doing, whereas a tool
   * call that hangs for forty minutes just looks broken.
   */
  async acquire(): Promise<void> {
    const admission = this.queue.then(
      () => this.admit(),
      () => this.admit(),
    );
    // Swallowed here only to keep the chain alive for the next caller; `admission` still
    // rejects for this one.
    this.queue = admission.then(
      () => {},
      () => {},
    );
    return admission;
  }

  /**
   * Deducts a token without waiting or refusing. A retry is already-admitted work that
   * has served its own backoff, so it must not be turned away by the limiter — but it
   * does still cost the server a hit, and the budget has to show that.
   */
  charge(): void {
    this.refill();
    this.tokens -= 1;
  }

  /**
   * Drains the bucket after the server said 429. Without this the client keeps firing
   * optimistically against a budget it has already been told is gone.
   */
  penalize(retryAfterMs: number): void {
    this.refill();
    this.tokens = Math.min(this.tokens, 0);
    if (retryAfterMs > 0) {
      // Rewind the clock so the next token is not available until the server's window
      // has passed.
      this.updatedAt = this.now() + retryAfterMs - 1 / this.perMs;
    }
  }

  state(): RateLimiterState {
    this.refill();
    const deficit = this.capacity - this.tokens;
    return {
      tokens: Math.max(0, Math.floor(this.tokens)),
      capacity: this.capacity,
      refillsInMs: deficit <= 0 ? 0 : Math.ceil(deficit / this.perMs),
    };
  }

  private async admit(): Promise<void> {
    this.refill();
    if (this.tokens < 1) {
      const waitMs = Math.ceil((1 - this.tokens) / this.perMs);
      if (waitMs > this.maxWaitMs) {
        throw new RateLimitExceededError(
          `Local request budget is spent. The next lookup can run in ${describe(waitMs)}. ` +
            'This mirrors the hourly per-researcher limit the endpoint enforces, and is ' +
            'shared with anything else using the same token.',
          waitMs,
        );
      }
      await this.sleep(waitMs);
      this.refill();
    }
    this.tokens -= 1;
  }

  private refill(): void {
    const at = this.now();
    if (at > this.updatedAt) {
      this.tokens = Math.min(this.capacity, this.tokens + (at - this.updatedAt) * this.perMs);
      this.updatedAt = at;
    }
  }
}

function describe(ms: number): string {
  if (ms < 1000) {
    return `${ms}ms`;
  }
  const seconds = Math.ceil(ms / 1000);
  if (seconds < 120) {
    return `${seconds}s`;
  }
  return `${Math.ceil(seconds / 60)}min`;
}

/** Caps requests in flight, so a batch fan-out cannot open an unbounded socket count. */
export class Semaphore {
  private available: number;
  private readonly waiting: Array<() => void> = [];

  constructor(permits: number) {
    this.available = Math.max(1, permits);
  }

  async run<T>(task: () => Promise<T>): Promise<T> {
    if (this.available > 0) {
      this.available -= 1;
    } else {
      await new Promise<void>((resolve) => this.waiting.push(resolve));
    }
    try {
      return await task();
    } finally {
      const next = this.waiting.shift();
      if (next !== undefined) {
        next();
      } else {
        this.available += 1;
      }
    }
  }
}
