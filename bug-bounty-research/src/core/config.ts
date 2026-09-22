/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import {type LogLevel, parseLogLevel} from './log.js';

/**
 * Configuration shared by every server in this package.
 *
 * Each setting is read from a server-specific variable first and a shared
 * `BB_RESEARCH_*` variable second. That fallback exists because both endpoint families
 * are gated on the same capability and served by the same OAuth app, so one token really
 * does drive both — a researcher should be able to say it once.
 *
 * Proxy selection is deliberately absent: it comes from the conventional
 * HTTPS_PROXY / HTTP_PROXY / NO_PROXY variables (see `http.ts`), which is what the rest
 * of a researcher's tooling already reads.
 */
export interface CoreConfig {
  /** Bearer token sent to the endpoint. Empty when unset. */
  readonly accessToken: string;
  /** Host serving the endpoint, normalised without a trailing slash. */
  readonly baseUrl: string;
  readonly timeoutMs: number;
  /** Skips certificate verification. Opt-in, for intercepting your own traffic. */
  readonly insecureTls: boolean;
  readonly logLevel: LogLevel;
  readonly rateLimit: RateLimitConfig;
  readonly retry: RetryConfig;
  /** Requests in flight at once. */
  readonly maxConcurrency: number;
}

export interface RateLimitConfig {
  readonly perHour: number;
  readonly burst: number;
  /** Longer than this and a call reports the budget instead of stalling on it. */
  readonly maxWaitMs: number;
}

export interface RetryConfig {
  readonly attempts: number;
  readonly baseDelayMs: number;
  readonly maxDelayMs: number;
}

export interface ConfigSpec {
  /** Prefix for this server's own variables, e.g. `ZURP` or `FBDL`. */
  readonly envPrefix: string;
  /** Token variables to try before `BB_RESEARCH_TOKEN`, in order. */
  readonly tokenVars: readonly string[];
  readonly defaultBaseUrl?: string;
}

export const SHARED_PREFIX = 'BB_RESEARCH';
export const SHARED_TOKEN_VAR = `${SHARED_PREFIX}_TOKEN`;

const DEFAULT_BASE_URL = 'https://api.facebook.com';
const DEFAULT_TIMEOUT_MS = 30_000;

/**
 * Matches the endpoints' own allowance: every researcher-facing route is declared
 * `HOURLY_1000_HITS`, counted per endpoint per researcher. Set it lower if other tooling
 * shares the token; raising it past the server's limit only converts local pacing into
 * 429s.
 */
const DEFAULT_RATE_LIMIT_PER_HOUR = 1000;
const DEFAULT_RATE_LIMIT_BURST = 20;
const DEFAULT_RATE_LIMIT_MAX_WAIT_MS = 5_000;

const DEFAULT_RETRY_ATTEMPTS = 3;
const DEFAULT_RETRY_BASE_DELAY_MS = 500;
const DEFAULT_RETRY_MAX_DELAY_MS = 20_000;

const DEFAULT_MAX_CONCURRENCY = 4;

/** Blank counts as unset, so an exported-but-empty variable falls back to the default. */
function read(env: NodeJS.ProcessEnv, name: string): string | undefined {
  const value = env[name];
  return value !== undefined && value.trim() !== '' ? value.trim() : undefined;
}

function firstSet(env: NodeJS.ProcessEnv, names: readonly string[]): string | undefined {
  for (const name of names) {
    const value = read(env, name);
    if (value !== undefined) {
      return value;
    }
  }
  return undefined;
}

function intInRange(raw: string | undefined, fallback: number, min: number, max: number): number {
  if (raw === undefined) {
    return fallback;
  }
  const parsed = Number.parseInt(raw, 10);
  if (!Number.isFinite(parsed)) {
    return fallback;
  }
  return Math.min(max, Math.max(min, parsed));
}

function boolean(raw: string | undefined, fallback: boolean): boolean {
  if (raw === undefined) {
    return fallback;
  }
  const value = raw.toLowerCase();
  if (['1', 'true', 'yes', 'on'].includes(value)) {
    return true;
  }
  if (['0', 'false', 'no', 'off'].includes(value)) {
    return false;
  }
  return fallback;
}

export function loadCoreConfig(spec: ConfigSpec, env: NodeJS.ProcessEnv = process.env): CoreConfig {
  /** Server-specific variable wins; the shared one is the fallback. */
  const setting = (name: string): string | undefined =>
    firstSet(env, [`${spec.envPrefix}_${name}`, `${SHARED_PREFIX}_${name}`]);

  const baseUrl = setting('API_BASE_URL') ?? spec.defaultBaseUrl ?? DEFAULT_BASE_URL;

  return {
    accessToken: firstSet(env, [...spec.tokenVars, SHARED_TOKEN_VAR]) ?? '',
    baseUrl: baseUrl.replace(/\/+$/, ''),
    timeoutMs: intInRange(setting('HTTP_TIMEOUT_MS'), DEFAULT_TIMEOUT_MS, 1_000, 600_000),
    insecureTls: boolean(setting('INSECURE_TLS'), false),
    logLevel: parseLogLevel(setting('LOG_LEVEL'), 'warn'),
    rateLimit: {
      perHour: intInRange(setting('RATE_LIMIT_PER_HOUR'), DEFAULT_RATE_LIMIT_PER_HOUR, 1, 1_000_000),
      burst: intInRange(setting('RATE_LIMIT_BURST'), DEFAULT_RATE_LIMIT_BURST, 1, 1_000),
      maxWaitMs: intInRange(
        setting('RATE_LIMIT_MAX_WAIT_MS'),
        DEFAULT_RATE_LIMIT_MAX_WAIT_MS,
        0,
        300_000,
      ),
    },
    retry: {
      attempts: intInRange(setting('RETRY_ATTEMPTS'), DEFAULT_RETRY_ATTEMPTS, 1, 10),
      baseDelayMs: intInRange(setting('RETRY_BASE_DELAY_MS'), DEFAULT_RETRY_BASE_DELAY_MS, 0, 60_000),
      maxDelayMs: intInRange(setting('RETRY_MAX_DELAY_MS'), DEFAULT_RETRY_MAX_DELAY_MS, 0, 300_000),
    },
    maxConcurrency: intInRange(setting('MAX_CONCURRENCY'), DEFAULT_MAX_CONCURRENCY, 1, 32),
  };
}
