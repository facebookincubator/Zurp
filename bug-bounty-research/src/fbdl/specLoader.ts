/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import {promises as fs} from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import type {Logger} from '../core/log.js';
import type {FbdlApi} from './api.js';
import {parseFbdlReference} from './specParser.js';
import {setSpec} from './spec.js';

const DEFAULT_CACHE_FILE = path.join(os.homedir(), '.cache', 'bug-bounty-research', 'fbdl-reference.json');

export type SpecSource = 'api' | 'cache';

export interface LoadSpecResult {
  readonly source: SpecSource;
  readonly cachePath: string;
  readonly apiError?: Error;
}

export interface LoadSpecOptions {
  readonly cachePath?: string;
}

/**
 * Fetch the FBDL spec from the reference API, fall back to the on-disk cache if the API
 * is unreachable, and install the result into the spec singleton.
 *
 * Successful responses are cached so the language tools keep working offline — and so a
 * researcher whose token has expired can still validate and explain scripts. Throws only
 * if both the API call and the cache read fail.
 */
export async function loadSpec(api: FbdlApi, options: LoadSpecOptions = {}): Promise<LoadSpecResult> {
  const cachePath = options.cachePath ?? DEFAULT_CACHE_FILE;

  let apiError: Error;
  try {
    const raw = await api.fetchReference();
    const parsed = parseFbdlReference(raw);
    setSpec(parsed.entities, parsed.actions);
    await writeCache(cachePath, raw).catch(() => {
      // Best-effort cache write — failure should not break startup.
    });
    return {source: 'api', cachePath};
  } catch (error) {
    apiError = error instanceof Error ? error : new Error(String(error));
  }

  const cached = await readCache(cachePath);
  if (cached !== null) {
    const parsed = parseFbdlReference(cached);
    setSpec(parsed.entities, parsed.actions);
    return {source: 'cache', cachePath, apiError};
  }

  throw new Error(
    `Failed to load the FBDL spec: the reference API failed (${apiError.message}) and no ` +
      `cached copy exists at ${cachePath}.`,
  );
}

async function writeCache(cachePath: string, raw: unknown): Promise<void> {
  await fs.mkdir(path.dirname(cachePath), {recursive: true});
  await fs.writeFile(cachePath, JSON.stringify(raw), 'utf8');
}

async function readCache(cachePath: string): Promise<unknown> {
  try {
    return JSON.parse(await fs.readFile(cachePath, 'utf8')) as unknown;
  } catch {
    return null;
  }
}

export function logSpecLoadResult(logger: Logger, result: LoadSpecResult): void {
  if (result.source === 'api') {
    logger.info('Loaded the FBDL spec from the reference API.');
    return;
  }
  logger.warn(
    `Loaded the FBDL spec from the on-disk cache (${result.cachePath}) because the ` +
      `reference API failed: ${result.apiError?.message ?? 'unknown error'}. Validation ` +
      'reflects the spec as of that cache, which may be stale.',
  );
}
