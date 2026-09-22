/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

/**
 * Covers the ported fbdl-mcp surface plus the parts that changed in porting: the API
 * client now sits on the shared core, and a failed spec load degrades instead of
 * stopping the server from starting.
 */

import assert from 'node:assert/strict';
import {beforeEach, test} from 'node:test';

import {Client} from '@modelcontextprotocol/sdk/client/index.js';
import {InMemoryTransport} from '@modelcontextprotocol/sdk/inMemory.js';

import {stubFetch, testConfig} from '../../core/__tests__/support.js';
import {ApiError} from '../../core/errors.js';
import {FbdlApi} from '../api.js';
import {resetRunGuard, tryAcquireCreateSlot} from '../runGuard.js';
import {clearSpec} from '../spec.js';
import {parseFbdlReference} from '../specParser.js';
import {buildServer} from '../server.js';
import {validate} from '../validator.js';
import {FIXTURE_JSON, installFixtureSpec} from './fixtures/loadFixtureSpec.js';

const SCRIPT = ['[setup]', '  User Victim', '  User Attacker', '', '[action]', '  Attacker follow Victim'].join('\n');

beforeEach(() => {
  installFixtureSpec();
  resetRunGuard();
});

async function expectError(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (error) {
    assert.ok(error instanceof ApiError, `expected ApiError, got ${String(error)}`);
    return error;
  }
  throw new Error('expected the call to reject');
}

// ---------------------------------------------------------------- spec parsing

test('the reference response parses into the real entity and action set', () => {
  const parsed = parseFbdlReference(FIXTURE_JSON);
  const types = parsed.entities.map((e) => e.type);

  assert.ok(types.includes('User'));
  assert.ok(types.includes('Page'));
  assert.ok(types.includes('Group'));
  // Multi-field test-user helpers are not valid [setup] declarations and are filtered.
  assert.ok(!types.includes('WhitehatTestUser'));
  assert.ok(parsed.actions.length > 50);
  assert.ok(parsed.actions.some((a) => a.name === 'make_post_text'));
});

test('a bool param becomes an enum and a string values_json becomes its value set', () => {
  const parsed = parseFbdlReference(FIXTURE_JSON);
  const post = parsed.actions.find((a) => a.name === 'make_post_text');
  const audience = post?.keywordParams.find((p) => p.name === 'audience');
  assert.deepEqual([...(audience?.values ?? [])].sort(), ['friends', 'only_me', 'public']);
});

test('a malformed reference response is rejected with a named error', () => {
  assert.throws(() => parseFbdlReference(null), /must be a JSON object/);
  assert.throws(() => parseFbdlReference({}), /missing the 'hints' object/);
  assert.throws(() => parseFbdlReference({hints: {setup: 'nope'}}), /must be arrays/);
});

// ---------------------------------------------------------------- validator

test('a well-formed script validates and reports its labels', () => {
  const result = validate(SCRIPT);
  assert.equal(result.valid, true, JSON.stringify(result.errors));
  assert.deepEqual([...result.definedLabels].sort(), ['Attacker', 'Victim']);
});

test('an unknown entity or action is caught with its line number', () => {
  const unknownEntity = validate('[setup]\n  Wombat Fred');
  assert.equal(unknownEntity.valid, false);
  assert.match(unknownEntity.errors[0]?.message ?? '', /Unknown setup entity type: "Wombat"/);
  assert.equal(unknownEntity.errors[0]?.line, 2);

  const unknownAction = validate('[setup]\n  User A\n[action]\n  A frobnicate B');
  assert.equal(unknownAction.valid, false);
  assert.match(unknownAction.errors[0]?.message ?? '', /Unknown action: "frobnicate"/);
});

test('the single-line setup form is rejected, because the API rejects it too', () => {
  const result = validate('[setup] User A User B');
  assert.equal(result.valid, false);
  assert.match(result.errors[0]?.message ?? '', /must be on its own line/);
});

test('an empty setup block is an error', () => {
  const result = validate('[setup]\n');
  assert.equal(result.valid, false);
  assert.match(result.errors[0]?.message ?? '', /Empty setup block/);
});

test('a line before any block header is an error', () => {
  const result = validate('User A');
  assert.equal(result.valid, false);
  assert.match(result.errors[0]?.message ?? '', /outside any block/);
});

test('an out-of-range enum value is caught and the valid ones are listed', () => {
  const result = validate(
    '[setup]\n  User A\n[action]\n  A make_post_text with {content: "x", audience: everyone}',
  );
  assert.equal(result.valid, false);
  const enumError = result.errors.find((e) => /invalid value "everyone"/.test(e.message));
  assert.ok(enumError !== undefined, JSON.stringify(result.errors));
  assert.match(enumError.message, /friends/);
});

