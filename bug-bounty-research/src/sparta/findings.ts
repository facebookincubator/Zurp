/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

/**
 * Client for the Bug Bounty SPARTA findings API — the endpoint that hands a researcher the
 * automated-scanner findings disclosed to them, each keyed to the GraphQL document or
 * operation it was raised against.
 *
 *   GET /bug_bounty/sparta_findings?limit=&after=
 *   GET /bug_bounty/sparta_findings?target_id=&target_type=&limit=
 *   GET /bug_bounty/sparta_findings/{bb_finding_id}
 *
 * The first two are the same route: the unfiltered form is the whole disclosed catalog,
 * which is what makes the sweep below worth doing. The target parameters narrow it, and
 * never grant — visibility is decided by the Ent policy behind it, not by the query.
 */

import {RestClient, type RestClientDeps} from '../core/client.js';
import type {CoreConfig} from '../core/config.js';
import {ApiError} from '../core/errors.js';
import type {Logger} from '../core/log.js';
import {silentLogger} from '../core/log.js';

export const SERVER_NAME = 'sparta';

const FINDINGS_PATH = '/bug_bounty/sparta_findings';

/**
 * Per researcher, per hour. The server keys its counter on the endpoint as well as the
 * researcher, so the list route and the by-id route each get their own allowance and
 * neither competes with the asset lookups.
 */
const BUDGET_PER_HOUR = 1000;

/** `FBStefiBBSpartaFindingListPaginationPolicy` clamps a larger page down to this. */
export const MAX_PAGE_SIZE = 100;

/**
 * Pages the sweep will pull before giving up. The server windows the visible corpus at
 * 2,000 rows, so a complete sweep is 20 pages; past that something is wrong, and the
 * catalog is marked incomplete rather than trusted.
 */
const MAX_SWEEP_PAGES = 50;

/** Wire values of the Hack `SpartaBountyLeadTargetType` enum. */
export const TARGET_TYPES = ['published_doc_id', 'endpoint_name'] as const;
export type TargetType = (typeof TARGET_TYPES)[number];

export interface SpartaTarget {
  readonly type: TargetType;
  readonly id: string;
}

export interface Finding {
  /** `bb_finding_id` on the wire. The handle to quote in a report and in Zurp's table. */
  readonly id: string;
  readonly title: string;
  readonly summary: string;
  /** Wire values of the Hack `SpartaBountyLeadPriority` enum; unknown values pass through. */
  readonly priority: string;
  /** Absent when the API named a target type this build does not know. */
  readonly target?: SpartaTarget;
  /** A persisted document id, or an operation shortname when the PoC names one instead. */
  readonly pocDocId: string;
  readonly pocVariablesJson: string;
  readonly pocPlaceholdersJson: string;
  /** Unix seconds. Absent when the API sent no timestamp. */
  readonly publishedAt?: number;
}

export interface Catalog {
  readonly findings: readonly Finding[];
  /**
   * False when the sweep stopped on a page cap or a budget refusal. An incomplete catalog
   * can prove a finding exists but never that one does not.
   */
  readonly complete: boolean;
}

export function targetKey(target: SpartaTarget): string {
  return `${target.type}|${target.id}`;
}

function isTargetType(value: string): value is TargetType {
  return (TARGET_TYPES as readonly string[]).includes(value);
}

function str(json: Record<string, unknown>, field: string): string {
  const value = json[field];
  return typeof value === 'string' ? value : '';
}

export function parseFinding(raw: unknown): Finding | undefined {
  if (raw === null || typeof raw !== 'object' || Array.isArray(raw)) {
    return undefined;
  }
  const json = raw as Record<string, unknown>;
  const id = str(json, 'bb_finding_id');
  if (id === '') {
    return undefined;
  }

  const targetType = str(json, 'target_type');
  const targetId = str(json, 'target_id');
  const publishedAt = json.published_at;

  return {
    id,
    title: str(json, 'title'),
    summary: str(json, 'summary'),
    priority: str(json, 'priority'),
    ...(isTargetType(targetType) && targetId !== ''
      ? {target: {type: targetType, id: targetId}}
      : {}),
    pocDocId: str(json, 'poc_doc_id'),
    pocVariablesJson: str(json, 'poc_variables_json'),
    pocPlaceholdersJson: str(json, 'poc_placeholders_json'),
    ...(typeof publishedAt === 'number' && publishedAt > 0 ? {publishedAt} : {}),
  };
}

function parsePage(body: Record<string, unknown>): {findings: Finding[]; after?: string} {
  const findings: Finding[] = [];
  if (Array.isArray(body.data)) {
    for (const entry of body.data) {
      const finding = parseFinding(entry);
      if (finding !== undefined) {
        findings.push(finding);
      }
    }
  }

  const paging = body.paging;
  if (paging !== null && typeof paging === 'object') {
    const cursors = (paging as Record<string, unknown>).cursors;
    if (cursors !== null && typeof cursors === 'object') {
      const after = (cursors as Record<string, unknown>).after;
      if (typeof after === 'string' && after !== '') {
        return {findings, after};
      }
    }
  }
  return {findings};
}

