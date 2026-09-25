/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import assert from 'node:assert/strict';
import {test} from 'node:test';

import {stubFetch, testConfig} from '../../core/__tests__/support.js';
import {ApiError} from '../../core/errors.js';
import {AssetClient, MAX_BATCH_QUERIES} from '../assets.js';

async function expectError(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (error) {
    assert.ok(error instanceof ApiError, `expected ApiError, got ${String(error)}`);
    return error;
  }
  throw new Error('expected the call to reject');
}

test('resolve sends the identifier as q', async () => {
  const {fetchImpl, calls} = stubFetch([
    {status: 200, body: JSON.stringify({query: 'x', assets: [], object_name: ''})},
  ]);
  await new AssetClient(testConfig(), {fetch: fetchImpl}).resolve('doc_id=123');

  assert.equal(calls.length, 1);
  assert.equal(calls[0]?.method, 'GET');
  assert.equal(calls[0]?.url, 'https://api.facebook.com/bug_bounty/assets?q=doc_id%3D123');
});

test('resolve parses assets and the vanity name', async () => {
  const {fetchImpl} = stubFetch([
    {
      status: 200,
      body: JSON.stringify({
        query: '100064123456789',
        assets: [
          {type: 'ent_or_node', name: 'EntGroupMall'},
          {type: 'xcontroller', name: 'FooAjaxController'},
        ],
        object_name: 'some.page',
      }),
    },
  ]);
  const resolution = await new AssetClient(testConfig(), {fetch: fetchImpl}).resolve(
    '100064123456789',
  );

  assert.equal(resolution.query, '100064123456789');
  assert.deepEqual(resolution.assets, [
    {type: 'ent_or_node', name: 'EntGroupMall'},
    {type: 'xcontroller', name: 'FooAjaxController'},
  ]);
  assert.equal(resolution.objectName, 'some.page');
});

test('an empty assets array is an answer, not a failure', async () => {
  const {fetchImpl} = stubFetch([{status: 200, body: JSON.stringify({query: 'x', assets: []})}]);
  const resolution = await new AssetClient(testConfig(), {fetch: fetchImpl}).resolve('x');
  assert.deepEqual(resolution.assets, []);
  assert.equal(resolution.objectName, undefined);
});

test('an unrecognised asset type is passed through rather than dropped', async () => {
  const {fetchImpl} = stubFetch([
    {status: 200, body: JSON.stringify({query: 'x', assets: [{type: 'brand_new', name: 'Thing'}]})},
  ]);
  const resolution = await new AssetClient(testConfig(), {fetch: fetchImpl}).resolve('x');
  assert.deepEqual(resolution.assets, [{type: 'brand_new', name: 'Thing'}]);
});

test('a malformed asset entry is skipped without failing the lookup', async () => {
  const {fetchImpl} = stubFetch([
    {
      status: 200,
      body: JSON.stringify({
        query: 'x',
        assets: [{type: 'ent_or_node'}, null, 'nope', {type: 'bloks', name: 'ok'}],
      }),
    },
  ]);
  const resolution = await new AssetClient(testConfig(), {fetch: fetchImpl}).resolve('x');
  assert.deepEqual(resolution.assets, [{type: 'bloks', name: 'ok'}]);
});

test('an empty identifier is rejected before the network', async () => {
  const {fetchImpl, calls} = stubFetch([{status: 200, body: '{}'}]);
  const error = await expectError(new AssetClient(testConfig(), {fetch: fetchImpl}).resolve('  '));
  assert.equal(error.kind, 'bad-request');
  assert.equal(calls.length, 0);
});

test('resolveBatch posts to /batch and deduplicates the list', async () => {
  const {fetchImpl, calls} = stubFetch([
    {status: 200, body: JSON.stringify({results: [{query: 'a', assets: []}]})},
  ]);
  await new AssetClient(testConfig(), {fetch: fetchImpl}).resolveBatch(['a', 'a', ' a ', 'b']);

  assert.equal(calls.length, 1);
  assert.equal(calls[0]?.method, 'POST');
  assert.equal(calls[0]?.url, 'https://api.facebook.com/bug_bounty/assets/batch');
  assert.deepEqual(JSON.parse(calls[0]?.body ?? '{}'), {queries: ['a', 'b']});
});

test('resolveBatch chunks at the endpoint cap', async () => {
  const {fetchImpl, calls} = stubFetch([{status: 200, body: JSON.stringify({results: []})}]);
  const queries = Array.from({length: MAX_BATCH_QUERIES + 5}, (_, i) => `id-${i}`);
  await new AssetClient(testConfig(), {fetch: fetchImpl}).resolveBatch(queries);

  assert.equal(calls.length, 2);
  assert.equal(JSON.parse(calls[0]?.body ?? '{}').queries.length, MAX_BATCH_QUERIES);
  assert.equal(JSON.parse(calls[1]?.body ?? '{}').queries.length, 5);
});

test('a chunk that fails keeps the answers already paid for', async () => {
  const {fetchImpl} = stubFetch([
    {status: 200, body: JSON.stringify({results: [{query: 'id-0', assets: []}]})},
    {status: 429, body: ''},
  ]);
  const queries = Array.from({length: MAX_BATCH_QUERIES + 5}, (_, i) => `id-${i}`);
  const outcome = await new AssetClient(testConfig(), {fetch: fetchImpl}).resolveBatch(queries);

  assert.equal(outcome.resolutions.length, 1);
  assert.equal(outcome.failure?.kind, 'rate-limited');
});

test('an empty batch is rejected before the network', async () => {
  const {fetchImpl, calls} = stubFetch([{status: 200, body: '{}'}]);
  const error = await expectError(
    new AssetClient(testConfig(), {fetch: fetchImpl}).resolveBatch(['', '  ']),
  );
  assert.equal(error.kind, 'bad-request');
  assert.equal(calls.length, 0);
});

test('the batch endpoint is paced to its own, tighter allowance', async () => {
  // The server caps batch at 100/hour and single lookups at 1000, counted separately.
  // Pooling them would let a scan-heavy session overspend the batch budget tenfold.
  const {fetchImpl, calls} = stubFetch([
    {status: 200, body: JSON.stringify({results: [{query: 'a', assets: []}]})},
  ]);
  const client = new AssetClient(
    testConfig({rateLimit: {perHour: 3_600_000, burst: 1000, maxWaitMs: 0}}),
    {fetch: fetchImpl},
  );

  for (let i = 0; i < 100; i += 1) {
    const outcome = await client.resolveBatch([`id-${i}`]);
    assert.equal(outcome.failure, undefined, `batch ${i + 1} of 100 should be admitted`);
  }
  assert.equal(calls.length, 100);

  const spent = await client.resolveBatch(['one-too-many']);
  assert.equal(spent.failure?.kind, 'local-budget');
  assert.equal(calls.length, 100, 'the refused batch must not reach the network');

  // The single-lookup endpoint has its own budget and is untouched by that.
  await client.resolve('100064123456789');
  assert.equal(calls.length, 101);
});

test('a base URL without a trailing slash does not produce a doubled path separator', async () => {
  const {fetchImpl, calls} = stubFetch([
    {status: 200, body: JSON.stringify({query: 'a', assets: []})},
  ]);
  await new AssetClient(testConfig({baseUrl: 'https://api.example.internal'}), {
    fetch: fetchImpl,
  }).resolve('a');
  assert.equal(calls[0]?.url, 'https://api.example.internal/bug_bounty/assets?q=a');
});
