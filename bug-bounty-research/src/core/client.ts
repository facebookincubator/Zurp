/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

/**
 * The HTTP client both servers are built on.
 *
 * Everything that is a property of *being a bug bounty researcher's client* lives here
 * rather than in either server: the bearer token, the hourly budget, retry pacing, the
 * shared RFC 7807 error envelope, and the rule that a credential never reaches a
 * transcript. The servers above it only know their own routes and payload shapes.
 */

import type {CoreConfig} from './config.js';
import {ApiError} from './errors.js';
import {backoffDelayMs, isRetryableStatus, parseRetryAfter, type RetryPolicy} from './http.js';
import {type Logger, redact, silentLogger} from './log.js';
import {RateLimiter, RateLimitExceededError, Semaphore} from './ratelimit.js';
import {userAgent} from './version.js';

const BODY_EXCERPT_LIMIT = 512;

export interface FetchResponseLike {
  readonly status: number;
  readonly headers?: {get(name: string): string | null};
  text(): Promise<string>;
}

export type FetchLike = (
  input: string,
  init: {
    method: string;
    headers: Record<string, string>;
    body?: string;
    signal?: AbortSignal;
  },
) => Promise<FetchResponseLike>;

export interface RestClientDeps {
  readonly fetch?: FetchLike;
  readonly logger?: Logger;
  readonly limiter?: RateLimiter;
  readonly semaphore?: Semaphore;
  readonly sleep?: (ms: number) => Promise<void>;
  readonly random?: () => number;
}

export type QueryParams = Readonly<Record<string, string | number | boolean | undefined>>;

export interface RequestOptions {
  readonly query?: QueryParams;
  readonly body?: unknown;
  /** Wording for a 404, which only means something in the caller's vocabulary. */
  readonly missingLabel?: string;
  /**
   * Which local budget this call spends from. The server counts per endpoint, so a
   * client that pools every route into one bucket either throttles itself early or —
   * worse — lets the tightest route overspend. Parameterised paths must pass this
   * explicitly or each id would get a bucket of its own.
   */
  readonly bucket?: string;
  /** This endpoint's own hourly allowance, when it differs from the default. */
  readonly budgetPerHour?: number;
}

export type Json = Record<string, unknown>;

const defaultSleep = (ms: number): Promise<void> =>
  new Promise((resolve) => {
    const timer = setTimeout(resolve, ms);
    // A pending sleep must not be the only thing keeping the process alive.
    timer.unref?.();
  });

function excerpt(body: string): string {
  return body.length <= BODY_EXCERPT_LIMIT
    ? body
    : `${body.slice(0, BODY_EXCERPT_LIMIT)}… (${body.length} bytes)`;
}

/**
 * These endpoints answer errors as RFC 7807 problem documents. `detail` is the part
 * written for a human — surfacing it beats echoing the raw body, which is mostly braces.
 */
export function describeProblem(body: string): string {
  try {
    const parsed: unknown = JSON.parse(body);
    if (parsed !== null && typeof parsed === 'object') {
      const {title, detail} = parsed as {title?: unknown; detail?: unknown};
      const parts = [
        typeof title === 'string' ? title : '',
        typeof detail === 'string' && detail !== title ? detail : '',
      ].filter((part) => part !== '');
      if (parts.length > 0) {
        return parts.join(': ');
      }
    }
  } catch {
    // Not a problem document; fall through to the raw excerpt.
  }
  return excerpt(body);
}

interface Attempt {
  readonly status: number;
  readonly text: string;
  readonly retryAfterMs: number | undefined;
}

export class RestClient {
  readonly config: CoreConfig;
  private readonly serverName: string;
  private readonly fetchImpl: FetchLike;
  private readonly logger: Logger;
  /** One bucket per endpoint, mirroring how the server keys its counters. */
  private readonly limiters = new Map<string, RateLimiter>();
  private readonly semaphore: Semaphore;
  private readonly sleep: (ms: number) => Promise<void>;
  private readonly random: () => number;
  private readonly retry: RetryPolicy;
  /** Tests inject one limiter and share it across buckets, to keep counting simple. */
  private readonly injectedLimiter: RateLimiter | undefined;

  /**
   * Latched on 403. Enrolment in the researcher allowlist is a property of the account,
   * not of the request, so once refused every later call would be refused too — and each
   * one still spends a slot of the hourly budget.
   */
  private forbidden: ApiError | undefined;

