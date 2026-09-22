#!/usr/bin/env node
/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import type {McpServer} from '@modelcontextprotocol/sdk/server/mcp.js';

import {runIfEntrypoint} from '../core/bootstrap.js';
import type {ConfigSpec, CoreConfig} from '../core/config.js';
import type {Logger} from '../core/log.js';
import {FbdlApi, SERVER_NAME} from './api.js';
import {buildServer} from './server.js';
import {loadSpec, logSpecLoadResult} from './specLoader.js';

export {buildServer, SERVER_NAME, type ServerDeps} from './server.js';
export {FbdlApi} from './api.js';
export {loadSpec, logSpecLoadResult} from './specLoader.js';
export {validate} from './validator.js';
export {parseFbdlReference} from './specParser.js';
export {setSpec, clearSpec, getSpec} from './spec.js';
export {tryAcquireCreateSlot, resetRunGuard} from './runGuard.js';
export type {Action, Param, SetupEntity} from './schema.js';

/**
 * `FBDL_API_TOKEN` is the variable the FBDL API documentation tells researchers to set,
 * so it comes first; the same token also drives the Meta Context server.
 */
export const CONFIG_SPEC: ConfigSpec = {
  envPrefix: 'FBDL',
  tokenVars: ['FBDL_API_TOKEN', 'ZURP_ACCESS_TOKEN'],
};

/**
 * The language tools need the spec, so it is fetched before serving. A failure is
 * carried into the server rather than thrown: without it the run-management tools still
 * work, and "spec unavailable, here is why" beats a server that refuses to start.
 */
export async function build(config: CoreConfig, logger: Logger): Promise<McpServer> {
  const api = new FbdlApi(config, {logger});
  try {
    logSpecLoadResult(logger, await loadSpec(api));
    return buildServer(config, {api, logger});
  } catch (error) {
    const specError = error instanceof Error ? error : new Error(String(error));
    logger.error(`FBDL language tools are unavailable: ${specError.message}`);
    return buildServer(config, {api, logger, specError});
  }
}

runIfEntrypoint(import.meta.url, {serverName: SERVER_NAME, spec: CONFIG_SPEC, build});
