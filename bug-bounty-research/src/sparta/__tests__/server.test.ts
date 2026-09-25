/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import assert from 'node:assert/strict';
import {test} from 'node:test';

import {Client} from '@modelcontextprotocol/sdk/client/index.js';
import {InMemoryTransport} from '@modelcontextprotocol/sdk/inMemory.js';

import type {FetchLike} from '../../core/client.js';
import {testConfig} from '../../core/__tests__/support.js';
import {SpartaClient} from '../findings.js';
import {buildServer} from '../server.js';

const CONFIG = testConfig();

const HIGH = {
  bb_finding_id: 'BB-1',
  title: 'Missing viewer check on the group mall',
  summary: 'The mall query returns members of a group the viewer is not in.',
  priority: 'high',
  target_id: '9876543210987654',
  target_type: 'published_doc_id',
  poc_doc_id: '9876543210987654',
  poc_variables_json: '{"groupID":"{{GROUP_ID}}"}',
  poc_placeholders_json: '{"GROUP_ID":"a group you are not a member of"}',
  published_at: 1_755_000_000,
};

/** `SpartaBountyLeadPriority` has exactly two values; there is no `low`. */
const MEDIUM = {
  bb_finding_id: 'BB-2',
  title: 'Verbose error on the profile query',
  summary: 'An invalid id echoes the internal exception.',
  priority: 'medium',
  target_id: 'CometProfileQuery',
  target_type: 'endpoint_name',
  poc_doc_id: 'CometProfileQuery',
  poc_variables_json: '{"id":"{{PROFILE_ID}}"}',
  poc_placeholders_json: '',
  published_at: 1_756_000_000,
};

const NOT_FOUND =
  '{"title":"Finding not found","detail":"No SPARTA bounty finding with that id is visible to you."}';

interface Harness {
  client: Client;
  urls: string[];
}

/**
 * Serves both real routes off one set of findings: the list route returns all of them,
 * the by-id route matches on the last path segment and 404s otherwise. A non-200 `status`
 * is returned for every route, which is how the auth cases are driven.
 */
async function connect(
  findings: ReadonlyArray<Record<string, unknown>>,
  status = 200,
): Promise<Harness> {
  const urls: string[] = [];
  const fetchImpl: FetchLike = async (url) => {
    urls.push(url);
    if (status !== 200) {
      return {status, text: async () => '{"title":"Forbidden","detail":"Not enrolled."}'};
    }
    const byId = /\/sparta_findings\/([^?/]+)$/.exec(url);
    if (byId !== null) {
      const match = findings.find((f) => f.bb_finding_id === byId[1]);
      return match === undefined
        ? {status: 404, text: async () => NOT_FOUND}
        : {status: 200, text: async () => JSON.stringify(match)};
    }
    return {status: 200, text: async () => JSON.stringify({data: findings})};
  };

  const server = buildServer(CONFIG, {client: new SpartaClient(CONFIG, {fetch: fetchImpl})});
  const client = new Client({name: 'test', version: '0'});
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  await Promise.all([server.connect(serverTransport), client.connect(clientTransport)]);
  return {client, urls};
}

function textOf(result: unknown): string {
  const {content} = result as {content: Array<{type: string; text?: string}>};
  return content.map((block) => block.text ?? '').join('\n');
}

const CATALOG = [MEDIUM, HIGH];

test('the server advertises the four SPARTA tools', async () => {
  const {client} = await connect(CATALOG);
  const {tools} = await client.listTools();

  assert.deepEqual(
    tools.map((t) => t.name).sort(),
    ['sparta_build_poc', 'sparta_get_finding', 'sparta_list_findings', 'sparta_scan_traffic'],
  );
  for (const tool of tools) {
    assert.ok((tool.description ?? '').length > 0, `${tool.name} needs a description`);
    assert.equal(tool.inputSchema.type, 'object');
  }
  await client.close();
});

test('sparta_list_findings orders the catalog worst first', async () => {
  const {client, urls} = await connect(CATALOG);
  const result = await client.callTool({name: 'sparta_list_findings', arguments: {}});

  assert.notEqual(result.isError, true);
  const text = textOf(result);
  assert.match(text, /2 SPARTA findings disclosed/);
  assert.ok(text.indexOf('BB-1') < text.indexOf('BB-2'), 'high must precede medium');
  assert.equal(urls.length, 1);
  assert.match(urls[0] ?? '', /\/bug_bounty\/sparta_findings\?limit=100$/);
  await client.close();
});

