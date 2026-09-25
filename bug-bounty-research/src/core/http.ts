/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

/**
 * Network policy: egress proxying, TLS trust, and retry pacing.
 *
 * Routing through a proxy is the normal case here rather than the exception — the people
 * running this are running Burp, and a lookup they cannot see in their own proxy history
 * is a lookup they cannot reason about.
 */

import {Agent, EnvHttpProxyAgent, setGlobalDispatcher} from 'undici';

import type {Logger} from './log.js';

export interface ProxySettings {
  readonly url: string | undefined;
  /** Why no proxy was selected, for the startup log line. */
  readonly reason: 'no-proxy-var' | 'bypassed-by-no-proxy' | 'selected';
}

const PROXY_VARS_HTTPS = [
  'BB_RESEARCH_PROXY_URL',
  'HTTPS_PROXY',
  'https_proxy',
  'ALL_PROXY',
  'all_proxy',
];
const PROXY_VARS_HTTP = [
  'BB_RESEARCH_PROXY_URL',
  'HTTP_PROXY',
  'http_proxy',
  'ALL_PROXY',
  'all_proxy',
];

function firstSet(env: NodeJS.ProcessEnv, names: readonly string[]): string | undefined {
  for (const name of names) {
    const value = env[name]?.trim();
    if (value !== undefined && value !== '') {
      return value;
    }
  }
  return undefined;
}

/**
 * True when NO_PROXY exempts this host. Follows the de-facto rules: `*` matches
 * everything, a leading dot or bare domain matches subdomains, and an entry may pin a
 * port.
 */
export function isProxyBypassed(env: NodeJS.ProcessEnv, target: URL): boolean {
  const raw = firstSet(env, ['NO_PROXY', 'no_proxy']);
  if (raw === undefined) {
    return false;
  }
  const host = target.hostname.toLowerCase();
  const port = target.port !== '' ? target.port : target.protocol === 'http:' ? '80' : '443';

  for (const entry of raw.split(',')) {
    const rule = entry.trim().toLowerCase();
    if (rule === '') {
      continue;
    }
    if (rule === '*') {
      return true;
    }
    const [rulePattern = '', rulePort] = rule.split(':');
    if (rulePort !== undefined && rulePort !== port) {
      continue;
    }
    const pattern = rulePattern.replace(/^\./, '');
    if (host === pattern || host.endsWith(`.${pattern}`)) {
      return true;
    }
  }
  return false;
}

export function resolveProxy(env: NodeJS.ProcessEnv, target: URL): ProxySettings {
  const url = firstSet(env, target.protocol === 'http:' ? PROXY_VARS_HTTP : PROXY_VARS_HTTPS);
  if (url === undefined) {
    return {url: undefined, reason: 'no-proxy-var'};
  }
  if (isProxyBypassed(env, target)) {
    return {url: undefined, reason: 'bypassed-by-no-proxy'};
  }
  return {url, reason: 'selected'};
}

export interface NetworkOptions {
  readonly baseUrl: string;
  readonly insecureTls: boolean;
  readonly timeoutMs: number;
  readonly env?: NodeJS.ProcessEnv;
  readonly logger: Logger;
  readonly install?: (dispatcher: Agent) => void;
}

/**
 * Points global `fetch` at a proxy and/or a relaxed TLS policy.
 *
 * Node's global `fetch` does not honour HTTP_PROXY on its own; undici's global dispatcher
 * is the seam it reads, so installing one here reaches every call site without threading
 * a dispatcher through them. `EnvHttpProxyAgent` does the matching itself — including
 * NO_PROXY — which is why the resolution below only decides *whether* to install one and
 * what to say about it.
 */
export function configureNetwork(options: NetworkOptions): ProxySettings {
  const {baseUrl, insecureTls, timeoutMs, logger} = options;
  const env = options.env ?? process.env;
  const install = options.install ?? setGlobalDispatcher;

  const target = new URL(baseUrl);
  const proxy = resolveProxy(env, target);

  if (insecureTls) {
    logger.warn(
      'INSECURE_TLS is set: server certificates are NOT verified. Only acceptable when ' +
        'deliberately intercepting your own traffic. Prefer NODE_EXTRA_CA_CERTS pointed ' +
        "at your proxy's CA certificate.",
    );
  }

  const connect = insecureTls
    ? {rejectUnauthorized: false, timeout: timeoutMs}
    : {timeout: timeoutMs};

  const configured = firstSet(env, [...PROXY_VARS_HTTPS, ...PROXY_VARS_HTTP]);
  if (configured !== undefined) {
    logger.info(
      proxy.url !== undefined
        ? `Routing requests through proxy ${scrubCredentials(proxy.url)}.`
        : `Proxy configured but NO_PROXY exempts ${target.host}; connecting directly.`,
    );
    install(new EnvHttpProxyAgent({connect}) as unknown as Agent);
  } else if (insecureTls) {
    install(new Agent({connect}));
  } else {
    logger.debug(`No proxy configured; connecting to ${target.host} directly.`);
  }

  return proxy;
}

/** Proxy URLs carry credentials often enough that logging one raw is a real leak. */
export function scrubCredentials(rawUrl: string): string {
  try {
    const url = new URL(rawUrl);
    if (url.username !== '' || url.password !== '') {
      url.username = '<redacted>';
      url.password = '';
    }
    return url.toString();
  } catch {
    return rawUrl;
  }
}

export interface RetryPolicy {
  /** Total tries, including the first. 1 disables retrying. */
  readonly attempts: number;
  readonly baseDelayMs: number;
  readonly maxDelayMs: number;
}

/** Transient by nature: the same request later has a real chance of a different answer. */
export function isRetryableStatus(status: number): boolean {
  return status === 429 || status === 408 || status >= 500;
}

/** Accepts both forms RFC 9110 allows: delay-seconds, or an HTTP date. */
export function parseRetryAfter(header: string | null | undefined, now: number = Date.now()): number | undefined {
  if (header === null || header === undefined) {
    return undefined;
  }
  const raw = header.trim();
  if (raw === '') {
    return undefined;
  }
  if (/^\d+$/.test(raw)) {
    return Number.parseInt(raw, 10) * 1000;
  }
  const at = Date.parse(raw);
  return Number.isNaN(at) ? undefined : Math.max(0, at - now);
}

/**
 * Full jitter: the delay is uniform over [0, exponential cap]. Spreading retries matters
 * because a 429 tends to hit every request of a batch at once, and a fixed backoff would
 * march them all back into the limiter together.
 */
export function backoffDelayMs(
  attempt: number,
  policy: RetryPolicy,
  retryAfterMs: number | undefined,
  random: () => number = Math.random,
): number {
  if (retryAfterMs !== undefined) {
    return Math.min(retryAfterMs, policy.maxDelayMs);
  }
  const cap = Math.min(policy.maxDelayMs, policy.baseDelayMs * 2 ** Math.max(0, attempt - 1));
  return Math.round(random() * cap);
}
