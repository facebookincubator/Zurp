/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import {mkdtemp, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';

import {type CommandRunner, spawnRunner} from './process.js';

// idb has no keychain command, so the simulator trust store goes through simctl. simctl only
// reaches simulators on this machine, unlike idb with a remote companion.
export class Simctl {
  readonly run: CommandRunner;

  constructor(run: CommandRunner = spawnRunner) {
    this.run = run;
  }

  // Returns false, changing nothing, when idb targets a remote companion.
  async addRootCertificate(der: Buffer, udid: string | undefined): Promise<boolean> {
    if (process.env.IDB_COMPANION?.trim()) return false;
    const directory = await mkdtemp(join(tmpdir(), 'zurp-ca-'));
    try {
      const path = join(directory, 'ProxyCACert.der');
      await writeFile(path, der);
      await this.run('xcrun', [
        'simctl',
        'keychain',
        udid ?? (process.env.IDB_UDID?.trim() || 'booted'),
        'add-root-cert',
        path,
      ]);
      return true;
    } finally {
      await rm(directory, {recursive: true, force: true});
    }
  }
}