test('sparta_list_findings filters on priority and says what it filtered from', async () => {
  const {client} = await connect(CATALOG);
  const result = await client.callTool({
    name: 'sparta_list_findings',
    arguments: {priority: 'HIGH'},
  });

  const text = textOf(result);
  assert.match(text, /BB-1/);
  assert.doesNotMatch(text, /BB-2/);
  assert.match(text, /Filtered to priority HIGH, from 2 disclosed/);
  await client.close();
});

test('an empty catalog reads as a normal answer rather than a failure', async () => {
  const {client} = await connect([]);
  const result = await client.callTool({name: 'sparta_list_findings', arguments: {}});

  assert.notEqual(result.isError, true);
  assert.match(textOf(result), /No SPARTA findings are disclosed .* not an error/s);
  await client.close();
});

test('sparta_scan_traffic reports the findings for the endpoints in a captured request', async () => {
  const {client, urls} = await connect(CATALOG);
  const request = `POST /api/graphql/ HTTP/1.1
Host: www.facebook.com

fb_dtsg=abc&fb_api_req_friendly_name=CometProfileQuery&doc_id=9876543210987654`;

  const result = await client.callTool({name: 'sparta_scan_traffic', arguments: {text: request}});

  assert.notEqual(result.isError, true);
  const text = textOf(result);
  assert.match(text, /Found 2 targets in that traffic\. 2 findings disclosed across 2 of them\./);
  assert.match(text, /doc_id=9876543210987654/);
  assert.match(text, /operation CometProfileQuery/);
  // The sweep answers both targets; neither costs its own request.
  assert.equal(urls.length, 1);
  await client.close();
});

test('sparta_scan_traffic spends no request on traffic with no SPARTA target in it', async () => {
  const {client, urls} = await connect(CATALOG);
  const result = await client.callTool({
    name: 'sparta_scan_traffic',
    arguments: {text: 'GET /some/page HTTP/1.1'},
  });

  assert.notEqual(result.isError, true);
  assert.match(textOf(result), /No SPARTA targets found/);
  assert.equal(urls.length, 0);
  await client.close();
});

test('sparta_build_poc renders the call with its placeholders called out', async () => {
  const {client} = await connect(CATALOG);
  const result = await client.callTool({
    name: 'sparta_build_poc',
    arguments: {finding_id: 'BB-1'},
  });

  assert.notEqual(result.isError, true);
  const text = textOf(result);
  assert.match(text, /POST https:\/\/www\.facebook\.com\/api\/graphql\//);
  assert.match(text, /\{\{GROUP_ID\}\}/);
  assert.match(text, /Substitute 2 placeholders first/);
  assert.match(text, /GROUP_ID\s+a group you are not a member of/);
  assert.match(text, /Do not fill these with identifiers belonging to real accounts/);
  await client.close();
});

test('sparta_get_finding returns the full summary and points at the PoC tool', async () => {
  const {client} = await connect(CATALOG);
  const result = await client.callTool({
    name: 'sparta_get_finding',
    arguments: {finding_id: 'BB-1'},
  });

  const text = textOf(result);
  assert.match(text, /members of a group the viewer is not in/);
  assert.match(text, /sparta_build_poc\(\{finding_id: "BB-1"\}\)/);
  await client.close();
});

test('reading one finding by id does not sweep the whole catalog first', async () => {
  const {client, urls} = await connect(CATALOG);
  await client.callTool({name: 'sparta_get_finding', arguments: {finding_id: 'BB-1'}});

  assert.equal(urls.length, 1);
  assert.match(urls[0] ?? '', /\/bug_bounty\/sparta_findings\/BB-1$/);
  await client.close();
});

test('an unknown finding id is a tool error, not an empty success', async () => {
  const {client} = await connect(CATALOG);
  const result = await client.callTool({
    name: 'sparta_get_finding',
    arguments: {finding_id: 'BB-404'},
  });

  assert.equal(result.isError, true);
  assert.match(textOf(result), /not-found: SPARTA finding BB-404 was not found/);
  await client.close();
});

test('a rejected token surfaces as a tool error rather than a dropped connection', async () => {
  const {client} = await connect(CATALOG, 403);
  const result = await client.callTool({name: 'sparta_list_findings', arguments: {}});

  assert.equal(result.isError, true);
  assert.match(textOf(result), /forbidden: .*researcher API allowlist/);
  await client.close();
});
