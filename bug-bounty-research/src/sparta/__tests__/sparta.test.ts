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
import type {Finding} from '../findings.js';
import {MAX_PAGE_SIZE, SpartaClient} from '../findings.js';
import {buildPoc} from '../poc.js';
import {extractTargets} from '../targets.js';

function finding(overrides: Partial<Record<string, unknown>> = {}): Record<string, unknown> {
  return {
    bb_finding_id: 'BB-1',
    title: 'Missing viewer check',
    summary: 'The mall query returns members of a group the viewer is not in.',
    priority: 'high',
    target_id: '9876543210987654',
    target_type: 'published_doc_id',
    poc_doc_id: '9876543210987654',
    poc_variables_json: '{"groupID":"{{GROUP_ID}}"}',
    poc_placeholders_json: '{"GROUP_ID":"a group you are not a member of"}',
    published_at: 1_755_000_000,
    ...overrides,
  };
}

function page(data: unknown[], after?: string): string {
  return JSON.stringify({
    data,
    ...(after !== undefined ? {paging: {cursors: {after}}} : {}),
  });
}

async function expectError(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (error) {
    assert.ok(error instanceof ApiError, `expected ApiError, got ${String(error)}`);
    return error;
  }
  throw new Error('expected the call to reject');
}

test('a finding is parsed from the wire shape the endpoint sends', async () => {
  const {fetchImpl} = stubFetch([{status: 200, body: page([finding()])}]);
  const catalog = await new SpartaClient(testConfig(), {fetch: fetchImpl}).loadCatalog();

  assert.equal(catalog.complete, true);
  assert.equal(catalog.findings.length, 1);
  const parsed = catalog.findings[0] as Finding;
  assert.equal(parsed.id, 'BB-1');
  assert.equal(parsed.priority, 'high');
  assert.deepEqual(parsed.target, {type: 'published_doc_id', id: '9876543210987654'});
  assert.equal(parsed.publishedAt, 1_755_000_000);
});

test('a finding without bb_finding_id is skipped rather than failing the page', async () => {
  const {fetchImpl} = stubFetch([
    {status: 200, body: page([{title: 'no id'}, null, 'nope', finding()])},
  ]);
  const catalog = await new SpartaClient(testConfig(), {fetch: fetchImpl}).loadCatalog();
  assert.deepEqual(
    catalog.findings.map((f) => f.id),
    ['BB-1'],
  );
});

test('an unknown target_type leaves the target unset instead of inventing one', async () => {
  const {fetchImpl} = stubFetch([
    {status: 200, body: page([finding({target_type: 'brand_new', target_id: 'x'})])},
  ]);
  const catalog = await new SpartaClient(testConfig(), {fetch: fetchImpl}).loadCatalog();
  assert.equal(catalog.findings[0]?.target, undefined);
});

test('the sweep follows cursors and asks for the endpoint page cap', async () => {
  const {fetchImpl, calls} = stubFetch([
    {status: 200, body: page([finding({bb_finding_id: 'BB-1'})], 'cursor-2')},
    {status: 200, body: page([finding({bb_finding_id: 'BB-2'})])},
  ]);
  const catalog = await new SpartaClient(testConfig(), {fetch: fetchImpl}).loadCatalog();

  assert.deepEqual(
    catalog.findings.map((f) => f.id),
    ['BB-1', 'BB-2'],
  );
  assert.equal(catalog.complete, true);
  assert.equal(calls.length, 2);
  assert.match(calls[0]?.url ?? '', new RegExp(`limit=${MAX_PAGE_SIZE}$`));
  assert.match(calls[1]?.url ?? '', /after=cursor-2/);
});

test('a finding disclosed on two pages is kept once', async () => {
  const {fetchImpl} = stubFetch([
    {status: 200, body: page([finding()], 'cursor-2')},
    {status: 200, body: page([finding()])},
  ]);
  const catalog = await new SpartaClient(testConfig(), {fetch: fetchImpl}).loadCatalog();
  assert.equal(catalog.findings.length, 1);
});

test('the catalog is swept once and reused, however many lookups follow', async () => {
  const {fetchImpl, calls} = stubFetch([{status: 200, body: page([finding()])}]);
  const client = new SpartaClient(testConfig(), {fetch: fetchImpl});

  await client.loadCatalog();
  await client.findingsForTarget({type: 'published_doc_id', id: '9876543210987654'});
  await client.findingById('BB-1');

  assert.equal(calls.length, 1, 'one sweep should answer all three');
});

test('concurrent callers share one sweep rather than each starting their own', async () => {
  const {fetchImpl, calls} = stubFetch([{status: 200, body: page([finding()])}]);
  const client = new SpartaClient(testConfig(), {fetch: fetchImpl});

  await Promise.all([client.loadCatalog(), client.loadCatalog(), client.loadCatalog()]);
  assert.equal(calls.length, 1);
});

