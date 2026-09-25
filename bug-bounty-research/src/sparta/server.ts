/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import {McpServer} from '@modelcontextprotocol/sdk/server/mcp.js';
import type {CallToolResult} from '@modelcontextprotocol/sdk/types.js';
import {z} from 'zod';

import type {CoreConfig} from '../core/config.js';
import {ApiError} from '../core/errors.js';
import {type Logger, redact, silentLogger} from '../core/log.js';
import {PACKAGE_VERSION} from '../core/version.js';
import {formatCatalog, formatFinding, formatPoc, formatScan, type TargetHit} from './format.js';
import {type Finding, SERVER_NAME, SpartaClient} from './findings.js';
import {buildPoc} from './poc.js';
import {extractTargets} from './targets.js';

export {SERVER_NAME} from './findings.js';

export interface ServerDeps {
  readonly client?: SpartaClient;
  readonly logger?: Logger;
}

/** Enough to read in one go; the whole catalog is still one request either way. */
const DEFAULT_LIST_LIMIT = 50;

const WHAT_A_FINDING_IS =
  'A SPARTA finding is a lead from an automated scanner, disclosed to you so you can ' +
  'investigate it — not a confirmed vulnerability and not a report you can submit as is.';

function ok(text: string): CallToolResult {
  return {content: [{type: 'text', text}]};
}

/**
 * Worst first, then newest, so a truncated list is the part worth reading. Sorting is not
 * optional cosmetics: the endpoint returns the catalog in an order seeded per researcher,
 * deliberately not by date, so nothing useful is at the top until a client puts it there.
 * `low` is listed against the enum growing; today it is only high and medium.
 */
const PRIORITY_ORDER = ['high', 'medium', 'low'];

function bySeverityThenRecency(a: Finding, b: Finding): number {
  const rank = (f: Finding): number => {
    const index = PRIORITY_ORDER.indexOf(f.priority.toLowerCase());
    return index === -1 ? PRIORITY_ORDER.length : index;
  };
  return rank(a) - rank(b) || (b.publishedAt ?? 0) - (a.publishedAt ?? 0);
}

export function buildServer(config: CoreConfig, deps: ServerDeps = {}): McpServer {
  const logger = deps.logger ?? silentLogger;
  const client = deps.client ?? new SpartaClient(config, {logger});
  const server = new McpServer({name: SERVER_NAME, version: PACKAGE_VERSION});

  // Tool errors land in the agent's transcript, which is the one place a leaked
  // credential would persist past the process.
  const fail = (error: unknown): CallToolResult => {
    const raw =
      error instanceof ApiError
        ? `${error.kind}: ${error.message}`
        : `Unexpected failure: ${error instanceof Error ? error.message : String(error)}`;
    const text = redact(raw, config.accessToken);
    logger.debug(`tool call failed — ${text}`);
    return {content: [{type: 'text', text}], isError: true};
  };

  server.registerTool(
    'sparta_list_findings',
    {
      title: 'List the SPARTA findings disclosed to you',
      description:
        `Every SPARTA finding disclosed to this researcher account. ${WHAT_A_FINDING_IS} ` +
        'The catalog is fetched once and reused, so this is cheap to call again and every ' +
        'later target lookup in this session is answered from it. An empty catalog is a ' +
        'normal answer: findings are disclosed selectively.',
      inputSchema: {
        priority: z
          .string()
          .min(1)
          .optional()
          .describe('Show only this priority, e.g. `high`. Matched case-insensitively.'),
        limit: z
          .number()
          .int()
          .positive()
          .optional()
          .describe(`Most findings to show, highest priority first (default ${DEFAULT_LIST_LIMIT}).`),
      },
    },
    async ({priority, limit}) => {
      try {
        const catalog = await client.loadCatalog();
        const wanted = priority?.toLowerCase();
        const matching = (
          wanted === undefined
            ? [...catalog.findings]
            : catalog.findings.filter((f) => f.priority.toLowerCase() === wanted)
        ).sort(bySeverityThenRecency);

        const shown = matching.slice(0, limit ?? DEFAULT_LIST_LIMIT);
        const notes: string[] = [];
        if (wanted !== undefined) {
          notes.push(`Filtered to priority ${priority}, from ${catalog.findings.length} disclosed.`);
        }
        if (matching.length > shown.length) {
          notes.push(`Showing ${shown.length} of ${matching.length}; raise \`limit\` for the rest.`);
        }

        return ok(
          formatCatalog(
            {findings: shown, complete: catalog.complete},
            notes.length > 0 ? {note: notes.join(' ')} : {},
          ),
        );
      } catch (error) {
        return fail(error);
      }
    },
  );

  server.registerTool(
    'sparta_scan_traffic',
    {
      title: 'Find SPARTA findings for the endpoints in some traffic',
      description:
        'Scrape the SPARTA targets out of captured traffic — a raw HTTP request, a HAR ' +
        'excerpt, a log — and report every finding disclosed against them. This is the tool ' +
        'to reach for when you are looking at a request and want to know whether a scanner ' +
        'has already flagged the endpoint behind it. Targets are persisted GraphQL document ' +
        'ids and operation shortnames; traffic carrying neither cannot be looked up. Answered ' +
        'from the catalog, so it usually costs nothing beyond the first call.',
      inputSchema: {
        text: z
          .string()
          .min(1)
          .describe('The traffic to scan. Paste the request, response, HAR excerpt or log verbatim.'),
      },
    },
    async ({text}) => {
      try {
        const targets = extractTargets(text);
        if (targets.length === 0) {
          return ok(formatScan([], true));
        }
        const catalog = await client.loadCatalog();
        const hits: TargetHit[] = [];
        for (const target of targets) {
          hits.push({target, findings: await client.findingsForTarget(target)});
        }
        return ok(formatScan(hits, catalog.complete));
      } catch (error) {
        return fail(error);
      }
    },
  );

  server.registerTool(
    'sparta_get_finding',
    {
      title: 'Read one SPARTA finding in full',
      description:
        `One finding by its \`bb_finding_id\`, with its full summary. ${WHAT_A_FINDING_IS} ` +
        'The id is the same one Zurp shows in its SPARTA findings table, so a finding can be ' +
        'carried between Burp and here.',
      inputSchema: {
        finding_id: z.string().min(1).describe('The `bb_finding_id` of the finding.'),
      },
    },
    async ({finding_id: findingId}) => {
      try {
        return ok(formatFinding(await client.findingById(findingId)));
      } catch (error) {
        return fail(error);
      }
    },
  );

  server.registerTool(
    'sparta_build_poc',
    {
      title: 'Build the proof-of-concept request for a finding',
      description:
        "Render the GraphQL call a finding's proof of concept describes, ready to send. " +
        'The disclosed variables carry `{{TOKEN}}` placeholders rather than identifiers, so ' +
        'the request reproduces nothing until you substitute values — use ids from a test ' +
        'environment you built, never a real account. Returns the placeholders and what each ' +
        'one wants alongside the request.',
      inputSchema: {
        finding_id: z.string().min(1).describe('The `bb_finding_id` of the finding.'),
      },
    },
    async ({finding_id: findingId}) => {
      try {
        const finding = await client.findingById(findingId);
        const poc = buildPoc(finding);
        if (poc === undefined) {
          return ok(
            `${finding.id} has no proof of concept: it names neither a document id nor an ` +
              'operation, so there is nothing to call. Read the summary with sparta_get_finding.',
          );
        }
        return ok(formatPoc(finding, poc));
      } catch (error) {
        return fail(error);
      }
    },
  );

  return server;
}
