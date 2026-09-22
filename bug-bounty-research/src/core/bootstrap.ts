/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

/**
 * The startup sequence both servers share: check the runtime, read config, settle proxy
 * and TLS, then serve over stdio. Keeping it in one place is what stops the two binaries
 * from drifting into two different sets of environment variables.
 */

import type {McpServer} from '@modelcontextprotocol/sdk/server/mcp.js';
import {StdioServerTransport} from '@modelcontextprotocol/sdk/server/stdio.js';

import {type ConfigSpec, type CoreConfig, loadCoreConfig, SHARED_TOKEN_VAR} from './config.js';
import {configureNetwork} from './http.js';
import {createLogger, type Logger} from './log.js';

export interface BootstrapOptions {
  readonly serverName: string;
  readonly spec: ConfigSpec;
  /** Async because a server may need to fetch something before it can answer. */
  readonly build: (config: CoreConfig, logger: Logger) => McpServer | Promise<McpServer>;
}

/** Set by undici, which is what carries proxy support. Keep in step with `engines`. */
const MINIMUM_NODE_MAJOR = 22;

export async function serveOverStdio(options: BootstrapOptions): Promise<void> {
  const {serverName, spec, build} = options;

  // Unlike a missing token, this cannot be reported per call: on too old a Node the
  // handshake and tools/list both succeed and only the calls fail, with an error naming
  // `fetch` rather than the Node version that is actually wrong.
  const major = Number.parseInt(process.versions.node.split('.')[0] ?? '0', 10);
  if (typeof globalThis.fetch !== 'function' || major < MINIMUM_NODE_MAJOR) {
    throw new Error(
      `Node ${process.versions.node} is too old. This server needs Node ` +
        `${MINIMUM_NODE_MAJOR}.19.0 or newer — point your MCP client at a newer node binary.`,
    );
  }

  const config = loadCoreConfig(spec);
  const logger = createLogger({
    level: config.logLevel,
    name: serverName,
    secrets: [config.accessToken],
  });

  // Proxy and TLS are process-wide, so they are settled before the first call rather
  // than negotiated per request.
  configureNetwork({
    baseUrl: config.baseUrl,
    insecureTls: config.insecureTls,
    timeoutMs: config.timeoutMs,
    logger,
  });

  if (config.accessToken === '') {
    // Reported per call rather than fatally: an MCP server that exits during handshake
    // surfaces as "server failed to start", which says nothing about what to fix.
    logger.warn(
      `No token configured. Set ${spec.tokenVars[0] ?? SHARED_TOKEN_VAR} or ` +
        `${SHARED_TOKEN_VAR}; every call will fail until you do.`,
    );
  }

  logger.info(
    `Ready. Endpoint ${config.baseUrl}, budget ${config.rateLimit.perHour}/hour, ` +
      `${config.retry.attempts} attempts per request, ${config.maxConcurrency} in flight.`,
  );

  const server = await build(config, logger);
  await server.connect(new StdioServerTransport());
}

/**
 * Runs the server when this module is the process entrypoint. `moduleUrl` is the
 * caller's `import.meta.url`; importing the module for its exports must not start it.
 */
export function runIfEntrypoint(moduleUrl: string, options: BootstrapOptions): void {
  if (process.argv[1] === undefined || moduleUrl !== `file://${process.argv[1]}`) {
    return;
  }
  serveOverStdio(options).catch((error: unknown) => {
    // stdout carries the JSON-RPC framing, so anything we say has to go to stderr.
    process.stderr.write(
      `${options.serverName} failed to start: ${
        error instanceof Error ? error.stack : String(error)
      }\n`,
    );
    process.exitCode = 1;
  });
}
