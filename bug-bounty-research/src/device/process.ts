/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import {spawn} from 'node:child_process';

export interface CommandResult {
  readonly stdout: string;
  readonly stderr: string;
}

export type CommandRunner = (
  executable: string,
  args: readonly string[],
  stdin?: string,
) => Promise<CommandResult>;

const TIMEOUT_MS = 120_000;

// MCP clients pass unset variables as empty strings, which idb and adb would read as a target.
function childEnvironment(): NodeJS.ProcessEnv {
  return Object.fromEntries(
    Object.entries(process.env).filter(
      ([name, value]) => value !== '' || !/^(?:IDB_COMPANION|IDB_UDID|ANDROID_SERIAL)$/.test(name),
    ),
  );
}

export const spawnRunner: CommandRunner = async (executable, args, stdin) =>
  await new Promise<CommandResult>((resolve, reject) => {
    const child = spawn(executable, [...args], {
      stdio: ['pipe', 'pipe', 'pipe'],
      env: childEnvironment(),
      timeout: TIMEOUT_MS,
    });
    let stdout = '';
    let stderr = '';
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (chunk: string) => {
      stdout += chunk;
    });
    child.stderr.on('data', (chunk: string) => {
      stderr += chunk;
    });
    child.on('error', reject);
    child.stdin.on('error', () => undefined);
    child.on('close', (code) => {
      if (code === null) {
        reject(new Error(`${executable} did not finish within ${TIMEOUT_MS / 1000} seconds`));
      } else if (code !== 0) {
        reject(new Error(`${executable} exited ${code}: ${stderr.trim()}`));
      } else {
        resolve({stdout, stderr});
      }
    });
    child.stdin.end(stdin);
  });
