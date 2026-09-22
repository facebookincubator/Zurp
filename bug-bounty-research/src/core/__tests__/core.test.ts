/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import assert from 'node:assert/strict';
import {test} from 'node:test';

import {describeProblem, RestClient} from '../client.js';
import {loadCoreConfig, SHARED_TOKEN_VAR} from '../config.js';
import {ApiError} from '../errors.js';
import {backoffDelayMs, isProxyBypassed, isRetryableStatus, parseRetryAfter, resolveProxy, scrubCredentials} from '../http.js';
import {createLogger, redact} from '../log.js';
import {RateLimiter, RateLimitExceededError, Semaphore} from '../ratelimit.js';
import {recordingSleep, stubFetch, testConfig} from './support.js';

const SPEC = {envPrefix: 'ZURP', tokenVars: ['ZURP_ACCESS_TOKEN']};

async function expectError(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (error) {
    assert.ok(error instanceof ApiError, `expected ApiError, got ${String(error)}`);
    return error;
  }
  throw new Error('expected the call to reject');
}

// ---------------------------------------------------------------- config

test('config defaults to the public host and treats a blank variable as unset', () => {
  const config = loadCoreConfig(SPEC, {ZURP_ACCESS_TOKEN: '  ', ZURP_API_BASE_URL: ''});
  assert.equal(config.accessToken, '');
  assert.equal(config.baseUrl, 'https://api.facebook.com');
  assert.equal(config.timeoutMs, 30_000);
});

test('config ships the endpoint budget and a retry policy by default', () => {
  const config = loadCoreConfig(SPEC, {});
  assert.equal(config.rateLimit.perHour, 1000);
  assert.equal(config.retry.attempts, 3);
  assert.equal(config.maxConcurrency, 4);
  assert.equal(config.insecureTls, false);
  assert.equal(config.logLevel, 'warn');
});

test('config trims the token and strips trailing slashes from the base URL', () => {
  const config = loadCoreConfig(SPEC, {
    ZURP_ACCESS_TOKEN: ' EAAB-token \n',
    ZURP_API_BASE_URL: 'https://api.example.internal///',
    ZURP_HTTP_TIMEOUT_MS: '5000',
  });
  assert.equal(config.accessToken, 'EAAB-token');
  assert.equal(config.baseUrl, 'https://api.example.internal');
  assert.equal(config.timeoutMs, 5000);
});

test('a nonsense or out-of-range number clamps rather than disabling the guard', () => {
  assert.equal(loadCoreConfig(SPEC, {ZURP_HTTP_TIMEOUT_MS: 'soon'}).timeoutMs, 30_000);
  assert.equal(loadCoreConfig(SPEC, {ZURP_HTTP_TIMEOUT_MS: '-1'}).timeoutMs, 1_000);
  assert.equal(loadCoreConfig(SPEC, {ZURP_RETRY_ATTEMPTS: '999'}).retry.attempts, 10);
});

test('the shared token serves a server that has no variable of its own', () => {
  const config = loadCoreConfig(SPEC, {[SHARED_TOKEN_VAR]: 'shared-token'});
  assert.equal(config.accessToken, 'shared-token');
});

test('a server-specific variable wins over the shared one', () => {
  const env = {ZURP_ACCESS_TOKEN: 'mine', [SHARED_TOKEN_VAR]: 'shared', ZURP_LOG_LEVEL: 'debug', BB_RESEARCH_LOG_LEVEL: 'error'};
  const config = loadCoreConfig(SPEC, env);
  assert.equal(config.accessToken, 'mine');
  assert.equal(config.logLevel, 'debug');
});

test('a shared setting applies to a server that did not override it', () => {
  const config = loadCoreConfig(SPEC, {BB_RESEARCH_RATE_LIMIT_PER_HOUR: '250'});
  assert.equal(config.rateLimit.perHour, 250);
});

// ---------------------------------------------------------------- redaction

test('redact blanks the configured token wherever it appears', () => {
  const text = redact('sent Bearer EAAB-secret-token to the API', 'EAAB-secret-token');
  assert.ok(!text.includes('EAAB-secret-token'));
});

