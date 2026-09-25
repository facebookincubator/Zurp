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
export {AssetClient, MAX_BATCH_QUERIES} from './assets.js';
export type {Asset, AssetType, BatchOutcome, Resolution} from './assets.js';
export {extractCandidates} from './identifiers.js';
export {formatBatch, formatResolution, formatScan} from './format.js';

/**
 * `ZURP_ACCESS_TOKEN` is listed first so the Burp extension's own variable configures
 * this server too — a researcher who has set up Zurp has already set up this.
 */
export const CONFIG_SPEC: ConfigSpec = {
  envPrefix: 'ZURP',
  tokenVars: ['ZURP_ACCESS_TOKEN', 'FBDL_API_TOKEN'],
};

runIfEntrypoint(import.meta.url, {
  serverName: SERVER_NAME,
  spec: CONFIG_SPEC,
  build: (config, logger) => buildServer(config, {logger}),
});
