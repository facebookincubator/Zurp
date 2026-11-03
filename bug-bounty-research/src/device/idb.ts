/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import {type CommandRunner, spawnRunner} from './process.js';

export interface IdbOptions {
  readonly executable?: string;
  readonly run?: CommandRunner;
}

export interface PreferenceTarget {
  readonly bundleId: string;
  readonly udid?: string;
}

export class Idb {
  readonly executable: string;
  readonly run: CommandRunner;

  constructor(options: IdbOptions = {}) {
    this.executable = options.executable ?? (process.env.BB_RESEARCH_IDB_BIN?.trim() || 'idb');
    this.run = options.run ?? spawnRunner;
  }

  async setPreference(
    target: PreferenceTarget,
    name: string,
    type: 'bool' | 'float' | 'string',
    value: string,
    reason: string,
  ): Promise<void> {
    await this.run(
      this.executable,
      this.#targetArgs('set', target, ['--type', type, '--json', '--reason', reason, name, value]),
    );
  }

  async terminate(bundleId: string, udid: string | undefined): Promise<void> {
    try {
      await this.run(
        this.executable,
        this.#udidArgs(['terminate'], udid, [
          '--reason',
          'restart after configuring whitehat inspection',
          bundleId,
        ]),
      );
    } catch {
      // The app may already be stopped. Launch below is the authoritative step.
    }
  }

  async launch(bundleId: string, udid: string | undefined): Promise<void> {
    await this.run(
      this.executable,
      this.#udidArgs(['launch'], udid, [
        '--reason',
        'apply whitehat inspection configuration',
        bundleId,
      ]),
    );
  }

  async groupList(path: string, udid: string | undefined): Promise<string[]> {
    const result = await this.run(
      this.executable,
      this.#udidArgs(['file', 'ls'], udid, [
        '--group',
        '--reason',
        'find existing MobileConfig overrides',
        path,
      ]),
    );
    return result.stdout
      .split('\n')
      .map((entry) => entry.trim())
      .filter((entry) => entry !== '');
  }

  async groupRead(path: string, udid: string | undefined): Promise<string> {
    const result = await this.run(
      this.executable,
      this.#udidArgs(['file', 'read'], udid, [
        '--group',
        '--reason',
        'preserve existing MobileConfig overrides',
        path,
      ]),
    );
    return result.stdout;
  }

  async groupWrite(path: string, contents: string, udid: string | undefined): Promise<void> {
    await this.run(
      this.executable,
      this.#udidArgs(['file', 'write'], udid, [
        '--group',
        '--reason',
        'override the whitehat settings MobileConfig',
        path,
      ]),
      contents,
    );
  }

  #targetArgs(command: 'set', target: PreferenceTarget, tail: string[]): string[] {
    return this.#udidArgs([command, '--domain', target.bundleId], target.udid, tail);
  }

  #udidArgs(head: string[], udid: string | undefined, tail: string[]): string[] {
    return udid === undefined ? [...head, ...tail] : [...head, '--udid', udid, ...tail];
  }
}
