#!/usr/bin/env node
/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import {runIfEntrypoint} from '../core/bootstrap.js';
import type {ConfigSpec} from '../core/config.js';
import {buildServer, SERVER_NAME} from './server.js';

export {buildServer, SERVER_NAME, type ServerDeps} from './server.js';
export {
  type Catalog,
  type Finding,
  MAX_PAGE_SIZE,
  parseFinding,
  SpartaClient,
  type SpartaTarget,
  TARGET_TYPES,
  type TargetType,
  targetKey,
} from './findings.js';
export {buildPoc, GRAPHQL_URL, type Poc} from './poc.js';
export {extractTargets} from './targets.js';
export {formatCatalog, formatFinding, formatPoc, formatScan, type TargetHit} from './format.js';

/**
 * `ZURP_ACCESS_TOKEN` is listed first for the same reason the Meta Context server does it:
 * this endpoint is gated on the same researcher allowlist and driven by the same token, so
 * a researcher who has already configured Zurp has already configured this.
 */
export const CONFIG_SPEC: ConfigSpec = {
  envPrefix: 'SPARTA',
  tokenVars: ['ZURP_ACCESS_TOKEN', 'FBDL_API_TOKEN'],
};

runIfEntrypoint(import.meta.url, {
  serverName: SERVER_NAME,
  spec: CONFIG_SPEC,
  build: (config, logger) => buildServer(config, {logger}),
});