test('redact blanks credential shapes it was never told about', () => {
  assert.ok(!redact('Authorization: Bearer abc.def-ghi').includes('abc.def-ghi'));
  assert.ok(!redact('?access_token=SUPERSECRETVALUE&x=1').includes('SUPERSECRETVALUE'));
  assert.ok(!redact('token EAABwzLixnjYBO1234567890').includes('EAABwzLixnjYBO1234567890'));
});

test('redact leaves a short string alone rather than blanking common words', () => {
  assert.equal(redact('the run failed', 'run'), 'the run failed');
});

test('the logger writes to the given sink, honours the level, and redacts', () => {
  const lines: string[] = [];
  const logger = createLogger({
    level: 'warn',
    name: 'fbdl',
    secrets: ['EAAB-secret'],
    write: (chunk) => lines.push(chunk),
  });
  logger.debug('not emitted at warn');
  logger.warn('using EAAB-secret now');

  assert.equal(lines.length, 1);
  assert.match(lines[0] ?? '', /\[fbdl\]/);
  assert.match(lines[0] ?? '', /warn/);
  assert.ok(!(lines[0] ?? '').includes('EAAB-secret'));
});

// ---------------------------------------------------------------- proxy

test('a proxy is selected from the conventional variables', () => {
  const proxy = resolveProxy(
    {HTTPS_PROXY: 'http://127.0.0.1:8080'},
    new URL('https://api.facebook.com'),
  );
  assert.equal(proxy.url, 'http://127.0.0.1:8080');
  assert.equal(proxy.reason, 'selected');
});

test('the shared variable overrides the conventional one', () => {
  const proxy = resolveProxy(
    {BB_RESEARCH_PROXY_URL: 'http://burp:8080', HTTPS_PROXY: 'http://other:3128'},
    new URL('https://api.facebook.com'),
  );
  assert.equal(proxy.url, 'http://burp:8080');
});

test('an http target reads HTTP_PROXY rather than HTTPS_PROXY', () => {
  const env = {HTTP_PROXY: 'http://plain:8080', HTTPS_PROXY: 'http://tls:8080'};
  assert.equal(resolveProxy(env, new URL('http://api.example.test')).url, 'http://plain:8080');
  assert.equal(resolveProxy(env, new URL('https://api.example.test')).url, 'http://tls:8080');
});

test('NO_PROXY exempts a host, a parent domain and a wildcard', () => {
  const target = new URL('https://api.facebook.com');
  assert.ok(isProxyBypassed({NO_PROXY: 'api.facebook.com'}, target));
  assert.ok(isProxyBypassed({NO_PROXY: '.facebook.com'}, target));
  assert.ok(isProxyBypassed({NO_PROXY: 'facebook.com'}, target));
  assert.ok(isProxyBypassed({NO_PROXY: 'example.com, *'}, target));
  assert.ok(!isProxyBypassed({NO_PROXY: 'notfacebook.com'}, target));
  assert.ok(!isProxyBypassed({NO_PROXY: 'facebook.com:8443'}, target));
});

test('NO_PROXY beats a configured proxy', () => {
  const proxy = resolveProxy(
    {HTTPS_PROXY: 'http://burp:8080', NO_PROXY: 'facebook.com'},
    new URL('https://api.facebook.com'),
  );
  assert.equal(proxy.url, undefined);
  assert.equal(proxy.reason, 'bypassed-by-no-proxy');
});

test('proxy credentials are scrubbed before they reach a log line', () => {
  assert.equal(
    scrubCredentials('http://user:hunter2@burp.local:8080'),
    'http://%3Credacted%3E@burp.local:8080/',
  );
  assert.equal(scrubCredentials('not a url'), 'not a url');
});

// ---------------------------------------------------------------- retry maths

test('only genuinely transient statuses are retried', () => {
  assert.ok(isRetryableStatus(429));
  assert.ok(isRetryableStatus(500));
  assert.ok(isRetryableStatus(503));
  assert.ok(!isRetryableStatus(401));
  assert.ok(!isRetryableStatus(403));
  assert.ok(!isRetryableStatus(400));
  assert.ok(!isRetryableStatus(404));
});

