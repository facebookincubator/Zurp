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
import {AssetClient} from '../assets.js';
import {buildServer} from '../server.js';

const CONFIG = testConfig();

interface Harness {
  client: Client;
  requests: Array<{url: string; body: string | undefined}>;
}

/** Wires a real MCP client to the real server over an in-memory pair. */
async function connect(
  respond: (url: string, body: string | undefined) => {status: number; body: string},
): Promise<Harness> {
  const requests: Array<{url: string; body: string | undefined}> = [];
  const fetchImpl: FetchLike = async (url, init) => {
    requests.push({url, body: init.body});
    const {status, body} = respond(url, init.body);
    return {status, text: async () => body};
  };

  const server = buildServer(CONFIG, {client: new AssetClient(CONFIG, {fetch: fetchImpl})});
  const client = new Client({name: 'test', version: '0'});
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  await Promise.all([server.connect(serverTransport), client.connect(clientTransport)]);

  return {client, requests};
}

function textOf(result: unknown): string {
  const {content} = result as {content: Array<{type: string; text?: string}>};
  return content.map((block) => block.text ?? '').join('\n');
}

test('the server advertises the three Meta Context tools', async () => {
  const {client} = await connect(() => ({status: 200, body: '{}'}));
  const {tools} = await client.listTools();

  assert.deepEqual(
    tools.map((t) => t.name).sort(),
    ['meta_context_resolve', 'meta_context_resolve_batch', 'meta_context_scan'],
  );
  for (const tool of tools) {
    assert.ok((tool.description ?? '').length > 0, `${tool.name} needs a description`);
    assert.equal(tool.inputSchema.type, 'object');
  }
  await client.close();
});

test('meta_context_resolve returns the asset behind one identifier', async () => {
  const {client, requests} = await connect(() => ({
    status: 200,
    body: JSON.stringify({
      query: '100064123456789',
      assets: [{type: 'ent_or_node', name: 'EntGroupMall'}],
      object_name: 'some.page',
    }),
  }));

  const result = await client.callTool({
    name: 'meta_context_resolve',
    arguments: {query: '100064123456789'},
  });

  assert.notEqual(result.isError, true);
  assert.match(textOf(result), /ent_or_node\s+EntGroupMall/);
  assert.match(textOf(result), /vanity: some\.page/);
  assert.equal(requests.length, 1);
  assert.match(requests[0]?.url ?? '', /\/bug_bounty\/assets\?q=100064123456789$/);
  await client.close();
});

test('meta_context_scan extracts identifiers from a report and resolves them in one batch', async () => {
  const {client, requests} = await connect(() => ({
    status: 200,
    body: JSON.stringify({
      results: [
        {query: 'doc_id=9876543210987654', assets: [{type: 'graphql', name: 'GroupsMallQuery'}]},
        {query: 'https://www.facebook.com/ajax/foo/', assets: []},
        {query: '100064123456789', assets: []},
      ],
    }),
  }));

  const report = `Sending doc_id=9876543210987654 to https://www.facebook.com/ajax/foo/
leaks the group owned by 100064123456789.`;
  const result = await client.callTool({name: 'meta_context_scan', arguments: {text: report}});

  assert.notEqual(result.isError, true);
  assert.match(textOf(result), /Extracted 3 candidates/);
  assert.match(textOf(result), /graphql\s+GroupsMallQuery/);

  // One batch request, carrying exactly the scraped identifiers.
  assert.equal(requests.length, 1);
  assert.match(requests[0]?.url ?? '', /\/bug_bounty\/assets\/batch$/);
  assert.deepEqual(JSON.parse(requests[0]?.body ?? '{}'), {
    queries: [
      'doc_id=9876543210987654',
      'https://www.facebook.com/ajax/foo/',
      '100064123456789',
    ],
  });
  await client.close();
});

test('meta_context_scan spends no request when the text carries no identifiers', async () => {
  const {client, requests} = await connect(() => ({status: 200, body: '{}'}));
  const result = await client.callTool({
    name: 'meta_context_scan',
    arguments: {text: 'The page returned a 500 with an empty body.'},
  });

  assert.notEqual(result.isError, true);
  assert.match(textOf(result), /No candidate identifiers found/);
  assert.equal(requests.length, 0);
  await client.close();
});

test('a rejected token surfaces as a tool error rather than a dropped connection', async () => {
  const {client} = await connect(() => ({
    status: 403,
    body: '{"title":"Forbidden","detail":"Requesting user is not enrolled."}',
  }));
  const result = await client.callTool({
    name: 'meta_context_resolve',
    arguments: {query: '100064123456789'},
  });

  assert.equal(result.isError, true);
  assert.match(textOf(result), /forbidden: .*researcher API allowlist/);
  assert.match(textOf(result), /Requesting user is not enrolled\./);
  await client.close();
});

test('a bad argument is rejected by the schema before the handler runs', async () => {
  const {client, requests} = await connect(() => ({status: 200, body: '{}'}));
  const result = await client.callTool({name: 'meta_context_resolve', arguments: {query: ''}});

  assert.equal(result.isError, true);
  assert.equal(requests.length, 0);
  await client.close();
});
