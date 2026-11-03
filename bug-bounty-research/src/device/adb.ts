/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import {type CommandRunner, spawnRunner} from './process.js';

const PACKAGE = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/;
const ABSENT = '__whitehat_file_absent__';

// adb joins its arguments with spaces and the device shell parses them again.
function quote(word: string): string {
  return `'${word.replaceAll("'", `'\\''`)}'`;
}

export class Adb {
  readonly executable: string;
  readonly run: CommandRunner;
  readonly serial: string | undefined;
  #root: Promise<boolean> | undefined;

  constructor(
    run: CommandRunner = spawnRunner,
    serial = process.env.ANDROID_SERIAL?.trim() || undefined,
  ) {
    this.executable = process.env.BB_RESEARCH_ADB_BIN?.trim() || 'adb';
    this.run = run;
    this.serial = serial;
  }

  async readSandboxFile(packageName: string, path: string): Promise<string | undefined> {
    const {stdout} = await this.#inApp(
      packageName,
      `if [ -f ${path} ]; then cat ${path}; else echo ${ABSENT}; fi`,
    );
    return stdout.trim() === ABSENT ? undefined : stdout;
  }

  async writeSandboxFile(packageName: string, path: string, contents: string): Promise<void> {
    await this.#writeStaged(packageName, path, 'cat', contents, Buffer.byteLength(contents));
  }

  async listSandboxDirectory(packageName: string, path: string): Promise<string[]> {
    const {stdout} = await this.#inApp(packageName, `if [ -d ${path} ]; then ls ${path}; fi`);
    return stdout
      .split('\n')
      .map((entry) => entry.trim())
      .filter((entry) => entry !== '');
  }

  async readSandboxBase64(packageName: string, path: string): Promise<Buffer> {
    const {stdout} = await this.#inApp(packageName, `base64 ${path}`);
    return Buffer.from(stdout.replace(/\s/g, ''), 'base64');
  }

  async writeSandboxBase64(
    packageName: string,
    path: string,
    contents: Buffer,
    replacedSidecars: readonly string[] = [],
  ): Promise<void> {
    await this.#writeStaged(
      packageName,
      path,
      'base64 -d',
      contents.toString('base64'),
      contents.length,
      replacedSidecars,
    );
  }

  async stop(packageName: string): Promise<void> {
    this.#validate(packageName);
    await this.run(this.executable, this.#shell(['am', 'force-stop', packageName]));
  }

  async restart(packageName: string): Promise<void> {
    await this.stop(packageName);
    await this.run(
      this.executable,
      this.#shell(['monkey', '-p', packageName, '-c', 'android.intent.category.LAUNCHER', '1']),
    );
  }

  // The size check keeps a truncated transfer from replacing the original.
  async #writeStaged(
    packageName: string,
    path: string,
    decoder: string,
    payload: string,
    size: number,
    replacedSidecars: readonly string[] = [],
  ): Promise<void> {
    const staged = `${path}.whitehat`;
    const directory = path.slice(0, path.lastIndexOf('/'));
    const parents = directory
      .split('/')
      .map((_, index, parts) => parts.slice(0, index + 1).join('/'))
      .join(' ');
    const own = async (target: string): Promise<string> =>
      (await this.#isRoot())
        ? ` && chown $(stat -c %u:%g .) ${target} && chcon $(stat -c %C .) ${target}`
        : '';
    const steps = [
      `mkdir -p ${directory}${await own(parents)}`,
      `${decoder} > ${staged}`,
      `[ $(wc -c < ${staged}) -eq ${size} ]`,
      `chmod 660 ${staged}${await own(staged)}`,
      `mv ${staged} ${path}`,
      ...(replacedSidecars.length === 0 ? [] : [`rm -f ${replacedSidecars.join(' ')}`]),
    ];
    await this.#inApp(
      packageName,
      `${steps.join(' && ')} || { rm -f ${staged}; exit 1; }`,
      payload,
    );
  }

  async #inApp(packageName: string, script: string, stdin?: string): Promise<{stdout: string}> {
    this.#validate(packageName);
    if (await this.#isRoot()) {
      const words = ['sh', '-c', `cd /data/data/${packageName} || exit 1; umask 007; ${script}`];
      return await this.run(this.executable, this.#shell(words), stdin);
    }
    try {
      return await this.run(
        this.executable,
        this.#shell(['run-as', packageName, 'sh', '-c', script]),
        stdin,
      );
    } catch (error) {
      if (!(error instanceof Error) || !error.message.includes('not debuggable')) throw error;
      throw new Error(`${error.message.trim()}; run \`adb root\` first or use a debuggable build`);
    }
  }

  async #isRoot(): Promise<boolean> {
    this.#root ??= this.run(this.executable, this.#shell(['id', '-u'])).then(
      ({stdout}) => stdout.trim() === '0',
    );
    return await this.#root;
  }

  #shell(words: readonly string[]): string[] {
    return this.#args(['shell', words.map(quote).join(' ')]);
  }
  #args(args: string[]): string[] {
    return this.serial === undefined ? args : ['-s', this.serial, ...args];
  }
  #validate(value: string): void {
    if (!PACKAGE.test(value)) throw new Error(`Invalid Android package: ${value}`);
  }
}
