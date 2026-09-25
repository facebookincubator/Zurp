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
import {AssetClient, MAX_BATCH_QUERIES, SERVER_NAME} from './assets.js';
import {formatBatch, formatResolution, formatScan} from './format.js';
import {DEFAULT_MAX_CANDIDATES, extractCandidates} from './identifiers.js';

export {SERVER_NAME} from './assets.js';

export interface ServerDeps {
  readonly client?: AssetClient;
  readonly logger?: Logger;
}

/** Guards a hostile report from spending the whole hourly budget in one scan. */
const MAX_SCAN_CANDIDATES = 1000;

const IDENTIFIER_SHAPES =
  'An object id or Instagram media id of 15-18 digits; a URL or path on facebook.com, ' +
  'meta.com, instagram.com or threads.com; a persisted document id written as ' +
  '`doc_id=<id>` (a bare number is read as an object id instead); a GraphQL operation ' +
  'name, which must end in `Query` or `Mutation`; or a `com.bloks.*` id.';

function ok(text: string): CallToolResult {
  return {content: [{type: 'text', text}]};
}

export function buildServer(config: CoreConfig, deps: ServerDeps = {}): McpServer {
  const logger = deps.logger ?? silentLogger;
  const client = deps.client ?? new AssetClient(config, {logger});
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
    'meta_context_resolve',
    {
      title: 'Resolve one identifier to its code',
      description:
        'Resolve a single identifier seen in Meta traffic to the code assets that serve ' +
        `it. ${IDENTIFIER_SHAPES} Returns zero or more assets — one value can be several ` +
        'things at once, and an identifier that matches nothing is a normal answer, not ' +
        'an error. Use meta_context_scan instead when you have a bug report or a captured ' +
        'request rather than one already-isolated identifier.',
      inputSchema: {
        query: z.string().min(1).describe(`The one identifier to resolve. ${IDENTIFIER_SHAPES}`),
      },
    },
    async ({query}) => {
      try {
        return ok(formatResolution(await client.resolve(query)));
      } catch (error) {
        return fail(error);
      }
    },
  );

  server.registerTool(
    'meta_context_resolve_batch',
    {
      title: 'Resolve many identifiers to their code',
      description:
        'Resolve a list of already-isolated identifiers in one round trip. Repeats are ' +
        `resolved once and the list is chunked at ${MAX_BATCH_QUERIES} per request. Cheaper ` +
        'than calling meta_context_resolve in a loop, and the right choice for anything ' +
        'past a couple of identifiers. Does not return vanity names — ask for a single ' +
        'identifier on its own if you need one.',
      inputSchema: {
        queries: z
          .array(z.string().min(1))
          .min(1)
          .describe(`The identifiers to resolve, each the shape a single lookup takes. ${IDENTIFIER_SHAPES}`),
      },
    },
    async ({queries}) => {
      try {
        return ok(formatBatch(await client.resolveBatch(queries), queries.length));
      } catch (error) {
        return fail(error);
      }
    },
  );

  server.registerTool(
    'meta_context_scan',
    {
      title: 'Scan text for identifiers and resolve them',
      description:
        'Scrape every candidate identifier out of a block of text — a bug bounty report, ' +
        'a raw HTTP request or response, a HAR excerpt, a stack trace — and resolve them ' +
        'all in one batch. This is the tool to reach for when you have traffic or a report ' +
        'in hand and want to know what code is behind it. Most scraped ids name nothing; ' +
        'only the ones that resolved are listed.',
      inputSchema: {
        text: z
          .string()
          .min(1)
          .describe('The text to scan. Paste the report, request, response or log verbatim.'),
        limit: z
          .number()
          .int()
          .positive()
          .max(MAX_SCAN_CANDIDATES)
          .optional()
          .describe(
            `Most candidates to resolve, newest-found last (default ${DEFAULT_MAX_CANDIDATES}). ` +
              'Raise it only for a large report you trust: every candidate spends a slot of ' +
              'your hourly budget, and untrusted text can be padded with fake ids.',
          ),
      },
    },
    async ({text, limit}) => {
      try {
        const cap = limit ?? DEFAULT_MAX_CANDIDATES;
        const candidates = extractCandidates(text, {limit: cap});
        if (candidates.length === 0) {
          return ok(formatScan(candidates, {resolutions: []}, false));
        }
        const outcome = await client.resolveBatch(candidates.map((c) => c.query));
        return ok(formatScan(candidates, outcome, candidates.length === cap));
      } catch (error) {
        return fail(error);
      }
    },
  );

  return server;
}