test('Retry-After is read as seconds or as an HTTP date', () => {
  assert.equal(parseRetryAfter('30'), 30_000);
  const now = Date.parse('2026-01-01T00:00:00Z');
  assert.equal(parseRetryAfter('Thu, 01 Jan 2026 00:00:20 GMT', now), 20_000);
  assert.equal(parseRetryAfter(null), undefined);
  assert.equal(parseRetryAfter('   '), undefined);
  assert.equal(parseRetryAfter('soon'), undefined);
});

test('backoff grows exponentially, is jittered, and is capped', () => {
  const policy = {attempts: 5, baseDelayMs: 100, maxDelayMs: 1000};
  assert.equal(backoffDelayMs(1, policy, undefined, () => 1), 100);
  assert.equal(backoffDelayMs(2, policy, undefined, () => 1), 200);
  assert.equal(backoffDelayMs(4, policy, undefined, () => 1), 800);
  assert.equal(backoffDelayMs(9, policy, undefined, () => 1), 1000);
  // Full jitter: the same attempt can come back anywhere below the cap.
  assert.equal(backoffDelayMs(4, policy, undefined, () => 0), 0);
});

test('Retry-After overrides the computed backoff but not the ceiling', () => {
  const policy = {attempts: 5, baseDelayMs: 100, maxDelayMs: 1000};
  assert.equal(backoffDelayMs(1, policy, 500, () => 1), 500);
  assert.equal(backoffDelayMs(1, policy, 90_000, () => 1), 1000);
});

// ---------------------------------------------------------------- rate limiter

test('the limiter admits up to the burst and then paces', async () => {
  let clock = 0;
  const limiter = new RateLimiter({
    perHour: 3600, // one per second
    burst: 2,
    maxWaitMs: 10_000,
    now: () => clock,
    sleep: async (ms) => {
      clock += ms;
    },
  });

  await limiter.acquire();
  await limiter.acquire();
  assert.equal(clock, 0, 'the burst is admitted without waiting');

  await limiter.acquire();
  assert.equal(clock, 1000, 'the next one waits for a token to refill');
});

test('the limiter refuses rather than stalling past the maximum wait', async () => {
  let clock = 0;
  const limiter = new RateLimiter({
    perHour: 1,
    burst: 1,
    maxWaitMs: 1000,
    now: () => clock,
    sleep: async (ms) => {
      clock += ms;
    },
  });

  await limiter.acquire();
  await assert.rejects(() => limiter.acquire(), RateLimitExceededError);
});

test('concurrent acquires cannot both claim the last token', async () => {
  let clock = 0;
  const limiter = new RateLimiter({
    perHour: 3600,
    burst: 1,
    maxWaitMs: 10_000,
    now: () => clock,
    sleep: async (ms) => {
      clock += ms;
    },
  });

  await Promise.all([limiter.acquire(), limiter.acquire(), limiter.acquire()]);
  assert.equal(clock, 2000, 'two of the three had to wait a second each');
});

test('a rejected acquire does not wedge the queue for the next caller', async () => {
  let clock = 0;
  const limiter = new RateLimiter({
    perHour: 3600,
    burst: 1,
    maxWaitMs: 0,
    now: () => clock,
    sleep: async (ms) => {
      clock += ms;
    },
  });

  await limiter.acquire();
  await assert.rejects(() => limiter.acquire(), RateLimitExceededError);
  clock += 5000;
  await limiter.acquire();
});

test('a server 429 drains the local bucket', async () => {
  let clock = 0;
  const limiter = new RateLimiter({perHour: 3_600_000, burst: 10, maxWaitMs: 0, now: () => clock});
  assert.equal(limiter.state().tokens, 10);
  limiter.penalize(60_000);
  assert.equal(limiter.state().tokens, 0);
  await assert.rejects(() => limiter.acquire(), RateLimitExceededError);
});

test('the semaphore caps work in flight', async () => {
  const semaphore = new Semaphore(2);
  let inFlight = 0;
  let peak = 0;
  const task = async (): Promise<void> => {
    inFlight += 1;
    peak = Math.max(peak, inFlight);
    await new Promise((resolve) => setImmediate(resolve));
    inFlight -= 1;
  };

  await Promise.all(Array.from({length: 6}, () => semaphore.run(task)));
  assert.equal(peak, 2);
});

test('the semaphore releases its permit when the task throws', async () => {
  const semaphore = new Semaphore(1);
  await assert.rejects(() => semaphore.run(async () => Promise.reject(new Error('boom'))));
  await semaphore.run(async () => 'ok');
});

