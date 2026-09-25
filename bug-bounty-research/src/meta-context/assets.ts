/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

/**
 * Client for the Bug Bounty asset resolution API — the endpoint that turns one
 * identifier a researcher saw in traffic into the code assets behind it.
 *
 *   GET  /bug_bounty/assets?q=<identifier>
 *   POST /bug_bounty/assets/batch  {"queries": [...]}
 *
 * Resolution runs under the calling researcher's own viewer context, so this can only
 * ever name code the researcher is already allowed to know about.
 */

import {ApiError} from '../core/errors.js';
import type {CoreConfig} from '../core/config.js';
import {RestClient, type RestClientDeps} from '../core/client.js';

export const SERVER_NAME = 'meta-context';

/** Kinds the endpoint returns today. An unrecognised one is data, not an error. */
export type AssetType =
  | 'ent_or_node'
  | 'xcontroller'
  | 'graphql'
  | 'graph_edge'
  | 'distillery'
  | 'bloks'
  | (string & {});

export interface Asset {
  readonly type: AssetType;
  readonly name: string;
}

export interface Resolution {
  readonly query: string;
  readonly assets: readonly Asset[];
  /** Only the single lookup returns this; the batch deliberately omits it. */
  readonly objectName?: string;
}

export interface BatchOutcome {
  readonly resolutions: readonly Resolution[];
  /** Set when a chunk failed; `resolutions` still holds everything resolved before it. */
  readonly failure?: ApiError;
}

/** Identifiers per batch request. A longer list is rejected rather than truncated. */
export const MAX_BATCH_QUERIES = 200;

const GET_PATH = '/bug_bounty/assets';
const BATCH_PATH = '/bug_bounty/assets/batch';

/**
 * The per-researcher hourly caps the endpoints actually enforce, counted per endpoint.
 * The batch cap is an order of magnitude tighter because one batch is worth up to
 * MAX_BATCH_QUERIES singles — 100 full batches an hour is still 20,000 identifiers.
 */
const GET_BUDGET_PER_HOUR = 1000;
const BATCH_BUDGET_PER_HOUR = 100;

function parseAssets(raw: unknown): Asset[] {
  if (!Array.isArray(raw)) {
    return [];
  }
  const assets: Asset[] = [];
  for (const entry of raw) {
    if (entry !== null && typeof entry === 'object') {
      const {type, name} = entry as {type?: unknown; name?: unknown};
      if (typeof type === 'string' && typeof name === 'string' && type !== '' && name !== '') {
        assets.push({type, name});
      }
    }
  }
  return assets;
}

export class AssetClient {
  private readonly rest: RestClient;

  constructor(config: CoreConfig, deps: RestClientDeps = {}) {
    this.rest = new RestClient(SERVER_NAME, config, deps);
  }

  budget(): Record<string, {tokens: number; capacity: number; refillsInMs: number}> {
    return this.rest.budget();
  }

  async resolve(query: string): Promise<Resolution> {
    const trimmed = query.trim();
    if (trimmed === '') {
      throw new ApiError('bad-request', 'Pass one identifier to resolve.');
    }
    const body = await this.rest.request('GET', GET_PATH, {
      query: {q: trimmed},
      bucket: 'assets:get',
      budgetPerHour: GET_BUDGET_PER_HOUR,
    });

    const objectName = typeof body.object_name === 'string' ? body.object_name : '';
    return {
      query: typeof body.query === 'string' ? body.query : trimmed,
      assets: parseAssets(body.assets),
      ...(objectName !== '' ? {objectName} : {}),
    };
  }

  /**
   * Resolves many identifiers, deduplicated and chunked to the endpoint's cap. Stops at
   * the first chunk that fails and returns what resolved before it, so one 429 partway
   * through a long list does not discard the answers already paid for.
   */
  async resolveBatch(queries: readonly string[]): Promise<BatchOutcome> {
    const unique = [...new Set(queries.map((q) => q.trim()).filter((q) => q !== ''))];
    if (unique.length === 0) {
      throw new ApiError('bad-request', 'Pass at least one identifier to resolve.');
    }

    const resolutions: Resolution[] = [];
    for (let i = 0; i < unique.length; i += MAX_BATCH_QUERIES) {
      const chunk = unique.slice(i, i + MAX_BATCH_QUERIES);
      try {
        const body = await this.rest.request('POST', BATCH_PATH, {
          body: {queries: chunk},
          bucket: 'assets:batch',
          budgetPerHour: BATCH_BUDGET_PER_HOUR,
        });
        const results = Array.isArray(body.results) ? body.results : [];
        for (const entry of results) {
          if (entry !== null && typeof entry === 'object') {
            const {query, assets} = entry as {query?: unknown; assets?: unknown};
            resolutions.push({
              query: typeof query === 'string' ? query : '',
              assets: parseAssets(assets),
            });
          }
        }
      } catch (error) {
        if (error instanceof ApiError) {
          return {resolutions, failure: error};
        }
        throw error;
      }
    }
    return {resolutions};
  }
}
