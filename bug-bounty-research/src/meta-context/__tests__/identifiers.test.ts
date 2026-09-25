/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import assert from 'node:assert/strict';
import {test} from 'node:test';

import {extractCandidates} from '../identifiers.js';

function queries(text: string, limit?: number): string[] {
  return extractCandidates(text, limit !== undefined ? {limit} : {}).map((c) => c.query);
}

test('takes object ids from 15 to 18 digits and nothing outside that', () => {
  const text = [
    '100064123456789', // 15
    '178414000000000000', // 18, the Instagram media range
    '1759412345', // 10, unix seconds
    '1759412345678', // 13, milliseconds
    '1759412345678901234', // 19, nanoseconds
  ].join(' ');
  assert.deepEqual(queries(text), ['100064123456789', '178414000000000000']);
});

test('keeps the doc_id prefix, since a bare number reads as an object id', () => {
  assert.deepEqual(queries('POST /graphql doc_id=9876543210987654'), [
    'doc_id=9876543210987654',
  ]);
  assert.deepEqual(queries('see doc_id: 9876543210987654'), ['doc_id=9876543210987654']);
});

test('does not also scrape a doc id as a bare object id', () => {
  const found = queries('doc_id=9876543210987654');
  assert.deepEqual(found, ['doc_id=9876543210987654']);
  assert.ok(!found.includes('9876543210987654'));
});

test('an object id next to a doc id still survives', () => {
  const found = queries('doc_id=9876543210987654 owner=100064123456789');
  assert.deepEqual(found, ['doc_id=9876543210987654', '100064123456789']);
});

test('takes URLs on domains the resolver can map to code', () => {
  const text = [
    'https://www.facebook.com/ajax/foo/?id=1',
    'https://graph.facebook.com/v20.0/me',
    'https://www.instagram.com/api/v1/users/',
    'https://www.threads.com/t/abc',
    'https://about.meta.com/company/',
  ].join('\n');
  assert.deepEqual(queries(text), [
    'https://www.facebook.com/ajax/foo/?id=1',
    'https://graph.facebook.com/v20.0/me',
    'https://www.instagram.com/api/v1/users/',
    'https://www.threads.com/t/abc',
    'https://about.meta.com/company/',
  ]);
});

test('skips domains the resolver cannot map, including lookalikes', () => {
  const text = [
    'https://example.com/facebook.com/x',
    'https://notfacebook.com/a',
    'https://web.whatsapp.com/b',
  ].join('\n');
  assert.deepEqual(queries(text), []);
});

test('drops the endpoints every page load hits', () => {
  const text = [
    'https://www.facebook.com/ajax/bootloader-endpoint/?a=1',
    'https://www.facebook.com/ajax/bulk-route-definitions/',
    'https://www.facebook.com/ajax/qm/?x=1',
    'https://edge-chat.facebook.com/chat?sticky=1',
    'https://www.facebook.com/ajax/keepalive/',
  ].join('\n');
  assert.deepEqual(queries(text), ['https://www.facebook.com/ajax/keepalive/']);
});

test('trims the sentence off a trailing URL', () => {
  assert.deepEqual(queries('Reproduced at https://www.facebook.com/groups/123/.'), [
    'https://www.facebook.com/groups/123/',
  ]);
});

test('takes GraphQL operation names only when they end in Query or Mutation', () => {
  const text = 'CometGroupsMallQuery and ProfileFollowMutation, but not GroupsHandler or Query';
  assert.deepEqual(queries(text), ['CometGroupsMallQuery', 'ProfileFollowMutation']);
});

test('takes bloks ids and strips trailing punctuation', () => {
  assert.deepEqual(queries('id com.bloks.www.bloks.caa.login.async, next'), [
    'com.bloks.www.bloks.caa.login.async',
  ]);
});

test('deduplicates and keeps first-seen order', () => {
  const text = '100064123456789 100064123456789 https://www.facebook.com/a 100064123456789';
  assert.deepEqual(queries(text), ['https://www.facebook.com/a', '100064123456789']);
});

test('honours the limit', () => {
  const text = '100064123456781 100064123456782 100064123456783';
  assert.equal(queries(text, 2).length, 2);
});

test('finds nothing in text that carries nothing', () => {
  assert.deepEqual(queries('The page just returned a 500 with no body.'), []);
});

test('pulls identifiers out of a raw request/response pair', () => {
  const text = `POST /api/graphql/ HTTP/2
Host: www.facebook.com
Content-Type: application/x-www-form-urlencoded

av=100064123456789&doc_id=9876543210987654&fb_api_req_friendly_name=CometGroupsMallQuery

HTTP/2 200
{"data":{"group":{"id":"178414000000000000"}}}`;
  assert.deepEqual(queries(text), [
    'doc_id=9876543210987654',
    '100064123456789',
    '178414000000000000',
    'CometGroupsMallQuery',
  ]);
});
