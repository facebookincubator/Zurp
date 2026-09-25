/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import type {FetchLike} from '../client.js';
import type {CoreConfig} from '../config.js';

/**
 * Retries and pacing are switched off by default here so a test that is about parsing
 * does not accidentally also assert timing. The production defaults are covered by the
 * `loadCoreConfig` tests; tests about retrying or pacing override these explicitly.
 */
export function testConfig(overrides: Partial<CoreConfig> = {}): CoreConfig {
  return {
    accessToken: 'EAAB-test',
    baseUrl: 'https://api.facebook.com',
    timeoutMs: 1000,
    insecureTls: false,
    logLevel: 'silent',
    rateLimit: {perHour: 3_600_000, burst: 1000, maxWaitMs: 0},
    retry: {attempts: 1, baseDelayMs: 0, maxDelayMs: 0},
    maxConcurrency: 4,
    ...overrides,
  };
}

export interface Call {
  readonly url: string;
  readonly method: string;
  readonly headers: Record<string, string>;
  readonly body: string | undefined;
}

export interface StubResponse {
  readonly status: number;
  readonly body: string;
  readonly headers?: Record<string, string>;
}

export interface Stub {
  readonly fetchImpl: FetchLike;
  readonly calls: Call[];
}

/** Replays `responses` in order, repeating the last one once the list runs out. */
export function stubFetch(responses: readonly StubResponse[]): Stub {
  const calls: Call[] = [];
  let index = 0;
  const fetchImpl: FetchLike = async (url, init) => {
    calls.push({url, method: init.method, headers: init.headers, body: init.body});
    const response = responses[Math.min(index, responses.length - 1)];
    index += 1;
    if (response === undefined) {
      throw new Error('stub called with no responses configured');
    }
    const headers = response.headers ?? {};
    return {
      status: response.status,
      headers: {get: (name: string) => headers[name.toLowerCase()] ?? null},
      text: async () => response.body,
    };
  };
  return {fetchImpl, calls};
}

/** Records what a retry loop would have slept, without actually sleeping. */
export function recordingSleep(): {sleep: (ms: number) => Promise<void>; slept: number[]} {
  const slept: number[] = [];
  return {
    slept,
    sleep: async (ms: number) => {
      slept.push(ms);
    },
  };
}
