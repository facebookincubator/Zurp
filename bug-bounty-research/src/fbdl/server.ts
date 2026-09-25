/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

/**
 * The tool surface, wording and JSON envelope are upstream's, so scripts and skills
 * written against fbdl-mcp keep working. What changed: the module-level singleton became
 * a `buildServer` factory taking an injected API client and logger, errors come from the
 * shared taxonomy in `../core`, and every tool result is scrubbed of the token.
 */

import {McpServer} from '@modelcontextprotocol/sdk/server/mcp.js';
import type {CallToolResult} from '@modelcontextprotocol/sdk/types.js';
import {z} from 'zod';

import type {CoreConfig} from '../core/config.js';
import {ApiError} from '../core/errors.js';
import {type Logger, redact, silentLogger} from '../core/log.js';
import {PACKAGE_VERSION} from '../core/version.js';
import {FbdlApi, SERVER_NAME} from './api.js';
import {tryAcquireCreateSlot} from './runGuard.js';
import {getAction, getActions, getSetupEntities, getSetupEntity} from './spec.js';
import {validate} from './validator.js';

export {SERVER_NAME} from './api.js';

export interface ServerDeps {
  readonly api?: FbdlApi;
  readonly logger?: Logger;
  /** Set when the spec could not be loaded; the language tools say so instead of throwing. */
  readonly specError?: Error;
}