// ---------------------------------------------------------------- problem documents

test('an RFC 7807 problem document is rendered as its human-readable parts', () => {
  assert.equal(
    describeProblem('{"title":"Invalid FBDL script","detail":"Unknown action `foo`","status":400}'),
    'Invalid FBDL script: Unknown action `foo`',
  );
  assert.equal(
    describeProblem('{"title":"Authentication Error","detail":"Authentication Error","status":401}'),
    'Authentication Error',
  );
});

test('a body that is not a problem document is passed through', () => {
  assert.equal(describeProblem('<html>login</html>'), '<html>login</html>');
});

// ---------------------------------------------------------------- client

test('the client sends the bearer token in the header and identifies itself', async () => {
  const {fetchImpl, calls} = stubFetch([{status: 200, body: '{}'}]);
  await new RestClient('meta-context', testConfig(), {fetch: fetchImpl}).request('GET', '/x', {
    query: {q: 'doc_id=1'},
  });

  assert.equal(calls[0]?.headers['Authorization'], 'Bearer EAAB-test');
  assert.match(calls[0]?.headers['User-Agent'] ?? '', /^bug-bounty-research\/\d+\.\d+\.\d+ \(meta-context\)$/);
  assert.equal(calls[0]?.url, 'https://api.facebook.com/x?q=doc_id%3D1');
  // The token must never travel in the URL.
  assert.ok(!(calls[0]?.url ?? '').includes('EAAB-test'));
});

test('no token configured fails before any request is made', async () => {
  const {fetchImpl, calls} = stubFetch([{status: 200, body: '{}'}]);
  const client = new RestClient('fbdl', testConfig({accessToken: ''}), {fetch: fetchImpl});

  const error = await expectError(client.request('GET', '/x'));
  assert.equal(error.kind, 'no-token');
  assert.equal(calls.length, 0);
});

test('403 latches, so a refused account stops spending its budget', async () => {
  const {fetchImpl, calls} = stubFetch([
    {status: 403, body: '{"title":"Forbidden","detail":"not enrolled"}'},
  ]);
  const client = new RestClient('fbdl', testConfig(), {fetch: fetchImpl});

  assert.equal((await expectError(client.request('GET', '/a'))).kind, 'forbidden');
  assert.equal((await expectError(client.request('GET', '/b'))).kind, 'forbidden');
  assert.equal(calls.length, 1, 'the second call must not reach the network');
});

test('each status maps to the kind that tells the researcher what to do', async () => {
  const kinds: Array<[number, string]> = [
    [400, 'bad-request'],
    [401, 'unauthorized'],
    [404, 'not-found'],
    [429, 'rate-limited'],
    [500, 'server-error'],
  ];
  for (const [status, kind] of kinds) {
    const client = new RestClient('fbdl', testConfig(), {
      fetch: stubFetch([{status, body: ''}]).fetchImpl,
    });
    assert.equal((await expectError(client.request('GET', '/a'))).kind, kind, `status ${status}`);
  }
});

test('the problem detail is surfaced in the error, not the raw body', async () => {
  const {fetchImpl} = stubFetch([
    {status: 400, body: '{"title":"Invalid FBDL script","detail":"line 2: unknown verb","status":400}'},
  ]);
  const error = await expectError(
    new RestClient('fbdl', testConfig(), {fetch: fetchImpl}).request('POST', '/a'),
  );
  assert.match(error.message, /Invalid FBDL script: line 2: unknown verb/);
});

test('a transient failure is retried and can succeed on a later attempt', async () => {
  const {fetchImpl, calls} = stubFetch([
    {status: 503, body: ''},
    {status: 200, body: '{"ok":true}'},
  ]);
  const {sleep, slept} = recordingSleep();
  const client = new RestClient('fbdl', testConfig({retry: {attempts: 3, baseDelayMs: 100, maxDelayMs: 400}}), {
    fetch: fetchImpl,
    sleep,
    random: () => 1,
  });

  assert.deepEqual(await client.request('GET', '/a'), {ok: true});
  assert.equal(calls.length, 2);
  assert.deepEqual(slept, [100]);
});