test('a target with no findings in a complete catalog costs no extra request', async () => {
  const {fetchImpl, calls} = stubFetch([{status: 200, body: page([finding()])}]);
  const client = new SpartaClient(testConfig(), {fetch: fetchImpl});

  const found = await client.findingsForTarget({type: 'endpoint_name', id: 'SomethingElseQuery'});
  assert.deepEqual(found, []);
  assert.equal(calls.length, 1, 'a complete catalog already proves there is nothing');
});

test('an incomplete catalog falls back to asking about the target directly', async () => {
  // 50 pages of cursors is the sweep cap; the 51st response answers the per-target query.
  const responses = Array.from({length: 50}, (_, i) => ({
    status: 200,
    body: page([finding({bb_finding_id: `BB-${i}`})], `cursor-${i + 1}`),
  }));
  responses.push({status: 200, body: page([finding({bb_finding_id: 'BB-TARGETED'})], 'unused')});
  const {fetchImpl, calls} = stubFetch(responses);
  const client = new SpartaClient(testConfig(), {fetch: fetchImpl});

  const catalog = await client.loadCatalog();
  assert.equal(catalog.complete, false);

  const found = await client.findingsForTarget({type: 'endpoint_name', id: 'UnsweptQuery'});
  assert.deepEqual(
    found.map((f) => f.id),
    ['BB-TARGETED'],
  );
  assert.equal(calls.length, 51);
  assert.match(calls[50]?.url ?? '', /target_id=UnsweptQuery&target_type=endpoint_name/);
});

test('a finding already in the catalog costs no request to read again', async () => {
  const {fetchImpl, calls} = stubFetch([{status: 200, body: page([finding()])}]);
  const client = new SpartaClient(testConfig(), {fetch: fetchImpl});

  await client.loadCatalog();
  assert.equal((await client.findingById('BB-1')).title, 'Missing viewer check');
  assert.equal(calls.length, 1);
});

test('a finding not in the catalog is fetched from the endpoint by-id route', async () => {
  // The by-id route is what reaches a finding past the server's visible-corpus window,
  // or one a colleague named, without sweeping the whole catalog to look for it.
  const {fetchImpl, calls} = stubFetch([
    {status: 200, body: JSON.stringify(finding({bb_finding_id: 'BB-OTHER'}))},
  ]);
  const client = new SpartaClient(testConfig(), {fetch: fetchImpl});

  assert.equal((await client.findingById('BB-OTHER')).id, 'BB-OTHER');
  assert.equal(calls.length, 1);
  assert.equal(
    calls[0]?.url,
    'https://api.facebook.com/bug_bounty/sparta_findings/BB-OTHER',
  );
});

test('the by-id route spends a different budget from the list route', async () => {
  const {fetchImpl} = stubFetch([
    {status: 200, body: page([finding()])},
    {status: 200, body: JSON.stringify(finding({bb_finding_id: 'BB-OTHER'}))},
  ]);
  const client = new SpartaClient(testConfig(), {fetch: fetchImpl});

  await client.loadCatalog();
  await client.findingById('BB-OTHER');

  assert.deepEqual(Object.keys(client.budget()).sort(), [
    'sparta_findings:get',
    'sparta_findings:list',
  ]);
});

test('an unknown finding id is not-found rather than a silent empty answer', async () => {
  const {fetchImpl} = stubFetch([
    {
      status: 404,
      body: '{"title":"Finding not found","detail":"No SPARTA bounty finding with that id is visible to you."}',
    },
  ]);
  const client = new SpartaClient(testConfig(), {fetch: fetchImpl});
  const error = await expectError(client.findingById('BB-NOPE'));

  assert.equal(error.kind, 'not-found');
  assert.match(error.message, /SPARTA finding BB-NOPE was not found/);
  // The server will not say whether it exists and is someone else's; nor should we.
  assert.match(error.message, /No SPARTA bounty finding with that id is visible to you/);
});

test('an empty finding id is rejected before the network', async () => {
  const {fetchImpl, calls} = stubFetch([{status: 200, body: page([])}]);
  const error = await expectError(new SpartaClient(testConfig(), {fetch: fetchImpl}).findingById('  '));
  assert.equal(error.kind, 'bad-request');
  assert.equal(calls.length, 0);
});

test('listPage will not ask for more than the endpoint allows', async () => {
  const {fetchImpl, calls} = stubFetch([{status: 200, body: page([finding()])}]);
  await new SpartaClient(testConfig(), {fetch: fetchImpl}).listPage({limit: 5000});
  assert.match(calls[0]?.url ?? '', new RegExp(`limit=${MAX_PAGE_SIZE}$`));
});