test('an unknown keyword param is caught', () => {
  const result = validate('[setup]\n  User A\n[action]\n  A make_post_text with {nonsense: 1}');
  assert.equal(result.valid, false);
  assert.ok(result.errors.some((e) => /unknown param "nonsense"/.test(e.message)));
});

test('comments and blank lines are ignored', () => {
  const result = validate('# a comment\n[setup]\n\n  User A\n  # another\n');
  assert.equal(result.valid, true, JSON.stringify(result.errors));
});

test('setup-only scripts are valid — the action block is optional', () => {
  assert.equal(validate('[setup]\n  User A').valid, true);
});

// ---------------------------------------------------------------- run guard

test('only one create can be in flight at a time', () => {
  const first = tryAcquireCreateSlot();
  assert.equal(first.ok, true);

  const second = tryAcquireCreateSlot();
  assert.equal(second.ok, false);
  assert.equal(second.ok === false ? second.reason : '', 'in_flight');
});

test('a cooldown follows a completed create, longer after a failure', () => {
  const success = tryAcquireCreateSlot();
  assert.ok(success.ok);
  success.slot.release('success');

  const now = Date.now();
  const blocked = tryAcquireCreateSlot(now);
  assert.equal(blocked.ok, false);
  if (blocked.ok === false) {
    assert.equal(blocked.reason, 'cooldown');
    assert.ok((blocked.cooldownRemainingMs ?? 0) > 25_000);
    assert.ok((blocked.cooldownRemainingMs ?? 0) <= 30_000);
  }

  // Past the success cooldown, a failure then imposes a longer one.
  const later = tryAcquireCreateSlot(now + 31_000);
  assert.ok(later.ok);
  later.slot.release('failure');
  const afterFailure = tryAcquireCreateSlot(Date.now() + 31_000);
  assert.equal(afterFailure.ok, false);
  if (afterFailure.ok === false) {
    assert.ok((afterFailure.cooldownRemainingMs ?? 0) > 25_000);
  }
});

// ---------------------------------------------------------------- api client

