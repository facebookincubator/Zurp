/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import assert from 'node:assert/strict';
import {test} from 'node:test';

import {ApiError} from '../../core/errors.js';
import {formatBatch, formatResolution, formatScan} from '../format.js';
import {extractCandidates} from '../identifiers.js';

test('a single resolution lists each asset and the vanity when there is one', () => {
  const text = formatResolution({
    query: '100064123456789',
    assets: [
      {type: 'ent_or_node', name: 'EntGroupMall'},
      {type: 'xcontroller', name: 'FooAjaxController'},
    ],
    objectName: 'some.page',
  });
  assert.match(text, /^100064123456789$/m);
  assert.match(text, /ent_or_node\s+EntGroupMall/);
  assert.match(text, /xcontroller\s+FooAjaxController/);
  assert.match(text, /vanity: some\.page/);
});

test('nothing matched says so instead of looking like a failure', () => {
  const text = formatResolution({query: '100064123456789', assets: []});
  assert.match(text, /no assets/);
  assert.doesNotMatch(text, /vanity/);
});

test('a batch summarises and lists only the identifiers that named something', () => {
  const text = formatBatch(
    {
      resolutions: [
        {query: 'a', assets: [{type: 'graphql', name: 'GroupsMallQuery'}]},
        {query: 'b', assets: []},
        {query: 'c', assets: []},
      ],
    },
    3,
  );
  assert.match(text, /Resolved 3 of 3 identifiers: 1 named something, 2 matched nothing\./);
  assert.match(text, /graphql\s+GroupsMallQuery/);
  assert.doesNotMatch(text, /^b$/m);
});

test('a partial batch reports what stopped it without hiding the results', () => {
  const text = formatBatch(
    {
      resolutions: [{query: 'a', assets: [{type: 'bloks', name: 'X'}]}],
      failure: new ApiError('rate-limited', 'Hourly request budget spent.', 429),
    },
    250,
  );
  assert.match(text, /Resolved 1 of 250/);
  assert.match(text, /bloks\s+X/);
  assert.match(text, /Stopped early — rate-limited/);
});

test('a scan reports the candidate breakdown and tags each hit with its kind', () => {
  const candidates = extractCandidates(
    'doc_id=9876543210987654 https://www.facebook.com/ajax/foo/ 100064123456789',
  );
  const text = formatScan(
    candidates,
    {
      resolutions: [
        {query: 'doc_id=9876543210987654', assets: [{type: 'graphql', name: 'FooQuery'}]},
        {query: 'https://www.facebook.com/ajax/foo/', assets: []},
        {query: '100064123456789', assets: []},
      ],
    },
    false,
  );
  assert.match(text, /Extracted 3 candidates/);
  assert.match(text, /1 doc_id/);
  assert.match(text, /1 named something, 2 matched nothing/);
  assert.match(text, /doc_id=9876543210987654 {2}\[doc_id\]/);
  assert.match(text, /graphql\s+FooQuery/);
});

test('a scan that found nothing explains what the endpoint recognises', () => {
  const text = formatScan([], {resolutions: []}, false);
  assert.match(text, /No candidate identifiers found/);
  assert.match(text, /doc_id=<id>/);
});

test('a capped scan says so, so a truncated answer is not read as the whole picture', () => {
  const candidates = extractCandidates('100064123456781 100064123456782', {limit: 2});
  const text = formatScan(candidates, {resolutions: []}, true);
  assert.match(text, /capped at the limit/);
});