export class SpartaClient {
  private readonly rest: RestClient;
  private readonly logger: Logger;

  /**
   * The disclosed catalog, swept once and reused. One sweep answers every later target
   * question for free, and a session turns up far more distinct targets than the hourly
   * budget would allow asking about one at a time.
   */
  private catalog: Catalog | undefined;
  private sweeping: Promise<Catalog> | undefined;

  constructor(config: CoreConfig, deps: RestClientDeps & {logger?: Logger} = {}) {
    this.rest = new RestClient(SERVER_NAME, config, deps);
    this.logger = deps.logger ?? silentLogger;
  }

  budget(): Record<string, {tokens: number; capacity: number; refillsInMs: number}> {
    return this.rest.budget();
  }

  /** One page of the unfiltered list, for a caller that wants to page it itself. */
  async listPage(options: {limit?: number; after?: string} = {}): Promise<{
    findings: readonly Finding[];
    nextCursor?: string;
  }> {
    const limit = Math.min(options.limit ?? MAX_PAGE_SIZE, MAX_PAGE_SIZE);
    const body = await this.rest.request('GET', FINDINGS_PATH, {
      query: {limit, after: options.after},
      bucket: 'sparta_findings:list',
      budgetPerHour: BUDGET_PER_HOUR,
    });
    const {findings, after} = parsePage(body);
    return {findings, ...(after !== undefined ? {nextCursor: after} : {})};
  }

  /**
   * The whole disclosed catalog, swept at most once per process. Concurrent callers share
   * one sweep rather than each starting their own.
   */
  async loadCatalog(): Promise<Catalog> {
    if (this.catalog !== undefined) {
      return this.catalog;
    }
    this.sweeping ??= this.sweep().finally(() => {
      this.sweeping = undefined;
    });
    return this.sweeping;
  }

  private async sweep(): Promise<Catalog> {
    const findings: Finding[] = [];
    const seen = new Set<string>();
    let after: string | undefined;
    let complete = false;

    for (let page = 0; page < MAX_SWEEP_PAGES; page += 1) {
      const body = await this.rest.request('GET', FINDINGS_PATH, {
        query: {limit: MAX_PAGE_SIZE, after},
        bucket: 'sparta_findings:list',
        budgetPerHour: BUDGET_PER_HOUR,
      });
      const parsed = parsePage(body);
      for (const finding of parsed.findings) {
        // The same finding can be raised against more than one target; keep one copy.
        if (!seen.has(finding.id)) {
          seen.add(finding.id);
          findings.push(finding);
        }
      }
      after = parsed.after;
      if (after === undefined) {
        complete = true;
        break;
      }
    }

    if (!complete) {
      this.logger.warn(
        `Catalog sweep stopped after ${MAX_SWEEP_PAGES} pages; results may be incomplete.`,
      );
    }
    this.catalog = {findings, complete};
    return this.catalog;
  }

  /**
   * Findings raised against one target. Answered from the swept catalog; only a catalog
   * that stopped short falls back to asking the API, since only then can it be wrong
   * about a target having nothing.
   */
  async findingsForTarget(target: SpartaTarget): Promise<readonly Finding[]> {
    const catalog = await this.loadCatalog();
    const cached = catalog.findings.filter(
      (finding) => finding.target !== undefined && targetKey(finding.target) === targetKey(target),
    );
    if (catalog.complete || cached.length > 0) {
      return cached;
    }
    return this.fetchForTarget(target);
  }

  private async fetchForTarget(target: SpartaTarget): Promise<readonly Finding[]> {
    const body = await this.rest.request('GET', FINDINGS_PATH, {
      query: {target_id: target.id, target_type: target.type, limit: MAX_PAGE_SIZE},
      bucket: 'sparta_findings:list',
      budgetPerHour: BUDGET_PER_HOUR,
    });
    return parsePage(body).findings;
  }

  /**
   * One finding by its `bb_finding_id`. Answered from an already-swept catalog when it is
   * there, and from the endpoint's own by-id route when it is not — which is what makes a
   * finding past the server's 2,000-row visible window, or one named by a colleague,
   * reachable at all.
   */
  async findingById(id: string): Promise<Finding> {
    const trimmed = id.trim();
    if (trimmed === '') {
      throw new ApiError('bad-request', 'Pass the bb_finding_id of the finding.');
    }

    const cached = this.catalog?.findings.find((candidate) => candidate.id === trimmed);
    if (cached !== undefined) {
      return cached;
    }

    const body = await this.rest.request(
      'GET',
      `${FINDINGS_PATH}/${encodeURIComponent(trimmed)}`,
      {
        missingLabel: `SPARTA finding ${trimmed}`,
        bucket: 'sparta_findings:get',
        budgetPerHour: BUDGET_PER_HOUR,
      },
    );

    const finding = parseFinding(body);
    if (finding === undefined) {
      throw new ApiError(
        'malformed',
        `The endpoint answered for ${trimmed} without a bb_finding_id.`,
      );
    }
    return finding;
  }
}