export function buildServer(config: CoreConfig, deps: ServerDeps = {}): McpServer {
  const logger = deps.logger ?? silentLogger;
  const api = deps.api ?? new FbdlApi(config, {logger});
  const server = new McpServer({name: SERVER_NAME, version: PACKAGE_VERSION});

  const scrub = (text: string): string => redact(text, config.accessToken);
  const json = (value: unknown): CallToolResult => ({
    content: [{type: 'text', text: scrub(JSON.stringify(value, null, 2))}],
  });

  /**
   * The language tools need the spec, which is fetched at startup. If that failed there
   * is nothing useful they can do — but saying so beats the server refusing to start,
   * which surfaces to the researcher as "server failed to start".
   */
  const withSpec = (run: () => CallToolResult): CallToolResult => {
    if (deps.specError !== undefined) {
      return {
        content: [
          {
            type: 'text',
            text: scrub(
              `The FBDL language spec is not available, so this tool cannot answer. ` +
                `Loading it from /bug_bounty/fbdl_reference/ failed and no cached copy ` +
                `exists: ${deps.specError.message}`,
            ),
          },
        ],
        isError: true,
      };
    }
    return run();
  };

  // ── Language tools: no API call, no budget spent ──────────────────────────

  server.registerTool(
    'validate_fbdl',
    {
      description:
        'Validate an FBDL script for structural correctness. Checks syntax, known ' +
        'entities/actions, required parameters, and enum values. Costs nothing — validate ' +
        'before submitting rather than learning from a rejected run.',
      inputSchema: {script: z.string().describe('The FBDL script to validate.')},
    },
    ({script}) => withSpec(() => json(validate(script))),
  );

  server.registerTool(
    'list_entities',
    {
      description:
        'List all available FBDL setup entity types with their parameters and examples.',
      inputSchema: {
        type: z
          .string()
          .optional()
          .describe("Filter by entity type name (e.g. 'Page', 'Group'). Omit to list all."),
      },
    },
    ({type}) =>
      withSpec(() => {
        const all = getSetupEntities();
        const entities = type !== undefined ? all.filter((e) => e.type === type) : all;
        const text = entities
          .map((e) => {
            const params = e.params.map((p) => `    ${describeParam(p)}`).join('\n');
            return (
              `## ${e.type}\n${e.description}\nLabel: ${e.hasLabel ? 'yes' : 'no'}\n` +
              `${params.length > 0 ? `Parameters:\n${params}` : 'No parameters.'}\nExample: ${e.example}`
            );
          })
          .join('\n\n');
        return {content: [{type: 'text', text: text.length > 0 ? text : 'No matching entities.'}]};
      }),
  );

  server.registerTool(
    'list_actions',
    {
      description:
        'List all available FBDL actions with their signatures, parameters, and examples.',
      inputSchema: {
        name: z
          .string()
          .optional()
          .describe("Filter by action name (e.g. 'make_post_text'). Omit to list all."),
        category: z
          .string()
          .optional()
          .describe(
            "Filter by category keyword (e.g. 'post', 'group', 'page', 'business', 'event', 'app').",
          ),
      },
    },
    ({name, category}) =>
      withSpec(() => {
        let actions = [...getActions()];
        if (name !== undefined) {
          const exact = getAction(name);
          actions = exact !== undefined ? [exact] : [];
        } else if (category !== undefined) {
          const lower = category.toLowerCase();
          actions = actions.filter(
            (a) =>
              a.name.toLowerCase().includes(lower) || a.description.toLowerCase().includes(lower),
          );
        }

        const text = actions
          .map((a) => {
            const kw = a.keywordParams.map((p) => `    ${describeParam(p)}`).join('\n');
            return (
              `## ${a.name}\n${a.description}\nSignature: ${a.signature}\n` +
              `Voice switcher: ${a.supportsVoiceSwitcher ? 'yes' : 'no'}\n` +
              `Target types: ${a.targetTypes.join(', ')}\n` +
              `${kw.length > 0 ? `Keyword params:\n${kw}` : 'No keyword params.'}\n` +
              `Examples:\n${a.examples.map((e) => `  ${e}`).join('\n')}`
            );
          })
          .join('\n\n');
        return {
          content: [{type: 'text', text: text.length > 0 ? text : 'No matching actions found.'}],
        };
      }),
  );

  server.registerTool(
    'explain_fbdl',
    {
      description:
        'Explain what an FBDL script does in plain English. Parses the script and ' +
        'describes each setup entity and action step by step.',
      inputSchema: {script: z.string().describe('The FBDL script to explain.')},
    },
    ({script}) => withSpec(() => ({content: [{type: 'text', text: explain(script)}]})),
  );

  // ── API tools: these spend budget ─────────────────────────────────────────

  server.registerTool(
    'create_fbdl_run',
    {
      description:
        'Submit an FBDL script to the FBDL API for asynchronous execution. ' +
        'Only one create_fbdl_run can be in flight at a time, and a cooldown blocks ' +
        'further submissions for 30s after success / 60s after failure. ' +
        'Poll the returned id with get_fbdl_run to collect the created assets.',
      inputSchema: {
        fbdl_code: z.string().describe('The FBDL script to execute.'),
        note: z.string().describe('Required note describing this run.'),
      },
    },
    async ({fbdl_code, note}) => {
      if (deps.specError === undefined) {
        const validation = validate(fbdl_code);
        if (!validation.valid) {
          // Validation never hit the API — no slot taken, no cooldown, nothing spent.
          return json({
            ok: false,
            error: 'Validation failed. The run was not submitted.',
            validation,
          });
        }
      }

      const slot = tryAcquireCreateSlot();
      if (!slot.ok) {
        return json({
          ok: false,
          error: slot.message,
          reason: slot.reason,
          ...(slot.cooldownRemainingMs !== undefined
            ? {cooldownRemainingMs: slot.cooldownRemainingMs}
            : {}),
        });
      }

      try {
        const result = await api.createRun({fbdlCode: fbdl_code, note});
        slot.slot.release('success');
        return json({ok: true, result});
      } catch (error) {
        slot.slot.release('failure');
        logger.debug(`create_fbdl_run failed — ${describeError(error)}`);
        return json(withMaxRunsHint(apiErrorResponse(error)));
      }
    },
  );

  server.registerTool(
    'list_fbdl_runs',
    {
      description: 'List FBDL API runs for the configured token, most recent first.',
      inputSchema: {
        limit: z
          .number()
          .int()
          .positive()
          .max(1000)
          .optional()
          .describe('Page size. API default is 25.'),
        after: z.string().optional().describe('Cursor returned in paging.cursors.after.'),
      },
    },
    async ({limit, after}) => {
      try {
        return json({
          ok: true,
          result: await api.listRuns({
            ...(limit !== undefined ? {limit} : {}),
            ...(after !== undefined ? {after} : {}),
          }),
        });
      } catch (error) {
        return json(apiErrorResponse(error));
      }
    },
  );

  server.registerTool(
    'get_fbdl_run',
    {
      description:
        'Fetch one FBDL API run by id: its status, the script it ran, the assets it ' +
        'created if it COMPLETED, and the error if it FAILED.',
      inputSchema: {id: z.string().min(1).describe('FBDL run id.')},
    },
    async ({id}) => {
      try {
        return json({ok: true, result: await api.getRun(id)});
      } catch (error) {
        return json(apiErrorResponse(error));
      }
    },
  );

  server.registerTool(
    'archive_fbdl_run',
    {
      description:
        'Archive one FBDL API run by id. Archiving releases the run\'s test assets, which ' +
        'frees a slot against the cap on active runs.',
      inputSchema: {id: z.string().min(1).describe('FBDL run id.')},
    },
    async ({id}) => {
      try {
        return json({ok: true, result: await api.archiveRun(id)});
      } catch (error) {
        return json(apiErrorResponse(error));
      }
    },
  );

  server.registerResource(
    'fbdl-reference',
    'fbdl://reference',
    {
      description:
        'Current FBDL language reference, built from the spec loaded from the FBDL ' +
        'reference API at startup.',
    },
    () => ({
      contents: [
        {
          uri: 'fbdl://reference',
          mimeType: 'text/markdown',
          text: deps.specError !== undefined ? `FBDL spec unavailable: ${deps.specError.message}` : buildReference(),
        },
      ],
    }),
  );

  server.registerPrompt(
    'generate_fbdl',
    {
      description:
        'Generate an FBDL script from a natural language description of the desired test scenario.',
      argsSchema: {
        description: z.string().describe('Natural language description of the test scenario.'),
      },
    },
    ({description}) => ({
      messages: [{role: 'user', content: {type: 'text', text: generatePrompt(description)}}],
    }),
  );

  return server;
}