test('a rejected token is not retried, because the answer will not change', async () => {
  const {fetchImpl, calls} = stubFetch([{status: 401, body: ''}]);
  const {sleep, slept} = recordingSleep();
  const client = new RestClient('fbdl', testConfig({retry: {attempts: 3, baseDelayMs: 10, maxDelayMs: 10}}), {
    fetch: fetchImpl,
    sleep,
  });

  assert.equal((await expectError(client.request('GET', '/a'))).kind, 'unauthorized');
  assert.equal(calls.length, 1);
  assert.deepEqual(slept, []);
});

test('retries give up after the configured attempts and report the last failure', async () => {
  const {fetchImpl, calls} = stubFetch([{status: 500, body: ''}]);
  const {sleep} = recordingSleep();
  const client = new RestClient('fbdl', testConfig({retry: {attempts: 3, baseDelayMs: 1, maxDelayMs: 1}}), {
    fetch: fetchImpl,
    sleep,
  });

  assert.equal((await expectError(client.request('GET', '/a'))).kind, 'server-error');
  assert.equal(calls.length, 3);
});

test('a 429 carrying Retry-After waits exactly that long', async () => {
  const {fetchImpl} = stubFetch([
    {status: 429, body: '', headers: {'retry-after': '7'}},
    {status: 200, body: '{}'},
  ]);
  const {sleep, slept} = recordingSleep();
  const client = new RestClient('fbdl', testConfig({retry: {attempts: 2, baseDelayMs: 100, maxDelayMs: 60_000}}), {
    fetch: fetchImpl,
    sleep,
  });

  await client.request('GET', '/a');
  assert.deepEqual(slept, [7000]);
});

test('a retry still costs budget, even though it is not made to queue for it', async () => {
  const {fetchImpl} = stubFetch([
    {status: 503, body: ''},
    {status: 503, body: ''},
    {status: 200, body: '{}'},
  ]);
  const {sleep} = recordingSleep();
  // Frozen clock, so nothing refills mid-test and the count is the count.
  const limiter = new RateLimiter({perHour: 1000, burst: 5, maxWaitMs: 0, now: () => 0});
  const client = new RestClient(
    'fbdl',
    testConfig({retry: {attempts: 3, baseDelayMs: 1, maxDelayMs: 1}}),
    {fetch: fetchImpl, sleep, limiter},
  );

  await client.request('GET', '/a');
  assert.equal(limiter.state().tokens, 2, 'three server hits came out of a five-token bucket');
});

test('a 429 mid-retry does not refuse the retry it just scheduled', async () => {
  const {fetchImpl, calls} = stubFetch([
    {status: 429, body: '', headers: {'retry-after': '30'}},
    {status: 200, body: '{"ok":true}'},
  ]);
  const {sleep, slept} = recordingSleep();
  const limiter = new RateLimiter({perHour: 1000, burst: 5, maxWaitMs: 0, now: () => 0});
  const client = new RestClient(
    'fbdl',
    testConfig({retry: {attempts: 2, baseDelayMs: 100, maxDelayMs: 60_000}}),
    {fetch: fetchImpl, sleep, limiter},
  );

  assert.deepEqual(await client.request('GET', '/a'), {ok: true});
  assert.deepEqual(slept, [30_000]);
  assert.equal(calls.length, 2);
});

test('a transport failure is reported as network and retried', async () => {
  let calls = 0;
  const {sleep} = recordingSleep();
  const client = new RestClient('fbdl', testConfig({retry: {attempts: 2, baseDelayMs: 1, maxDelayMs: 1}}), {
    fetch: async () => {
      calls += 1;
      throw new Error('getaddrinfo ENOTFOUND');
    },
    sleep,
  });

  assert.equal((await expectError(client.request('GET', '/a'))).kind, 'network');
  assert.equal(calls, 2);
});

test('the local budget is refused without spending a request', async () => {
  const {fetchImpl, calls} = stubFetch([{status: 200, body: '{}'}]);
  const client = new RestClient('fbdl', testConfig({rateLimit: {perHour: 1, burst: 1, maxWaitMs: 0}}), {
    fetch: fetchImpl,
  });

  await client.request('GET', '/a');
  const error = await expectError(client.request('GET', '/a'));
  assert.equal(error.kind, 'local-budget');
  assert.match(error.message, /budget is spent/);
  assert.equal(calls.length, 1);
});

