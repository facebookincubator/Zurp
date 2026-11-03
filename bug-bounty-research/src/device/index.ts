#!/usr/bin/env node
/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import {StdioServerTransport} from '@modelcontextprotocol/sdk/server/stdio.js';

import {buildServer, SERVER_NAME} from './server.js';

export {Adb} from './adb.js';
export {Idb} from './idb.js';
export {type CommandResult, type CommandRunner} from './process.js';
export {buildServer, SERVER_NAME, type ServerDeps} from './server.js';

async function main(): Promise<void> {
  await buildServer().connect(new StdioServerTransport());
}

if (process.argv[1] !== undefined && import.meta.url === `file://${process.argv[1]}`) {
  main().catch((error: unknown) => {
    process.stderr.write(
      `${SERVER_NAME} failed to start: ${error instanceof Error ? error.stack : String(error)}\n`,
    );
    process.exitCode = 1;
  });
}