interface DescribableParam {
  readonly name: string;
  readonly required: boolean;
  readonly description: string;
  readonly values?: readonly string[];
  readonly isList?: boolean;
}

function describeParam(p: DescribableParam): string {
  const req = p.required ? '(required)' : '(optional)';
  const vals = p.values !== undefined ? ` [${p.values.join(' | ')}]` : '';
  const list = p.isList === true ? ' (list)' : '';
  return `${p.name} ${req}${vals}${list}: ${p.description}`;
}

function explain(script: string): string {
  const explanations: string[] = [];
  let section: 'setup' | 'action' | null = null;

  for (const raw of script.split('\n')) {
    const line = raw.trim();
    if (line.length === 0 || line.startsWith('#')) continue;

    if (line.startsWith('[setup]')) {
      explanations.push('**Setup block**: creates the test entities listed below.');
      section = 'setup';
      continue;
    }
    if (line.startsWith('[action]')) {
      explanations.push('**Action block**: performs the operations listed below.');
      section = 'action';
      continue;
    }
    explanations.push(section === 'setup' ? explainEntity(line) : explainAction(line));
  }

  return explanations.join('\n\n');
}

function explainEntity(line: string): string {
  const tokens = line.split(/\s+/);
  const type = tokens[0] ?? 'Entity';
  const label = tokens[1] !== undefined && tokens[1] !== 'with' ? tokens[1] : undefined;
  const entity = getSetupEntity(type);
  const desc = entity !== undefined ? `: ${entity.description}` : '';
  const heading = label !== undefined ? `${type} ${label}` : type;
  return `**${heading}** — creates a ${type}${desc}\n  Full line: \`${line}\``;
}

function explainAction(line: string): string {
  const tokens = line.split(/\s+/);
  const subject = tokens[0] ?? 'Unknown';

  let voiceAs: string | undefined;
  let actionIdx = 1;
  if (tokens[1] === 'as') {
    voiceAs = tokens[2];
    actionIdx = 3;
  }

  const actionName = tokens[actionIdx] ?? 'unknown';
  const action = getAction(actionName);
  const voice = voiceAs !== undefined ? ` (acting as ${voiceAs})` : '';

  if (action !== undefined) {
    return `**${subject}${voice}** performs **${actionName}**: ${action.description}\n  Full line: \`${line}\``;
  }
  return `**${subject}${voice}** performs **${actionName}**\n  Full line: \`${line}\``;
}

function describeError(error: unknown): string {
  return error instanceof Error ? `${error.name}: ${error.message}` : String(error);
}

export function apiErrorResponse(error: unknown): Record<string, unknown> {
  if (error instanceof ApiError) {
    return {
      ok: false,
      error: error.message,
      kind: error.kind,
      ...(error.status !== undefined ? {status: error.status} : {}),
    };
  }
  if (error instanceof Error) {
    return {ok: false, error: error.message, name: error.name};
  }
  return {ok: false, error: 'Unknown FBDL API error.'};
}

