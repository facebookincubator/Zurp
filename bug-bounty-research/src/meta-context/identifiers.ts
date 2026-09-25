/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

/**
 * Scrapes candidate identifiers out of arbitrary text — a bug report, a raw HTTP
 * request/response pair, a HAR excerpt — in the shapes the asset endpoint accepts.
 *
 * These are candidates, not decisions. Only the server can say whether a well-formed
 * id names anything, and most scraped ids name nothing at all.
 */

export type CandidateKind = 'fbid' | 'url' | 'doc_id' | 'graphql' | 'bloks';

export interface Candidate {
  /** Exactly what to send as the endpoint's `q`. */
  readonly query: string;
  readonly kind: CandidateKind;
}

/**
 * 15 is a deliberate floor rather than the true one: object ids start far lower, but 10
 * digits is also unix seconds and 13 is milliseconds, so reaching down there costs more
 * noise than it finds. 18 is the ceiling because 19 would be nanoseconds; it has to reach
 * that high to cover the Instagram media range, which starts at 17841400000000000.
 */
const FBID_RE = /\b\d{15,18}\b/g;

/**
 * The endpoint reads a bare number as an object id, so a persisted document id only
 * resolves when the `doc_id=` prefix survives into the query.
 */
const DOC_ID_RE = /\bdoc_id[:=]\s*(\d+)/gi;

const URL_RE = /\bhttps?:\/\/[^\s"'`<>()[\]{}\\]+/gi;

/** An operation name is only recognised as one when it ends in `Query` or `Mutation`. */
const GRAPHQL_RE = /\b[A-Z][A-Za-z0-9_]{2,}(?:Query|Mutation)\b/g;

const BLOKS_RE = /\bcom\.bloks\.[A-Za-z0-9._-]+/g;

/** Domains the resolver can map to code: www and Distillery controllers, and Graph edges. */
const RESOLVABLE_HOSTS = ['facebook.com', 'meta.com', 'instagram.com', 'threads.com'];

/**
 * Endpoints every Meta page load hits regardless of what is being tested. Left in, they
 * crowd out the handful of URLs a researcher actually cares about.
 */
const NOISE_PATHS = [
  '/ajax/bootloader-endpoint/',
  '/ajax/bnzai',
  '/ajax/bulk-route-definitions/',
  '/ajax/webstorage/process_keys/',
  '/ajax/qm/',
  '/rsrc-translations.php',
  '/a/bz',
];

export const DEFAULT_MAX_CANDIDATES = 200;

function isResolvableUrl(raw: string): boolean {
  let parsed: URL;
  try {
    parsed = new URL(raw);
  } catch {
    return false;
  }
  const host = parsed.hostname.toLowerCase();
  const onResolvableDomain = RESOLVABLE_HOSTS.some(
    (domain) => host === domain || host.endsWith(`.${domain}`),
  );
  if (!onResolvableDomain) {
    return false;
  }
  if (host === 'edge-chat.facebook.com' && parsed.pathname === '/chat') {
    return false;
  }
  return !NOISE_PATHS.some((path) => parsed.pathname.startsWith(path));
}

/** Trailing punctuation is almost always the sentence, not the identifier. */
function trimTrailingPunctuation(value: string): string {
  return value.replace(/[.,;:!?'"）)\]}]+$/, '');
}

function matchAll(text: string, pattern: RegExp): RegExpExecArray[] {
  // Cloned so a module-level /g regex cannot carry lastIndex between calls.
  const re = new RegExp(pattern.source, pattern.flags);
  const found: RegExpExecArray[] = [];
  let match: RegExpExecArray | null;
  while ((match = re.exec(text)) !== null) {
    found.push(match);
    if (match[0] === '') {
      re.lastIndex += 1;
    }
  }
  return found;
}

/**
 * Blanks out the spans already claimed by `doc_id=<n>` so the digits inside them are not
 * scraped a second time as bare object ids — the same number resolved both ways returns
 * a persisted document and a wrong (or absent) Ent.
 */
function maskSpans(text: string, spans: ReadonlyArray<readonly [number, number]>): string {
  if (spans.length === 0) {
    return text;
  }
  const chars = [...text];
  for (const [start, end] of spans) {
    for (let i = start; i < end && i < chars.length; i++) {
      chars[i] = ' ';
    }
  }
  return chars.join('');
}

export interface ExtractOptions {
  /** Stops at this many candidates; the endpoint takes 200 identifiers per batch. */
  readonly limit?: number;
}

/**
 * Every distinct identifier in `text`, in the order first seen, capped at `limit`.
 *
 * A URL carrying an object id in its query string is deliberately left whole *and* left
 * to also match as an id: the endpoint resolves one value to several assets, so the URL
 * alone would answer both, but keeping them separate attributes each asset to what
 * produced it.
 */
export function extractCandidates(text: string, options: ExtractOptions = {}): Candidate[] {
  const limit = options.limit ?? DEFAULT_MAX_CANDIDATES;
  const seen = new Set<string>();
  const candidates: Candidate[] = [];

  const add = (query: string, kind: CandidateKind): void => {
    if (query === '' || seen.has(query) || candidates.length >= limit) {
      return;
    }
    seen.add(query);
    candidates.push({query, kind});
  };

  const docIdMatches = matchAll(text, DOC_ID_RE);
  for (const match of docIdMatches) {
    add(`doc_id=${match[1]}`, 'doc_id');
  }

  for (const match of matchAll(text, URL_RE)) {
    const url = trimTrailingPunctuation(match[0]);
    if (isResolvableUrl(url)) {
      add(url, 'url');
    }
  }

  const withoutDocIds = maskSpans(
    text,
    docIdMatches.map((m) => [m.index, m.index + m[0].length] as const),
  );
  for (const match of matchAll(withoutDocIds, FBID_RE)) {
    add(match[0], 'fbid');
  }

  for (const match of matchAll(text, GRAPHQL_RE)) {
    add(match[0], 'graphql');
  }

  for (const match of matchAll(text, BLOKS_RE)) {
    add(trimTrailingPunctuation(match[0]).replace(/[._-]+$/, ''), 'bloks');
  }

  return candidates;
}