test('each endpoint gets its own budget, the way the server counts them', async () => {
  const {fetchImpl, calls} = stubFetch([{status: 200, body: '{}'}]);
  const client = new RestClient('fbdl', testConfig({rateLimit: {perHour: 1, burst: 1, maxWaitMs: 0}}), {
    fetch: fetchImpl,
  });

  await client.request('GET', '/a', {bucket: 'one'});
  // A different endpoint must not be refused because a sibling spent its allowance.
  await client.request('GET', '/b', {bucket: 'two'});
  assert.equal(calls.length, 2);

  assert.equal((await expectError(client.request('GET', '/a', {bucket: 'one'}))).kind, 'local-budget');
  assert.equal(calls.length, 2);
});

test('a tighter endpoint allowance is honoured, and a tighter config still wins', async () => {
  const {fetchImpl, calls} = stubFetch([{status: 200, body: '{}'}]);
  const client = new RestClient(
    'meta-context',
    testConfig({rateLimit: {perHour: 3_600_000, burst: 1000, maxWaitMs: 0}}),
    {fetch: fetchImpl},
  );

  // Two calls at a two-per-hour allowance: the second is admitted, the third is not.
  await client.request('POST', '/batch', {bucket: 'batch', budgetPerHour: 2});
  await client.request('POST', '/batch', {bucket: 'batch', budgetPerHour: 2});
  assert.equal((await expectError(client.request('POST', '/batch', {bucket: 'batch', budgetPerHour: 2}))).kind, 'local-budget');
  assert.equal(calls.length, 2);

  // The generous endpoint on the same client is unaffected.
  await client.request('GET', '/single', {bucket: 'single', budgetPerHour: 1000});
  assert.equal(calls.length, 3);
});

test('a Retry-After longer than the retry ceiling reports instead of retrying', async () => {
  // These endpoints answer 429 with Retry-After set to the whole hour-long window.
  const {fetchImpl, calls} = stubFetch([{status: 429, body: '', headers: {'retry-after': '3600'}}]);
  const {sleep, slept} = recordingSleep();
  const client = new RestClient(
    'meta-context',
    testConfig({retry: {attempts: 3, baseDelayMs: 100, maxDelayMs: 20_000}}),
    {fetch: fetchImpl, sleep},
  );

  const error = await expectError(client.request('GET', '/a'));
  assert.equal(error.kind, 'rate-limited');
  assert.match(error.message, /Retry after 60 minutes/);
  assert.equal(calls.length, 1, 'retrying into an hour-long lockout wastes the budget');
  assert.deepEqual(slept, []);
});

test('the token never appears in an error, however the endpoint echoes it', async () => {
  const {fetchImpl} = stubFetch([
    {status: 400, body: '{"title":"Bad","detail":"token EAAB-test rejected"}'},
  ]);
  const error = await expectError(
    new RestClient('fbdl', testConfig(), {fetch: fetchImpl}).request('GET', '/a'),
  );
  assert.ok(!error.message.includes('EAAB-test'));
  assert.match(error.message, /<redacted>/);
});

test('a 2xx with an empty body is an empty document, not a fault', async () => {
  const {fetchImpl} = stubFetch([{status: 201, body: ''}]);
  assert.deepEqual(
    await new RestClient('fbdl', testConfig(), {fetch: fetchImpl}).request('POST', '/a'),
    {},
  );
});

test('a 200 that is not JSON is reported as malformed rather than thrown raw', async () => {
  const {fetchImpl} = stubFetch([{status: 200, body: '<html>login</html>'}]);
  const error = await expectError(
    new RestClient('fbdl', testConfig(), {fetch: fetchImpl}).request('GET', '/a'),
  );
  assert.equal(error.kind, 'malformed');
});

test('undefined query parameters are dropped rather than sent as the string undefined', async () => {
  const {fetchImpl, calls} = stubFetch([{status: 200, body: '{}'}]);
  await new RestClient('fbdl', testConfig(), {fetch: fetchImpl}).request('GET', '/runs', {
    query: {limit: 25, after: undefined},
  });
  assert.equal(calls[0]?.url, 'https://api.facebook.com/runs?limit=25');
});