/**
 * If the API refused a new run because the account already has too many active ones,
 * point at the cleanup workflow rather than leaving the agent to retry into the same
 * wall. Matches conservatively: a mention of "run" plus a quota-ish word.
 */
export function withMaxRunsHint(response: Record<string, unknown>): Record<string, unknown> {
  const haystackParts = ['error', 'title', 'detail', 'body']
    .map((key) => response[key])
    .filter((value): value is string => typeof value === 'string');
  if (haystackParts.length === 0) return response;

  const haystack = haystackParts.join(' ').toLowerCase();
  if (!haystack.includes('run')) return response;
  if (!['max', 'limit', 'quota', 'exceeded', 'too many'].some((t) => haystack.includes(t))) {
    return response;
  }
  return {
    ...response,
    hint:
      'You appear to have hit the max active FBDL runs limit. Call list_fbdl_runs to see ' +
      'your active runs, then archive_fbdl_run on the ones you no longer need to free up ' +
      'slots before retrying.',
  };
}

function buildReference(): string {
  const entitySection = getSetupEntities()
    .map((e) => {
      const params = e.params
        .map((p) => `  - ${p.name}${p.required ? '' : '?'}: ${p.description}`)
        .join('\n');
      return `### ${e.type}\n${e.description}\n${params}\nExample: ${e.example}`;
    })
    .join('\n\n');

  const actionSection = getActions()
    .map((a) => {
      const params = a.keywordParams
        .map((p) => `  - ${p.name}${p.required ? '' : '?'}: ${p.description}`)
        .join('\n');
      return `### ${a.name}\n${a.description}\nSignature: ${a.signature}\n${params}\nExamples: ${a.examples.join(' | ')}`;
    })
    .join('\n\n');

  return `# FBDL Reference\n\n## Setup Entities\n\n${entitySection}\n\n## Actions\n\n${actionSection}`;
}

function generatePrompt(description: string): string {
  const entities = getSetupEntities()
    .map(
      (e) =>
        `- **${e.type}**: ${e.description} Params: ${
          e.params
            .map(
              (p) =>
                `${p.name}${p.required ? '' : '?'}${p.values !== undefined ? `(${p.values.join('|')})` : ''}`,
            )
            .join(', ') || 'none'
        }`,
    )
    .join('\n');

  const actions = getActions()
    .map(
      (a) =>
        `- **${a.name}**: ${a.description} | Voice switcher: ${
          a.supportsVoiceSwitcher ? 'yes' : 'no'
        } | Params: ${
          a.keywordParams
            .map(
              (p) =>
                `${p.name}${p.required ? '' : '?'}${p.values !== undefined ? `(${p.values.join('|')})` : ''}`,
            )
            .join(', ') || 'none'
        }`,
    )
    .join('\n');

  return `You are an expert in FBDL (Facebook Developer Language), a DSL used for Meta's bug bounty program to create reproducible test scenarios.

## FBDL Syntax

### Setup Block
The script starts with a \`[setup]\` header on its own line, followed by ONE entity
declaration per line. Format:
\`\`\`
[setup]
  Type Label [with {key: value, ...}]
  Type Label [with {key: value, ...}]
\`\`\`

Available entity types:
${entities}

### Action Lines
After the setup block, an \`[action]\` header on its own line is followed by ONE
action per line. Format:
\`\`\`
[action]
  Subject [as VoiceSwitcher] action_name Label [with {key: value, ...}]
\`\`\`

Available actions:
${actions}

## Rules
1. All entities referenced in actions MUST be created in the setup block first.
2. Labels must be unique and descriptive (PascalCase).
3. Users must exist before being assigned roles.
4. Friendships must be established before friend-dependent actions.
5. Voice switcher (as) is only available for actions that support it.
6. The script is two blocks: a \`[setup]\` header then an \`[action]\` header, each on its own line.
7. Put each entity declaration and each action on its own line (one per line).
8. A setup-only script (no actions) is valid — omit the [action] block entirely.
9. Name labels after their role in the bug (Victim, Attacker, NonMember), not after their type.

## Task
Generate a valid FBDL script for the following scenario:

${description}

Output ONLY the FBDL script, no explanations.`;
}
