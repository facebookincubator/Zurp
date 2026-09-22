/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

/**
 * Pulls the two identifiers SPARTA keys its findings on out of captured traffic: the
 * persisted GraphQL document id, and the operation shortname www echoes back as
 * `fb_api_req_friendly_name`.
 *
 * Both travel as urlencoded form fields from the web clients, as query parameters from a
 * few GET-shaped callers, and as JSON members from the mobile clients, so each key is
 * matched wherever it appears rather than by parsing one body shape. Kept in step with
 * `SpartaTargetExtractor` in the Zurp Burp extension, which reads the same traffic.
 */

import type {SpartaTarget} from './findings.js';
import {targetKey} from './findings.js';

const DOC_ID = /(?:^|[?&"])doc_id"?\s*[=:]\s*"?(\d{5,})/g;
const FRIENDLY_NAME = /(?:^|[?&"])fb_api_req_friendly_name"?\s*[=:]\s*"?([A-Za-z0-9_]{3,128})/g;

/** Traffic longer than this is a file upload, which carries neither key. */
const MAX_SCAN_LENGTH = 256 * 1024;

/**
 * Every distinct target named in `text`, in the order it first appears. A doc id and an
 * operation name are different targets even when they name the same call, because the
 * API keys findings on whichever one SPARTA scanned.
 */
export function extractTargets(text: string): SpartaTarget[] {
  const scanned = text.length > MAX_SCAN_LENGTH ? text.slice(0, MAX_SCAN_LENGTH) : text;
  const targets: SpartaTarget[] = [];
  const seen = new Set<string>();

  const add = (target: SpartaTarget): void => {
    const key = targetKey(target);
    if (!seen.has(key)) {
      seen.add(key);
      targets.push(target);
    }
  };

  for (const match of scanned.matchAll(DOC_ID)) {
    if (match[1] !== undefined) {
      add({type: 'published_doc_id', id: match[1]});
    }
  }
  for (const match of scanned.matchAll(FRIENDLY_NAME)) {
    if (match[1] !== undefined) {
      add({type: 'endpoint_name', id: match[1]});
    }
  }
  return targets;
}