  constructor(serverName: string, config: CoreConfig, deps: RestClientDeps = {}) {
    this.serverName = serverName;
    this.config = config;
    this.fetchImpl = deps.fetch ?? (globalThis.fetch as unknown as FetchLike);
    this.logger = deps.logger ?? silentLogger;
    this.sleep = deps.sleep ?? defaultSleep;
    this.random = deps.random ?? Math.random;
    this.retry = config.retry;
    this.injectedLimiter = deps.limiter;
    this.semaphore = deps.semaphore ?? new Semaphore(config.maxConcurrency);
  }

  /**
   * The bucket's allowance is the tighter of what the server permits and what the
   * researcher configured, so lowering `RATE_LIMIT_PER_HOUR` to share a token still
   * tightens the endpoints that already sit below the default.
   */
  private limiterFor(bucket: string, budgetPerHour: number | undefined): RateLimiter {
    const existing = this.limiters.get(bucket);
    if (existing !== undefined) {
      return existing;
    }
    const perHour = Math.min(this.config.rateLimit.perHour, budgetPerHour ?? Number.MAX_SAFE_INTEGER);
    const limiter =
      this.injectedLimiter ??
      new RateLimiter({
        perHour,
        burst: Math.min(this.config.rateLimit.burst, perHour),
        maxWaitMs: this.config.rateLimit.maxWaitMs,
      });
    this.limiters.set(bucket, limiter);
    return limiter;
  }

  /** Remaining local budget per endpoint, for reporting rather than for control flow. */
  budget(): Record<string, {tokens: number; capacity: number; refillsInMs: number}> {
    const out: Record<string, {tokens: number; capacity: number; refillsInMs: number}> = {};
    for (const [bucket, limiter] of this.limiters) {
      out[bucket] = limiter.state();
    }
    return out;
  }

  /** Error text reaches the agent transcript, so it must never carry the credential. */
  scrub(text: string): string {
    return redact(text, this.config.accessToken);
  }

  async request(
    method: 'GET' | 'POST' | 'DELETE',
    path: string,
    options: RequestOptions = {},
  ): Promise<Json> {
    if (this.forbidden !== undefined) {
      throw this.forbidden;
    }
    if (this.config.accessToken === '') {
      throw new ApiError(
        'no-token',
        'No access token is configured. Mint one at ' +
          'https://www.facebook.com/whitehat/fbdl/generate_api_token/ and export it, then ' +
          'restart the MCP server.',
      );
    }

    const url = this.config.baseUrl + path + renderQuery(options.query);
    const limiter = this.limiterFor(options.bucket ?? `${method} ${path}`, options.budgetPerHour);
    let lastFailure: ApiError | undefined;

    // Admission is charged once for the request, not once per attempt. A retry has
    // already served the backoff the server asked for, so making it queue again behind
    // the bucket it just drained would refuse the one call most likely to succeed.
    await this.reserveSlot(limiter);

    for (let attempt = 1; attempt <= this.retry.attempts; attempt += 1) {
      if (attempt > 1) {
        limiter.charge();
      }

      let outcome: Attempt;
      try {
        outcome = await this.semaphore.run(() => this.attempt(method, url, options.body));
      } catch (error) {
        const reason = error instanceof Error ? error.message : String(error);
        lastFailure = new ApiError('network', this.scrub(`${method} ${url} failed: ${reason}`));
        if (attempt < this.retry.attempts) {
          await this.pause(attempt, undefined, `network error (${reason})`);
          continue;
        }
        throw lastFailure;
      }

      const {status, text, retryAfterMs} = outcome;
      if (status >= 200 && status < 300) {
        return this.parseBody(method, url, status, text);
      }
      if (status === 429) {
        // The server has already said the budget is gone; stop the local limiter from
        // waving through calls that will only be refused again.
        limiter.penalize(retryAfterMs ?? 0);
      }

      lastFailure = this.errorFor(status, text, url, options.missingLabel, retryAfterMs);
      if (isRetryableStatus(status) && attempt < this.retry.attempts) {
        // These endpoints answer 429 with Retry-After set to the whole window — an hour.
        // Clamping that to the backoff ceiling and trying anyway just spends two more
        // requests against a budget the server has already said is gone.
        if (retryAfterMs !== undefined && retryAfterMs > this.retry.maxDelayMs) {
          this.logger.warn(
            `HTTP ${status} with Retry-After ${Math.ceil(retryAfterMs / 1000)}s, longer than ` +
              'the retry ceiling; reporting instead of retrying.',
          );
          throw lastFailure;
        }
        await this.pause(attempt, retryAfterMs, `HTTP ${status}`);
        continue;
      }
      throw lastFailure;
    }

    throw lastFailure ?? new ApiError('network', this.scrub(`${method} ${url} failed.`));
  }