test('the findings endpoint has its own hourly budget, separate from the other endpoints', async () => {
  const {fetchImpl, calls} = stubFetch([{status: 200, body: page([finding()])}]);
  const client = new SpartaClient(
    testConfig({rateLimit: {perHour: 3_600_000, burst: 1000, maxWaitMs: 0}}),
    {fetch: fetchImpl},
  );
  await client.loadCatalog();

  assert.deepEqual(Object.keys(client.budget()), ['sparta_findings:list']);
  assert.equal(client.budget()['sparta_findings:list']?.capacity, 1000);
  assert.equal(calls.length, 1);
});

test('a 403 says the account is not on the allowlist and latches', async () => {
  const {fetchImpl, calls} = stubFetch([
    {status: 403, body: '{"title":"Forbidden","detail":"Requesting user is not enrolled."}'},
  ]);
  const client = new SpartaClient(testConfig(), {fetch: fetchImpl});

  const first = await expectError(client.loadCatalog());
  assert.equal(first.kind, 'forbidden');

  const second = await expectError(client.loadCatalog());
  assert.equal(second.kind, 'forbidden');
  assert.equal(calls.length, 1, 'the latch must stop the second sweep before the network');
});

test('extractTargets finds doc ids and operation names wherever they appear', () => {
  const traffic = `POST /api/graphql/ HTTP/1.1

fb_dtsg=x&fb_api_req_friendly_name=CometGroupsMallQuery&variables=%7B%7D&doc_id=9876543210987654`;
  assert.deepEqual(extractTargets(traffic), [
    {type: 'published_doc_id', id: '9876543210987654'},
    {type: 'endpoint_name', id: 'CometGroupsMallQuery'},
  ]);
});

test('extractTargets reads the JSON shape the mobile clients send', () => {
  const body = '{"doc_id":"1234567","fb_api_req_friendly_name":"IGProfileQuery"}';
  assert.deepEqual(extractTargets(body), [
    {type: 'published_doc_id', id: '1234567'},
    {type: 'endpoint_name', id: 'IGProfileQuery'},
  ]);
});

test('extractTargets deduplicates a target repeated across a session', () => {
  const log = 'doc_id=1234567 ... doc_id=1234567 ... ?doc_id=7654321';
  assert.deepEqual(extractTargets(log), [
    {type: 'published_doc_id', id: '1234567'},
    {type: 'published_doc_id', id: '7654321'},
  ]);
});

test('extractTargets ignores a short number that is not a document id', () => {
  assert.deepEqual(extractTargets('doc_id=42'), []);
});

test('buildPoc renders the form body www expects, placeholders intact', () => {
  const poc = buildPoc({
    id: 'BB-1',
    title: 't',
    summary: 's',
    priority: 'high',
    target: {type: 'published_doc_id', id: '9876543210987654'},
    pocDocId: '9876543210987654',
    pocVariablesJson: '{"groupID":"{{GROUP_ID}}","actorID":"{{ACTOR_ID}}"}',
    pocPlaceholdersJson: '{}',
  });

  assert.ok(poc !== undefined);
  assert.equal(poc.method, 'POST');
  assert.equal(poc.url, 'https://www.facebook.com/api/graphql/');
  assert.match(poc.body, /^fb_dtsg=\{\{fb_dtsg\}\}&variables=/);
  assert.match(poc.body, /&doc_id=9876543210987654$/);
  // The braces survive encoding, which is the whole point — they have to be findable.
  assert.match(poc.body, /\{\{GROUP_ID\}\}/);
  assert.match(poc.body, /%22groupID%22/, 'the JSON around them is still form-encoded');
  assert.deepEqual(poc.placeholders, ['fb_dtsg', 'GROUP_ID', 'ACTOR_ID']);
});

test('buildPoc falls back to the operation name when the PoC names no document id', () => {
  const poc = buildPoc({
    id: 'BB-2',
    title: 't',
    summary: 's',
    priority: 'medium',
    target: {type: 'endpoint_name', id: 'CometGroupsMallQuery'},
    pocDocId: '',
    pocVariablesJson: '',
    pocPlaceholdersJson: '',
  });

  assert.ok(poc !== undefined);
  assert.match(poc.body, /fb_api_req_friendly_name=CometGroupsMallQuery/);
  assert.doesNotMatch(poc.body, /doc_id=/);
  assert.match(poc.body, /variables=%7B%7D/, 'absent variables send an empty object');
});

test('buildPoc returns nothing when the finding names nothing to call', () => {
  const poc = buildPoc({
    id: 'BB-3',
    title: 't',
    summary: 's',
    priority: 'low',
    pocDocId: '',
    pocVariablesJson: '{}',
    pocPlaceholdersJson: '',
  });
  assert.equal(poc, undefined);
});
