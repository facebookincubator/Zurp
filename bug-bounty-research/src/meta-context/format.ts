/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import type {ApiError} from '../core/errors.js';
import type {BatchOutcome, Resolution} from './assets.js';
import type {Candidate} from './identifiers.js';

const NOTHING_MATCHED = '(no assets — an identifier resolving to nothing is normal)';

function renderAssets(resolution: Resolution, indent = '  '): string[] {
  if (resolution.assets.length === 0) {
    return [`${indent}${NOTHING_MATCHED}`];
  }
  const width = Math.max(...resolution.assets.map((a) => a.type.length));
  return resolution.assets.map((a) => `${indent}${a.type.padEnd(width)}  ${a.name}`);
}

export function formatResolution(resolution: Resolution): string {
  const lines = [resolution.query, ...renderAssets(resolution)];
  if (resolution.objectName !== undefined) {
    lines.push(`  vanity: ${resolution.objectName}`);
  }
  return lines.join('\n');
}

function renderFailure(failure: ApiError): string {
  return `\n\nStopped early — ${failure.kind}: ${failure.message}`;
}

/**
 * Only the identifiers that resolved are listed. On a scan of live traffic most
 * candidates match nothing, and printing every miss buries the handful that hit.
 */
export function formatBatch(outcome: BatchOutcome, asked: number): string {
  const hits = outcome.resolutions.filter((r) => r.assets.length > 0);
  const misses = outcome.resolutions.length - hits.length;

  const header =
    `Resolved ${outcome.resolutions.length} of ${asked} identifiers: ` +
    `${hits.length} named something, ${misses} matched nothing.`;

  if (hits.length === 0) {
    return header + (outcome.failure !== undefined ? renderFailure(outcome.failure) : '');
  }

  const body = hits.map((r) => [r.query, ...renderAssets(r)].join('\n')).join('\n');
  return `${header}\n\n${body}${outcome.failure !== undefined ? renderFailure(outcome.failure) : ''}`;
}

function countByKind(candidates: readonly Candidate[]): string {
  const counts = new Map<string, number>();
  for (const candidate of candidates) {
    counts.set(candidate.kind, (counts.get(candidate.kind) ?? 0) + 1);
  }
  return [...counts.entries()].map(([kind, n]) => `${n} ${kind}`).join(', ');
}

export function formatScan(
  candidates: readonly Candidate[],
  outcome: BatchOutcome,
  truncated: boolean,
): string {
  if (candidates.length === 0) {
    return 'No candidate identifiers found. The endpoint recognises object ids of 15–18 digits, URLs on facebook.com / meta.com / instagram.com / threads.com, `doc_id=<id>`, GraphQL operation names ending in Query or Mutation, and `com.bloks.*` ids.';
  }

  const kindByQuery = new Map(candidates.map((c) => [c.query, c.kind]));
  const hits = outcome.resolutions.filter((r) => r.assets.length > 0);
  const misses = outcome.resolutions.length - hits.length;

  const lines = [
    `Extracted ${candidates.length} candidates (${countByKind(candidates)})` +
      (truncated ? ', capped at the limit' : '') +
      `. ${hits.length} named something, ${misses} matched nothing.`,
  ];

  if (hits.length > 0) {
    lines.push('');
    for (const resolution of hits) {
      const kind = kindByQuery.get(resolution.query);
      lines.push(`${resolution.query}${kind !== undefined ? `  [${kind}]` : ''}`);
      lines.push(...renderAssets(resolution));
    }
  }

  if (outcome.failure !== undefined) {
    lines.push(renderFailure(outcome.failure).trimStart());
  }

  return lines.join('\n');
}