  private async reserveSlot(limiter: RateLimiter): Promise<void> {
    try {
      await limiter.acquire();
    } catch (error) {
      if (error instanceof RateLimitExceededError) {
        throw new ApiError('local-budget', error.message, 429);
      }
      throw error;
    }
  }

  private async pause(
    attempt: number,
    retryAfterMs: number | undefined,
    why: string,
  ): Promise<void> {
    // These endpoints do not send Retry-After, so backoff is the mechanism rather than
    // the fallback. The header is still honoured if one ever appears.
    const delay = backoffDelayMs(attempt, this.retry, retryAfterMs, this.random);
    this.logger.warn(
      `${why}; retrying in ${delay}ms (attempt ${attempt + 1} of ${this.retry.attempts}).`,
    );
    await this.sleep(delay);
  }

  private async attempt(method: string, url: string, body?: unknown): Promise<Attempt> {
    const headers: Record<string, string> = {
      // The token travels in this header only; the query-string form is a legacy Graph
      // trait these endpoints do not accept.
      Authorization: `Bearer ${this.config.accessToken}`,
      Accept: 'application/json',
      'User-Agent': userAgent(this.serverName),
    };
    if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
    }

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.config.timeoutMs);
    try {
      this.logger.debug(`${method} ${url}`);
      const response = await this.fetchImpl(url, {
        method,
        headers,
        ...(body !== undefined ? {body: JSON.stringify(body)} : {}),
        signal: controller.signal,
      });
      return {
        status: response.status,
        text: await response.text(),
        retryAfterMs: parseRetryAfter(response.headers?.get('retry-after')),
      };
    } finally {
      clearTimeout(timer);
    }
  }

  private parseBody(method: string, url: string, status: number, text: string): Json {
    // 201 with an empty body is legal; treat it as an empty document rather than a fault.
    if (text.trim() === '') {
      return {};
    }
    let parsed: unknown;
    try {
      parsed = JSON.parse(text);
    } catch {
      throw new ApiError(
        'malformed',
        this.scrub(
          `${method} ${url} returned ${status} with a body that is not JSON: ${excerpt(text)}`,
        ),
      );
    }
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new ApiError(
        'malformed',
        this.scrub(`${method} ${url} returned ${status} with a non-object body: ${excerpt(text)}`),
      );
    }
    return parsed as Json;
  }

  private errorFor(
    status: number,
    body: string,
    url: string,
    missingLabel = 'The requested object',
    retryAfterMs?: number,
  ): ApiError {
    // The problem document is where the API names the allowlist, the quota or the
    // validation failure, which is the only actionable part of a 4xx.
    const detail = body.trim() === '' ? '' : this.scrub(` ${describeProblem(body)}`);

    if (status === 403) {
      this.forbidden = new ApiError(
        'forbidden',
        'This account is not enrolled in the bug bounty researcher API allowlist, so no ' +
          'further calls will be attempted for the rest of this session.' +
          detail,
        403,
      );
      return this.forbidden;
    }
    if (status === 401) {
      return new ApiError(
        'unauthorized',
        'The access token was rejected. Researcher tokens last 60 days — re-mint at ' +
          `https://www.facebook.com/whitehat/fbdl/generate_api_token/ if yours has expired.${detail}`,
        401,
      );
    }
    if (status === 404) {
      return new ApiError('not-found', `${missingLabel} was not found.${detail}`, 404);
    }
    if (status === 400) {
      return new ApiError('bad-request', `The endpoint rejected the request.${detail}`, 400);
    }
    if (status === 429) {
      // Each endpoint has its own hourly counter, so this says nothing about the others.
      const wait =
        retryAfterMs !== undefined
          ? ` Retry after ${Math.ceil(retryAfterMs / 60_000)} minutes; other endpoints have separate budgets.`
          : '';
      return new ApiError(
        'rate-limited',
        `This endpoint's hourly budget is spent.${detail}${wait}`,
        429,
      );
    }
    return new ApiError('server-error', `${url} returned ${status}.${detail}`, status);
  }
}

function renderQuery(query: QueryParams | undefined): string {
  if (query === undefined) {
    return '';
  }
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined) {
      params.set(key, String(value));
    }
  }
  const rendered = params.toString();
  return rendered === '' ? '' : `?${rendered}`;
}
