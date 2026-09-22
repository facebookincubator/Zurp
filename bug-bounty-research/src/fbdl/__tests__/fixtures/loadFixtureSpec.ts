/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

/**
 * `fbdl_reference.json` is a real captured `/bug_bounty/fbdl_reference/` response, so the
 * validator is tested against the actual grammar rather than a hand-written stand-in.
 */

import {readFileSync} from 'node:fs';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

import {setSpec} from '../../spec.js';
import {parseFbdlReference} from '../../specParser.js';

/**
 * The fixture is data, so `tsc` leaves it in `src/` rather than copying it to `dist/`.
 * Walk up from wherever this module was loaded to the package root and read it there,
 * which works whether the tests run from source or from the build output.
 */
function locateFixture(): string {
  let dir = dirname(fileURLToPath(import.meta.url));
  for (let up = 0; up < 8; up += 1) {
    const candidate = join(dir, 'src/fbdl/__tests__/fixtures/fbdl_reference.json');
    try {
      readFileSync(candidate);
      return candidate;
    } catch {
      dir = dirname(dir);
    }
  }
  // Running directly from source: the fixture sits next to this file.
  return join(dirname(fileURLToPath(import.meta.url)), 'fbdl_reference.json');
}

export const FIXTURE_PATH = locateFixture();
export const FIXTURE_JSON: unknown = JSON.parse(readFileSync(FIXTURE_PATH, 'utf8')) as unknown;

export function installFixtureSpec(): void {
  const parsed = parseFbdlReference(FIXTURE_JSON);
  setSpec(parsed.entities, parsed.actions);
}
