/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

/**
 * Turns a disclosed SPARTA finding into the GraphQL call its proof of concept describes.
 *
 * The disclosed variables hold `{{TOKEN}}` placeholders rather than identifiers, so what
 * comes out of here reproduces nothing until the researcher substitutes values of their
 * own. That is why the tokens are left unescaped in the body and listed separately: they
 * have to be findable, both by a human reading the request and by an agent about to send
 * it. Kept in step with `SpartaPocRequest` in the Zurp Burp extension.
 */

import type {Finding} from './findings.js';

/** Every target SPARTA scans is a www GraphQL document, so there is one endpoint to call. */
export const GRAPHQL_URL = 'https://www.facebook.com/api/graphql/';

const PLACEHOLDER = /\{\{([^{}]+)\}\}/g;

export interface Poc {
  readonly method: 'POST';
  readonly url: string;
  readonly contentType: string;
  readonly body: string;
  /** Every `{{TOKEN}}` in the body, deduplicated, in the order it appears. */
  readonly placeholders: readonly string[];
  /** What the API says each placeholder wants, verbatim. Empty when it said nothing. */
  readonly placeholdersJson: string;
}

/** A persisted document id is all digits; anything else in that field is an operation name. */
function isDocId(value: string): boolean {
  return value !== '' && /^\d+$/.test(value);
}

/**
 * The PoC's own document id, falling back to the target the finding was raised against
 * when the PoC names an operation instead.
 */
function docIdOf(finding: Finding): string | undefined {
  if (isDocId(finding.pocDocId)) {
    return finding.pocDocId;
  }
  const {target} = finding;
  return target?.type === 'published_doc_id' && isDocId(target.id) ? target.id : undefined;
}

function friendlyNameOf(finding: Finding): string | undefined {
  if (finding.pocDocId !== '' && !isDocId(finding.pocDocId)) {
    return finding.pocDocId;
  }
  const {target} = finding;
  return target?.type === 'endpoint_name' && target.id !== '' ? target.id : undefined;
}

/** Form encoding, matching what www's parser expects on the other end. */
function encode(value: string): string {
  return encodeURIComponent(value).replace(/%20/g, '+');
}

/**
 * Encoded as a form value, except for the placeholder braces: the researcher has to find
 * and replace every token by hand, and www's parser takes a brace either way.
 */
function encodeVariables(variablesJson: string): string {
  const variables = variablesJson === '' ? '{}' : variablesJson;
  return encode(variables).replace(/%7B%7B/g, '{{').replace(/%7D%7D/g, '}}');
}

function placeholdersIn(body: string): string[] {
  const found = new Set<string>();
  for (const match of body.matchAll(PLACEHOLDER)) {
    if (match[1] !== undefined) {
      found.add(match[1]);
    }
  }
  return [...found];
}

/**
 * The call to make, or undefined when the finding names neither a document id nor an
 * operation and so describes nothing that can be sent.
 *
 * `fb_dtsg` is left as a placeholder like any other. Inside Burp, Zurp's CSRF plane fills
 * it at send time, which is the only moment one is fresh; outside Burp the researcher
 * supplies their own.
 */
export function buildPoc(finding: Finding): Poc | undefined {
  const docId = docIdOf(finding);
  const friendlyName = friendlyNameOf(finding);
  if (docId === undefined && friendlyName === undefined) {
    return undefined;
  }

  // Ordered as the web client sends them, so the request reads like the traffic around it.
  const parts = ['fb_dtsg={{fb_dtsg}}'];
  if (friendlyName !== undefined) {
    parts.push(`fb_api_req_friendly_name=${encode(friendlyName)}`);
  }
  parts.push(`variables=${encodeVariables(finding.pocVariablesJson)}`);
  if (docId !== undefined) {
    parts.push(`doc_id=${encode(docId)}`);
  }
  const body = parts.join('&');

  return {
    method: 'POST',
    url: GRAPHQL_URL,
    contentType: 'application/x-www-form-urlencoded',
    body,
    placeholders: placeholdersIn(body),
    placeholdersJson: finding.pocPlaceholdersJson,
  };
}
