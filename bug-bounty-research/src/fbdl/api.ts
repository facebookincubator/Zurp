/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

/**
 * The routes and payloads are upstream's. What changed is what sits underneath: instead
 * of a bare `fetch`, calls go through the shared client in `../core`, which adds the
 * hourly budget, retries with backoff, proxy egress, and credential scrubbing — the same
 * treatment the Meta Context server gets.
 */

import {RestClient, type RestClientDeps} from '../core/client.js';
import type {CoreConfig} from '../core/config.js';
import {ApiError} from '../core/errors.js';

export const SERVER_NAME = 'fbdl';

const RUNS_PATH = '/bug_bounty/fbdl_runs';
const REFERENCE_PATH = '/bug_bounty/fbdl_reference/';

/**
 * Every fbdl_runs route carries the same per-researcher hourly cap, counted separately
 * per endpoint — so the buckets below are per route rather than one pool.
 */
const BUDGET_PER_HOUR = 1000;

export interface FbdlRunCreateInput {
  readonly fbdlCode: string;
  readonly note: string;
}

export interface FbdlRunListInput {
  readonly limit?: number;
  readonly after?: string;
}

export type Json = Record<string, unknown>;

export class FbdlApi {
  private readonly rest: RestClient;

  constructor(config: CoreConfig, deps: RestClientDeps = {}) {
    this.rest = new RestClient(SERVER_NAME, config, deps);
  }

  budget(): Record<string, {tokens: number; capacity: number; refillsInMs: number}> {
    return this.rest.budget();
  }

  async createRun(input: FbdlRunCreateInput): Promise<Json> {
    if (input.note.trim() === '') {
      throw new ApiError(
        'bad-request',
        'The endpoint requires a note describing what this run is for.',
      );
    }
    return this.rest.request('POST', RUNS_PATH, {
      body: {fbdl_code: input.fbdlCode, note: input.note},
      bucket: 'fbdl_runs:create',
      budgetPerHour: BUDGET_PER_HOUR,
    });
  }

  async listRuns(input: FbdlRunListInput = {}): Promise<Json> {
    return this.rest.request('GET', RUNS_PATH, {
      query: {limit: input.limit, after: input.after},
      bucket: 'fbdl_runs:list',
      budgetPerHour: BUDGET_PER_HOUR,
    });
  }

  async getRun(id: string): Promise<Json> {
    return this.rest.request('GET', `${RUNS_PATH}/${encodeURIComponent(id)}`, {
      missingLabel: `FBDL run ${id}`,
      bucket: 'fbdl_runs:get',
      budgetPerHour: BUDGET_PER_HOUR,
    });
  }

  async archiveRun(id: string): Promise<Json> {
    return this.rest.request('POST', `${RUNS_PATH}/${encodeURIComponent(id)}/archive`, {
      missingLabel: `FBDL run ${id}`,
      bucket: 'fbdl_runs:archive',
      budgetPerHour: BUDGET_PER_HOUR,
    });
  }

  /** The language spec itself, which is what makes offline validation possible. */
  async fetchReference(): Promise<Json> {
    return this.rest.request('GET', REFERENCE_PATH, {
      bucket: 'fbdl_reference',
      budgetPerHour: BUDGET_PER_HOUR,
    });
  }
}