test('the API client hits the documented routes with the documented payloads', async () => {
  const {fetchImpl, calls} = stubFetch([{status: 201, body: '{"id":"555"}'}]);
  const api = new FbdlApi(testConfig(), {fetch: fetchImpl});

  await api.createRun({fbdlCode: SCRIPT, note: 'repro T123'});
  assert.equal(calls[0]?.method, 'POST');
  assert.equal(calls[0]?.url, 'https://api.facebook.com/bug_bounty/fbdl_runs');
  assert.deepEqual(JSON.parse(calls[0]?.body ?? '{}'), {fbdl_code: SCRIPT, note: 'repro T123'});

  await api.listRuns({limit: 5, after: 'CURSOR'});
  assert.equal(calls[1]?.url, 'https://api.facebook.com/bug_bounty/fbdl_runs?limit=5&after=CURSOR');

  await api.getRun('555');
  assert.equal(calls[2]?.url, 'https://api.facebook.com/bug_bounty/fbdl_runs/555');

  await api.archiveRun('555');
  assert.equal(calls[3]?.method, 'POST');
  assert.equal(calls[3]?.url, 'https://api.facebook.com/bug_bounty/fbdl_runs/555/archive');

  await api.fetchReference();
  assert.equal(calls[4]?.url, 'https://api.facebook.com/bug_bounty/fbdl_reference/');

  for (const call of calls) {
    assert.equal(call.headers['Authorization'], 'Bearer EAAB-test');
    assert.match(call.headers['User-Agent'] ?? '', /^bug-bounty-research\//);
  }
});

test('an empty note is refused before the network', async () => {
  const {fetchImpl, calls} = stubFetch([{status: 201, body: '{"id":"1"}'}]);
  const api = new FbdlApi(testConfig(), {fetch: fetchImpl});
  assert.equal((await expectError(api.createRun({fbdlCode: SCRIPT, note: ' '}))).kind, 'bad-request');
  assert.equal(calls.length, 0);
});

test('a missing run names the id, via the shared error taxonomy', async () => {
  const {fetchImpl} = stubFetch([
    {status: 404, body: '{"title":"FBDL run not found","detail":"No run with id=7"}'},
  ]);
  const error = await expectError(new FbdlApi(testConfig(), {fetch: fetchImpl}).getRun('7'));
  assert.equal(error.kind, 'not-found');
  assert.match(error.message, /FBDL run 7 was not found/);
});

// ---------------------------------------------------------------- protocol

async function connect(
  responses: Parameters<typeof stubFetch>[0],
  specError?: Error,
): Promise<{client: Client; calls: ReturnType<typeof stubFetch>['calls']}> {
  const {fetchImpl, calls} = stubFetch(responses);
  const config = testConfig();
  const server = buildServer(config, {
    api: new FbdlApi(config, {fetch: fetchImpl}),
    ...(specError !== undefined ? {specError} : {}),
  });
  const client = new Client({name: 'test', version: '0'});
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  await Promise.all([server.connect(serverTransport), client.connect(clientTransport)]);
  return {client, calls};
}

function textOf(result: unknown): string {
  const {content} = result as {content: Array<{type: string; text?: string}>};
  return content.map((block) => block.text ?? '').join('\n');
}

test('the server advertises the upstream tool surface', async () => {
  const {client} = await connect([{status: 200, body: '{}'}]);
  const {tools} = await client.listTools();

  assert.deepEqual(
    tools.map((t) => t.name).sort(),
    [
      'archive_fbdl_run',
      'create_fbdl_run',
      'explain_fbdl',
      'get_fbdl_run',
      'list_actions',
      'list_entities',
      'list_fbdl_runs',
      'validate_fbdl',
    ],
  );
  await client.close();
});

test('the reference resource and the generate prompt are registered', async () => {
  const {client} = await connect([{status: 200, body: '{}'}]);
  assert.ok((await client.listResources()).resources.some((r) => r.uri === 'fbdl://reference'));
  assert.ok((await client.listPrompts()).prompts.some((p) => p.name === 'generate_fbdl'));
  await client.close();
});

test('the language tools answer without touching the network', async () => {
  const {client, calls} = await connect([{status: 200, body: '{}'}]);

  const validated = await client.callTool({name: 'validate_fbdl', arguments: {script: SCRIPT}});
  assert.match(textOf(validated), /"valid": true/);

  const entities = await client.callTool({name: 'list_entities', arguments: {type: 'Group'}});
  assert.match(textOf(entities), /## Group/);

  const actions = await client.callTool({name: 'list_actions', arguments: {name: 'make_post_text'}});
  assert.match(textOf(actions), /## make_post_text/);

  const explained = await client.callTool({name: 'explain_fbdl', arguments: {script: SCRIPT}});
  assert.match(explained.content ? textOf(explained) : '', /Setup block/);

  assert.equal(calls.length, 0, 'the language tools must spend no budget');
  await client.close();
});

test('an invalid script is refused before the API sees it', async () => {
  const {client, calls} = await connect([{status: 201, body: '{"id":"1"}'}]);
  const result = await client.callTool({
    name: 'create_fbdl_run',
    arguments: {fbdl_code: '[setup]\n  Wombat Fred', note: 'n'},
  });

  assert.match(textOf(result), /Validation failed. The run was not submitted./);
  assert.equal(calls.length, 0);
  await client.close();
});

test('create submits a valid script and the guard then blocks the next one', async () => {
  const {client, calls} = await connect([{status: 201, body: '{"id":"555"}'}]);

  const first = await client.callTool({
    name: 'create_fbdl_run',
    arguments: {fbdl_code: SCRIPT, note: 'repro T123'},
  });
  assert.match(textOf(first), /"ok": true/);
  assert.match(textOf(first), /"id": "555"/);
  assert.equal(calls.length, 1);

  const second = await client.callTool({
    name: 'create_fbdl_run',
    arguments: {fbdl_code: SCRIPT, note: 'again'},
  });
  assert.match(textOf(second), /"reason": "cooldown"/);
  assert.equal(calls.length, 1, 'the cooldown must not spend a request');
  await client.close();
});

test('hitting the active-run cap comes back with the cleanup workflow attached', async () => {
  const {client} = await connect([
    {
      status: 400,
      body: '{"title":"Cannot create FBDL run","detail":"Too many active runs. Max is 10."}',
    },
  ]);
  const result = await client.callTool({
    name: 'create_fbdl_run',
    arguments: {fbdl_code: SCRIPT, note: 'n'},
  });

  assert.match(textOf(result), /Too many active runs/);
  assert.match(textOf(result), /archive_fbdl_run/);
  await client.close();
});

test('the token never reaches a tool result, however the endpoint echoes it', async () => {
  const {client} = await connect([
    {status: 400, body: '{"title":"Bad","detail":"token EAAB-test rejected"}'},
  ]);
  const result = await client.callTool({name: 'list_fbdl_runs', arguments: {}});

  assert.ok(!textOf(result).includes('EAAB-test'));
  assert.match(textOf(result), /<redacted>/);
  await client.close();
});

test('a failed spec load degrades the language tools instead of the whole server', async () => {
  clearSpec();
  const {client, calls} = await connect(
    [{status: 200, body: JSON.stringify({data: []})}],
    new Error('reference API returned 403'),
  );

  const validated = await client.callTool({name: 'validate_fbdl', arguments: {script: SCRIPT}});
  assert.equal(validated.isError, true);
  assert.match(textOf(validated), /spec is not available/);
  assert.match(textOf(validated), /reference API returned 403/);

  // Run management still works — it needs the API, not the grammar.
  const listed = await client.callTool({name: 'list_fbdl_runs', arguments: {}});
  assert.match(textOf(listed), /"ok": true/);
  assert.equal(calls.length, 1);
  await client.close();
});
