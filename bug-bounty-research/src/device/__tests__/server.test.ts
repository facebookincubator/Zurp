/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import assert from 'node:assert/strict';
import {test} from 'node:test';
import {Client} from '@modelcontextprotocol/sdk/client/index.js';
import {InMemoryTransport} from '@modelcontextprotocol/sdk/inMemory.js';
import {existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {DatabaseSync} from 'node:sqlite';
import {type CommandRunner, spawnRunner} from '../process.js';
import {mergeOverrides} from '../mobileconfig.js';
import {buildServer} from '../server.js';

interface Call {
  readonly executable: string;
  readonly args: readonly string[];
  readonly stdin: string | undefined;
  readonly words: readonly string[];
}
interface StagedWrite {
  readonly path: string;
  readonly decoder: string;
  readonly size: number;
  readonly sidecars: readonly string[];
  readonly owned: readonly string[];
}

const MC = 'files/mobileconfig/mc_overrides.json';
const PREFS_DB_PATH = 'databases/prefs_db';
const DEVPREFS = 'shared_prefs/devprefs.xml';

// adb joins its arguments and the device shell parses them again; this applies that parse and rejects unquoted metacharacters.
function deviceWords(args: readonly string[]): string[] {
  const rest = args[0] === '-s' ? args.slice(2) : args;
  assert.equal(rest[0], 'shell', `unexpected adb service: ${JSON.stringify(args)}`);
  assert.equal(
    rest.length,
    2,
    `adb shell must receive one pre-quoted command: ${JSON.stringify(args)}`,
  );
  const command = rest[1] ?? '';
  const words: string[] = [];
  let word: string | undefined;
  for (let index = 0; index < command.length; index++) {
    const character = command[index] ?? '';
    if (character === "'") {
      const close = command.indexOf("'", index + 1);
      assert.notEqual(close, -1, `unterminated quote in ${command}`);
      word = (word ?? '') + command.slice(index + 1, close);
      index = close;
    } else if (character === '\\') {
      word = (word ?? '') + (command[index + 1] ?? '');
      index++;
    } else if (character === ' ') {
      if (word !== undefined) words.push(word);
      word = undefined;
    } else {
      assert.match(
        character,
        /[A-Za-z0-9_./-]/,
        `unquoted ${character} reaches the device shell in ${command}`,
      );
      word = (word ?? '') + character;
    }
  }
  if (word !== undefined) words.push(word);
  return words;
}

// The script run inside the app's data directory, through run-as or a root shell.
function appScript(words: readonly string[]): string {
  if (words[0] === 'run-as' && words[2] === 'sh' && words[3] === '-c') return words[4] ?? '';
  if (words[0] === 'sh' && words[1] === '-c')
    return (words[2] ?? '').replace(/^cd \/data\/data\/[\w.]+ \|\| exit 1; umask 007; /, '');
  return '';
}

function stagedWrite(words: readonly string[]): StagedWrite | undefined {
  const own = (name: string, target: string): string =>
    `(?: && chown \\$\\(stat -c %u:%g \\.\\) (?<${name}>${target}) && chcon \\$\\(stat -c %C \\.\\) \\k<${name}>)?`;
  const pattern = new RegExp(
    `^mkdir -p (?<directory>\\S+)${own('parents', '[^&]+?')} && (?<decoder>cat|base64 -d) > (?<path>\\S+)\\.whitehat && \\[ \\$\\(wc -c < \\k<path>\\.whitehat\\) -eq (?<size>\\d+) \\] && chmod 660 \\k<path>\\.whitehat${own('staged', '\\k<path>\\.whitehat')} && mv \\k<path>\\.whitehat \\k<path>(?: && rm -f (?<sidecars>[^|]+))? \\|\\| \\{ rm -f \\k<path>\\.whitehat; exit 1; \\}$`,
  );
  const groups = pattern.exec(appScript(words))?.groups;
  if (groups === undefined) return undefined;
  const owned = [
    ...(groups.parents?.split(' ') ?? []),
    ...(groups.staged === undefined ? [] : [groups.staged]),
  ];
  return {
    decoder: groups.decoder ?? '',
    path: groups.path ?? '',
    size: Number(groups.size),
    sidecars: groups.sidecars?.split(' ') ?? [],
    owned,
  };
}
function writesTo(call: Call, path: string): boolean {
  return stagedWrite(call.words)?.path === path;
}

function emptyPrefsDb(): string {
  const directory = mkdtempSync(join(tmpdir(), 'zurp-test-prefs-'));
  const path = join(directory, 'prefs_db');
  const database = new DatabaseSync(path);
  database.exec('CREATE TABLE preferences(key TEXT PRIMARY KEY, type INTEGER, value TEXT)');
  database.close();
  const encoded = readFileSync(path).toString('base64');
  rmSync(directory, {recursive: true, force: true});
  return encoded;
}
const PREFS_DB = emptyPrefsDb();
function walPrefsDb(): Record<string, string> {
  const directory = mkdtempSync(join(tmpdir(), 'zurp-test-wal-'));
  const path = join(directory, 'prefs_db');
  const database = new DatabaseSync(path);
  database.exec(
    'PRAGMA journal_mode=WAL; CREATE TABLE preferences(key TEXT PRIMARY KEY, type INTEGER, value TEXT); PRAGMA wal_checkpoint(TRUNCATE);',
  );
  database.exec("INSERT INTO preferences(key,type,value) VALUES('/unrelated',1,'preserve-me')");
  const files = Object.fromEntries(
    ['prefs_db', 'prefs_db-wal', 'prefs_db-shm'].map((name) => [
      name,
      readFileSync(join(directory, name)).toString('base64'),
    ]),
  );
  database.close();
  rmSync(directory, {recursive: true, force: true});
  return files;
}
function readWrittenPrefs(stdin: string, query: string): unknown[] {
  const directory = mkdtempSync(join(tmpdir(), 'zurp-test-written-'));
  const path = join(directory, 'prefs_db');
  writeFileSync(path, Buffer.from(stdin, 'base64'));
  const database = new DatabaseSync(path, {readOnly: true});
  const rows = database.prepare(query).all();
  database.close();
  rmSync(directory, {recursive: true, force: true});
  return rows;
}

interface ConnectOptions {
  readonly failIosList?: boolean;
  readonly iosOverrides?: string | null;
  readonly androidOverrides?: string | null;
  readonly devprefs?: string;
  readonly prefsFiles?: Record<string, string>;
  readonly failPrefsFile?: string;
  readonly root?: boolean;
  readonly notDebuggable?: boolean;
  readonly failRelaunch?: boolean;
  readonly iosGroupRoot?: string;
}

async function connect(options: ConnectOptions = {}): Promise<{client: Client; calls: Call[]}> {
  const calls: Call[] = [];
  process.env.BB_RESEARCH_ADB_BIN = 'adb-test';
  const iosOverrides =
    options.iosOverrides === undefined ? '{"_qe_overrides_":["kept"]}' : options.iosOverrides;
  const androidOverrides = options.androidOverrides === undefined ? '{}' : options.androidOverrides;
  const prefsFiles = options.prefsFiles ?? {prefs_db: PREFS_DB};
  const run: CommandRunner = async (executable, args, stdin) => {
    const words = executable === 'adb-test' ? deviceWords(args) : [];
    calls.push({executable, args, stdin, words});
    if (args[0] === 'file' && args[1] === 'ls') {
      if (options.failIosList === true) throw new Error('simulated MC read failure');
      if (options.iosGroupRoot !== undefined && args.at(-1) === 'group.com.burbn.instagram')
        return {stdout: options.iosGroupRoot, stderr: ''};
      return {
        stdout: `mobileconfig\n${iosOverrides === null ? '' : 'mc_overrides.json\n'}12345.data\n`,
        stderr: '',
      };
    }
    if (args[0] === 'file' && args[1] === 'read') return {stdout: iosOverrides ?? '', stderr: ''};
    if (executable !== 'adb-test') return {stdout: '', stderr: ''};
    if (words.join(' ') === 'id -u')
      return {stdout: options.root === true ? '0\n' : '2000\n', stderr: ''};
    if (options.notDebuggable === true && words[0] === 'run-as')
      throw new Error(`adb-test exited 1: run-as: package not debuggable: ${words[1]}`);
    if (options.failRelaunch === true && words[0] === 'monkey')
      throw new Error('simulated relaunch failure');
    const staged = stagedWrite(words);
    if (staged !== undefined) {
      const written =
        staged.decoder === 'cat'
          ? Buffer.byteLength(stdin ?? '')
          : Buffer.from(stdin ?? '', 'base64').length;
      assert.equal(written, staged.size, `size check for ${staged.path}`);
      return {stdout: '', stderr: ''};
    }
    const script = appScript(words);
    const absent = `${/echo (\S+); fi$/.exec(script)?.[1]}\n`;
    if (script.startsWith(`if [ -f ${DEVPREFS} ]`))
      return {stdout: options.devprefs ?? '<map>\n</map>\n', stderr: ''};
    if (script.startsWith(`if [ -f ${MC} ]`))
      return {stdout: androidOverrides ?? absent, stderr: ''};
    if (script === 'if [ -d databases ]; then ls databases; fi')
      return {stdout: `${Object.keys(prefsFiles).join('\n')}\n`, stderr: ''};
    if (/^base64 \S+$/.test(script)) {
      const name = script.split('/').at(-1) ?? '';
      if (name === options.failPrefsFile) throw new Error('simulated transfer failure');
      const encoded = prefsFiles[name];
      if (encoded !== undefined) return {stdout: encoded, stderr: ''};
      throw new Error('unexpected absent file read');
    }
    return {stdout: '', stderr: ''};
  };
  const server = buildServer({
    idbExecutable: 'idb-test',
    run,
    now: () => 1_000_000,
    androidSerial: 'emu-1',
  });
  const client = new Client({name: 'test', version: '0'});
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  await Promise.all([server.connect(serverTransport), client.connect(clientTransport)]);
  return {client, calls};
}

test('the server advertises one cross-platform whitehat tool', async () => {
  const {client} = await connect();
  assert.deepEqual(
    (await client.listTools()).tools.map((tool) => tool.name),
    ['whitehat_configure'],
  );
  await client.close();
});

test('Threads iOS overrides the MC gate in its own session directory, scopes the request, and restarts', async () => {
  const {client, calls} = await connect();
  const result = await client.callTool({
    name: 'whitehat_configure',
    arguments: {
      platform: 'ios',
      app: 'threads',
      enabled: true,
      account_id: '12345',
      device_id: 'sim-1',
      duration_seconds: 3600,
    },
  });
  assert.notEqual(result.isError, true);
  const write = calls.find((call) => call.args[0] === 'file' && call.args[1] === 'write');
  assert.ok(
    write?.args.includes(
      'group.com.burbn.instagram/barcelona/Documents/mobileconfig/12345.data/mc_overrides.json',
    ),
  );
  assert.match(write?.stdin ?? '', /120291:whitehat_settings_ios_ig/);
  assert.match(write?.stdin ?? '', /0: show_settings: true/);
  assert.deepEqual(
    calls.filter((call) => call.args[0] === 'set').map((call) => call.args.slice(-2)),
    [
      ['FBWhitehatSSLPinningBypassExpiry', '4600'],
      ['FBWhitehatSSLPinningBypassOwner', '12345'],
      ['FBWhitehatSSLPinningBypass', 'true'],
    ],
  );
  assert.equal(calls[0]?.args[0], 'terminate');
  assert.equal(calls.at(-1)?.args[0], 'launch');
  assert.ok(calls.every((call) => call.args.join(' ').includes('--udid sim-1')));
  await client.close();
});

test('Facebook iOS applies both sessionless and sessioned visibility gates with no expiry or owner', async () => {
  const {client, calls} = await connect();
  const result = await client.callTool({
    name: 'whitehat_configure',
    arguments: {platform: 'ios', app: 'facebook', enabled: true},
  });
  assert.notEqual(result.isError, true);
  const write = calls.find((call) => call.args[0] === 'file' && call.args[1] === 'write');
  assert.match(write?.stdin ?? '', /114381:whitehat_settings_ios/);
  assert.match(write?.stdin ?? '', /121208:whitehat_sessioned_settings_fb_ios/);
  assert.deepEqual(
    calls.filter((call) => call.args[0] === 'set').map((call) => call.args.at(-2)),
    ['FBWhitehatSSLPinningBypass'],
  );
  await client.close();
});

test('Messenger iOS applies the sessioned visibility gate in its own app group', async () => {
  const {client, calls} = await connect();
  const result = await client.callTool({
    name: 'whitehat_configure',
    arguments: {platform: 'ios', app: 'messenger', enabled: true},
  });
  assert.notEqual(result.isError, true);
  const write = calls.find((call) => call.args[0] === 'file' && call.args[1] === 'write');
  assert.ok(write?.args.some((arg) => arg.startsWith('group.com.facebook.Messenger/')));
  assert.deepEqual(Object.keys(JSON.parse(write?.stdin ?? '{}')).sort(), [
    '121208:whitehat_sessioned_settings_fb_ios',
    '_qe_overrides_',
  ]);
  assert.equal(calls.at(-1)?.args.at(-1), 'com.facebook.Messenger');
  await client.close();
});

test('a failed iOS enable relaunches the app it stopped', async () => {
  const {client, calls} = await connect({failIosList: true});
  const result = await client.callTool({
    name: 'whitehat_configure',
    arguments: {platform: 'ios', app: 'instagram', enabled: true, account_id: '12345'},
  });
  assert.equal(result.isError, true);
  assert.match(JSON.stringify(result.content), /simulated MC read failure/);
  assert.deepEqual(
    calls.map((call) => call.args[0]),
    ['terminate', 'file', 'launch'],
  );
  await client.close();
});

test('Instagram and Threads iOS refuse to enable without account_id before stopping the app', async () => {
  for (const app of ['instagram', 'threads']) {
    const {client, calls} = await connect();
    const result = await client.callTool({
      name: 'whitehat_configure',
      arguments: {platform: 'ios', app, enabled: true},
    });
    assert.equal(result.isError, true);
    assert.match(JSON.stringify(result.content), /requires account_id/);
    assert.equal(calls.length, 0);
    await client.close();
  }
});

test('iOS rejects an app_id that is not a bundle id', async () => {
  const {client, calls} = await connect();
  const result = await client.callTool({
    name: 'whitehat_configure',
    arguments: {platform: 'ios', app: 'facebook', enabled: true, app_id: '../escape'},
  });
  assert.equal(result.isError, true);
  const flag = await client.callTool({
    name: 'whitehat_configure',
    arguments: {platform: 'ios', app: 'facebook', enabled: true, app_id: '--udid.other'},
  });
  assert.equal(flag.isError, true);
  assert.equal(calls.length, 0);
  await client.close();
});

test('iOS app_id replaces the bundle id and keeps the app group', async () => {
  const {client, calls} = await connect();
  const result = await client.callTool({
    name: 'whitehat_configure',
    arguments: {platform: 'ios', app: 'facebook', enabled: true, app_id: 'com.example.Facebook'},
  });
  assert.notEqual(result.isError, true);
  assert.equal(calls.at(-1)?.args.at(-1), 'com.example.Facebook');
  const write = calls.find((call) => call.args[0] === 'file' && call.args[1] === 'write');
  assert.ok(write?.args.includes('group.com.facebook.Facebook/mobileconfig/mc_overrides.json'));
  await client.close();
});

test('Instagram iOS falls back to the Documents MobileConfig directory', async () => {
  const {client, calls} = await connect({iosGroupRoot: 'Documents\nLibrary\n'});
  const result = await client.callTool({
    name: 'whitehat_configure',
    arguments: {platform: 'ios', app: 'instagram', enabled: true, account_id: '12345'},
  });
  assert.notEqual(result.isError, true);
  const write = calls.find((call) => call.args[0] === 'file' && call.args[1] === 'write');
  assert.ok(
    write?.args.includes(
      'group.com.burbn.instagram/Documents/mobileconfig/12345.data/mc_overrides.json',
    ),
  );
  await client.close();
});

test('Instagram iOS selects the logged-in user MobileConfig directory', async () => {
  const {client, calls} = await connect();
  await client.callTool({
    name: 'whitehat_configure',
    arguments: {platform: 'ios', app: 'instagram', enabled: true, account_id: '12345'},
  });
  const write = calls.find((call) => call.args[0] === 'file' && call.args[1] === 'write');
  assert.ok(
    write?.args.includes('group.com.burbn.instagram/mobileconfig/12345.data/mc_overrides.json'),
  );
  await client.close();
});

test('iOS disable removes only its own MC override', async () => {
  const {client, calls} = await connect({
    iosOverrides:
      '{"_qe_overrides_":["kept"],"120291:whitehat_settings_ios_ig":["0: show_settings: true","1: other: false"]}',
  });
  const result = await client.callTool({
    name: 'whitehat_configure',
    arguments: {platform: 'ios', app: 'threads', enabled: false, account_id: '12345'},
  });
  assert.notEqual(result.isError, true);
  const write = calls.find((call) => call.args[0] === 'file' && call.args[1] === 'write');
  assert.deepEqual(JSON.parse(write?.stdin ?? '{}'), {
    _qe_overrides_: ['kept'],
    '120291:whitehat_settings_ios_ig': ['1: other: false'],
  });
  await client.close();
});

test('iOS disable without an overrides file writes none', async () => {
  const {client, calls} = await connect({iosOverrides: null});
  const result = await client.callTool({
    name: 'whitehat_configure',
    arguments: {platform: 'ios', app: 'facebook', enabled: false},
  });
  assert.notEqual(result.isError, true);
  assert.ok(!calls.some((call) => call.args[0] === 'file' && call.args[1] === 'write'));
  await client.close();
});

test('iOS disable clears the bypass before best-effort MC cleanup and reports a failed cleanup', async () => {
  const {client, calls} = await connect({failIosList: true});
  const result = await client.callTool({
    name: 'whitehat_configure',
    arguments: {platform: 'ios', app: 'facebook', enabled: false},
  });
  assert.notEqual(result.isError, true);
  assert.match(
    JSON.stringify(result.content),
    /MobileConfig cleanup did not complete: simulated MC read failure/,
  );
  const set = calls.find((call) => call.args[0] === 'set');
  assert.deepEqual(set?.args.slice(-2), ['FBWhitehatSSLPinningBypass', 'false']);
  assert.deepEqual([calls[0]?.args[0], calls.at(-1)?.args[0]], ['terminate', 'launch']);
  await client.close();
});

test('Android profiles stop the app, write their MC gates, and restart through adb', async () => {
  const {client, calls} = await connect();
  const result = await client.callTool({
    name: 'whitehat_configure',
    arguments: {platform: 'android', app: 'facebook', enabled: true},
  });
  assert.notEqual(result.isError, true);
  const adbCalls = calls.filter((call) => call.executable === 'adb-test');
  assert.equal(adbCalls[0]?.words.join(' '), 'am force-stop com.facebook.katana');
  const write = adbCalls.find((call) => writesTo(call, MC));
  assert.match(write?.stdin ?? '', /android_show_whitehat_settings: true/);
  assert.equal(adbCalls.at(-1)?.words[0], 'monkey');
  await client.close();
});

test('every Android app sends each adb command as one quoted device command', async () => {
  const {client, calls} = await connect();
  for (const app of ['facebook', 'instagram', 'messenger', 'threads']) {
    for (const enabled of [true, false]) {
      const result = await client.callTool({
        name: 'whitehat_configure',
        arguments: {platform: 'android', app, enabled},
      });
      assert.notEqual(result.isError, true, `${app} ${enabled}: ${JSON.stringify(result.content)}`);
    }
  }
  assert.ok(
    calls.filter((call) => call.executable === 'adb-test').every((call) => call.args.length === 4),
  );
  await client.close();
});

test('Android app_id targets the supplied package', async () => {
  const {client, calls} = await connect();
  const result = await client.callTool({
    name: 'whitehat_configure',
    arguments: {platform: 'android', app: 'facebook', enabled: true, app_id: 'com.example.app'},
  });
  assert.notEqual(result.isError, true);
  const appCalls = calls.filter(
    (call) => call.executable === 'adb-test' && call.words.join(' ') !== 'id -u',
  );
  assert.ok(appCalls.length > 0);
  assert.ok(appCalls.every((call) => call.words.includes('com.example.app')));
  assert.ok(!appCalls.some((call) => /chown|chcon/.test(call.args.join(' '))));
  await client.close();
});

test('a root adb shell edits the app as root and hands files back to the app', async () => {
  const {client, calls} = await connect({root: true});
  const result = await client.callTool({
    name: 'whitehat_configure',
    arguments: {platform: 'android', app: 'facebook', enabled: true},
  });
  assert.notEqual(result.isError, true);
  const appCalls = calls.filter((call) => appScript(call.words) !== '');
  assert.ok(appCalls.length > 0);
  assert.ok(
    appCalls.every(
      (call) =>
        call.words[0] === 'sh' &&
        call.words[2]?.startsWith('cd /data/data/com.facebook.katana || exit 1; umask 007; ') ===
          true,
    ),
  );
  const writes = appCalls.filter((call) => stagedWrite(call.words) !== undefined);
  assert.deepEqual(
    writes.map((call) => stagedWrite(call.words)?.path),
    [MC, PREFS_DB_PATH],
  );
  assert.deepEqual(
    writes.map((call) => stagedWrite(call.words)?.owned),
    [
      ['files', 'files/mobileconfig', `${MC}.whitehat`],
      ['databases', `${PREFS_DB_PATH}.whitehat`],
    ],
  );
  assert.equal(calls.filter((call) => call.words.join(' ') === 'id -u').length, 1);
  await client.close();
});

test('a release build without a root shell points at adb root', async () => {
  const {client} = await connect({notDebuggable: true});
  const result = await client.callTool({
    name: 'whitehat_configure',
    arguments: {platform: 'android', app: 'instagram', enabled: true},
  });
  assert.equal(result.isError, true);
  assert.match(
    JSON.stringify(result.content),
    /not debuggable: com\.instagram\.android; run `adb root` first/,
  );
  await client.close();
});

test('Android rejects an app_id that is not a package name', async () => {
  const {client, calls} = await connect();
  const result = await client.callTool({
    name: 'whitehat_configure',
    arguments: {platform: 'android', app: 'facebook', enabled: true, app_id: '..'},
  });
  assert.equal(result.isError, true);
  assert.ok(!calls.some((call) => call.executable === 'adb-test'));
  await client.close();
});

test('Android disable removes only its own MC overrides and honors device_id', async () => {
  const existing = {
    _qe_overrides_: [],
    '23066:android_messenger_whitehat': ['2: enabled: true', '3: unrelated: true'],
    '1:other': ['0: label: café ✓ 🙂'],
  };
  const {client, calls} = await connect({androidOverrides: JSON.stringify(existing)});
  const result = await client.callTool({
    name: 'whitehat_configure',
    arguments: {
      platform: 'android',
      app: 'messenger',
      enabled: false,
      device_id: 'requested-device',
    },
  });
  assert.notEqual(result.isError, true);
  assert.ok(
    !JSON.stringify(result.content).includes('MobileConfig cleanup did not complete'),
    JSON.stringify(result.content),
  );
  const adbCalls = calls.filter((call) => call.executable === 'adb-test');
  assert.ok(adbCalls.length > 0);
  assert.ok(adbCalls.every((call) => call.args[0] === '-s' && call.args[1] === 'requested-device'));
  const write = adbCalls.find((call) => writesTo(call, MC));
  assert.deepEqual(JSON.parse(write?.stdin ?? '{}'), {
    ...existing,
    '23066:android_messenger_whitehat': ['3: unrelated: true'],
  });
  const dbWrite = adbCalls.find((call) => writesTo(call, PREFS_DB_PATH));
  assert.ok(dbWrite?.stdin !== undefined);
  const rows = readWrittenPrefs(dbWrite.stdin, 'SELECT key, value FROM preferences') as Array<{
    key: string;
    value: string;
  }>;
  assert.equal(rows.find((row) => row.key === '/settings/http/user_certs')?.value, '0');
  assert.equal(rows.find((row) => row.key === '/settings/http/check_certs')?.value, '1');
  assert.equal(rows.find((row) => row.key === '/settings/http/disable_fizz')?.value, '0');
  assert.equal(rows.find((row) => row.key === '/settings/http/user_certs_ttl')?.value, '');
  await client.close();
});

test('Android disable without an overrides file leaves it absent', async () => {
  const {client, calls} = await connect({androidOverrides: null});
  const result = await client.callTool({
    name: 'whitehat_configure',
    arguments: {platform: 'android', app: 'threads', enabled: false},
  });
  assert.notEqual(result.isError, true);
  assert.ok(!JSON.stringify(result.content).includes('MobileConfig cleanup did not complete'));
  assert.ok(!calls.some((call) => writesTo(call, MC)));
  await client.close();
});

test('Android enable creates a missing overrides file', async () => {
  const {client, calls} = await connect({androidOverrides: null});
  const result = await client.callTool({
    name: 'whitehat_configure',
    arguments: {platform: 'android', app: 'threads', enabled: true},
  });
  assert.notEqual(result.isError, true);
  const write = calls.find((call) => writesTo(call, MC));
  assert.deepEqual(JSON.parse(write?.stdin ?? '{}'), {
    _qe_overrides_: [],
    '115529:whitehat_settings_android_ig': ['0: show_settings: true'],
  });
  await client.close();
});

test('Instagram Android edits DevPreferences after stopping the app and honors duration_seconds', async () => {
  const {client, calls} = await connect();
  const result = await client.callTool({
    name: 'whitehat_configure',
    arguments: {platform: 'android', app: 'instagram', enabled: true, duration_seconds: 3600},
  });
  assert.notEqual(result.isError, true);
  const stop = calls.findIndex(
    (call) => call.words.join(' ') === 'am force-stop com.instagram.android',
  );
  const write = calls.findIndex((call) => writesTo(call, DEVPREFS));
  assert.ok(stop !== -1 && write > stop);
  const xml = calls[write]?.stdin ?? '';
  assert.match(xml, /<boolean name="debug_allow_user_certs" value="true" \/>/);
  assert.match(xml, /<string name="debug_allow_user_certs_ttl">1000000:3600000<\/string>/);
  await client.close();
});

test('Instagram Android fills an empty DevPreferences map', async () => {
  const {client, calls} = await connect({
    devprefs: "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map />\n",
  });
  const result = await client.callTool({
    name: 'whitehat_configure',
    arguments: {platform: 'android', app: 'threads', enabled: true},
  });
  assert.notEqual(result.isError, true);
  const xml = calls.find((call) => writesTo(call, DEVPREFS))?.stdin ?? '';
  assert.match(xml, /<map>\n {4}<boolean name="debug_allow_user_certs" value="true" \/>/);
  assert.match(xml, /<string name="debug_allow_user_certs_ttl">1000000:86400000<\/string>/);
  await client.close();
});

test('Instagram Android refuses to rewrite unrecognized DevPreferences', async () => {
  const {client, calls} = await connect({devprefs: '<map>\n'});
  const result = await client.callTool({
    name: 'whitehat_configure',
    arguments: {platform: 'android', app: 'instagram', enabled: true},
  });
  assert.equal(result.isError, true);
  assert.ok(!calls.some((call) => writesTo(call, DEVPREFS)));
  await client.close();
});

test('Facebook Android enables user certificates without disabling certificate checks', async () => {
  const {client, calls} = await connect();
  const result = await client.callTool({
    name: 'whitehat_configure',
    arguments: {platform: 'android', app: 'facebook', enabled: true},
  });
  assert.notEqual(result.isError, true);
  const dbWrite = calls.find((call) => writesTo(call, PREFS_DB_PATH));
  assert.ok(dbWrite?.stdin !== undefined);
  const rows = readWrittenPrefs(
    dbWrite.stdin,
    'SELECT key, type, value FROM preferences',
  ) as Array<{key: string; type: number; value: string}>;
  assert.deepEqual(rows.map((row) => [row.key, row.type, row.value]).sort(), [
    ['/settings/http/disable_fizz', 2, '1'],
    ['/settings/http/user_certs', 2, '1'],
    ['/settings/http/user_certs_ttl', 1, '1000000:86400000'],
  ]);
  await client.close();
});

test('Facebook Android writes the requested TTL', async () => {
  const {client, calls} = await connect();
  const result = await client.callTool({
    name: 'whitehat_configure',
    arguments: {platform: 'android', app: 'facebook', enabled: true, duration_seconds: 7200},
  });
  assert.notEqual(result.isError, true);
  const dbWrite = calls.find((call) => writesTo(call, PREFS_DB_PATH));
  assert.ok(dbWrite?.stdin !== undefined);
  const rows = readWrittenPrefs(
    dbWrite.stdin,
    "SELECT value FROM preferences WHERE key='/settings/http/user_certs_ttl'",
  ) as Array<{value: string}>;
  assert.equal(rows[0]?.value, '1000000:7200000');
  await client.close();
});

test('Facebook Android needs an existing prefs_db to enable but not to disable', async () => {
  const {client, calls} = await connect({prefsFiles: {}});
  const enable = await client.callTool({
    name: 'whitehat_configure',
    arguments: {platform: 'android', app: 'facebook', enabled: true},
  });
  assert.equal(enable.isError, true);
  assert.match(JSON.stringify(enable.content), /has no prefs_db; launch it once/);
  assert.equal(calls.filter((call) => call.executable === 'adb-test').at(-1)?.words[0], 'monkey');
  const disable = await client.callTool({
    name: 'whitehat_configure',
    arguments: {platform: 'android', app: 'facebook', enabled: false},
  });
  assert.notEqual(disable.isError, true);
  assert.ok(!calls.some((call) => writesTo(call, PREFS_DB_PATH)));
  await client.close();
});

test('Facebook preference edit preserves unrelated data committed only in WAL', async () => {
  const {client, calls} = await connect({prefsFiles: walPrefsDb()});
  const result = await client.callTool({
    name: 'whitehat_configure',
    arguments: {platform: 'android', app: 'facebook', enabled: false},
  });
  assert.notEqual(result.isError, true);
  const write = calls.find((call) => writesTo(call, PREFS_DB_PATH));
  assert.ok(write?.stdin !== undefined);
  const rows = readWrittenPrefs(
    write.stdin,
    "SELECT value FROM preferences WHERE key='/unrelated'",
  ) as Array<{value: string}>;
  assert.equal(rows[0]?.value, 'preserve-me');
  assert.deepEqual(stagedWrite(write.words)?.sidecars, [
    'databases/prefs_db-wal',
    'databases/prefs_db-shm',
    'databases/prefs_db-journal',
  ]);
  await client.close();
});

test('Facebook preference edit reads an existing rollback journal before replacing the database', async () => {
  const {client, calls} = await connect({prefsFiles: {prefs_db: PREFS_DB, 'prefs_db-journal': ''}});
  const result = await client.callTool({
    name: 'whitehat_configure',
    arguments: {platform: 'android', app: 'facebook', enabled: true},
  });
  assert.notEqual(result.isError, true);
  const journalRead = calls.findIndex(
    (call) => appScript(call.words) === 'base64 databases/prefs_db-journal',
  );
  const write = calls.findIndex((call) => writesTo(call, PREFS_DB_PATH));
  assert.ok(journalRead !== -1 && write > journalRead);
  await client.close();
});

test('an existing WAL transfer failure aborts before any database write or deletion', async () => {
  const {client, calls} = await connect({prefsFiles: walPrefsDb(), failPrefsFile: 'prefs_db-wal'});
  const result = await client.callTool({
    name: 'whitehat_configure',
    arguments: {platform: 'android', app: 'messenger', enabled: false},
  });
  assert.equal(result.isError, true);
  assert.ok(!calls.some((call) => writesTo(call, PREFS_DB_PATH)));
  assert.equal(calls.filter((call) => call.executable === 'adb-test').at(-1)?.words[0], 'monkey');
  await client.close();
});

test('mergeOverrides replaces only entries with the same parameter key', () => {
  const merged = mergeOverrides(
    {'1:ns': ['11: other: true', '1: show: false', 7]},
    [{namespace: '1:ns', parameter: '1: show: true'}],
    true,
  );
  assert.deepEqual(merged, {_qe_overrides_: [], '1:ns': ['11: other: true', 7, '1: show: true']});
});

test('mergeOverrides sets and clears several parameters of one namespace', () => {
  const wanted = [
    {namespace: '9:ns', parameter: '0: a: true'},
    {namespace: '9:ns', parameter: '2: b: true'},
    {namespace: '9:ns', parameter: '5: c: true'},
  ];
  const enabled = mergeOverrides({'9:ns': ['2: b: false', '3: keep: false']}, wanted, true);
  assert.deepEqual(enabled['9:ns'], ['3: keep: false', '0: a: true', '2: b: true', '5: c: true']);
  assert.deepEqual(mergeOverrides(enabled, wanted, false)['9:ns'], ['3: keep: false']);
});

test('mergeOverrides on disable drops emptied namespaces and adds nothing', () => {
  const wanted = [
    {namespace: '1:ns', parameter: '0: show: true'},
    {namespace: '2:ns', parameter: '0: show: true'},
  ];
  assert.deepEqual(
    mergeOverrides(
      {'1:ns': ['0: show: true'], '2:ns': ['0: show: true', '1: keep: true']},
      wanted,
      false,
    ),
    {'2:ns': ['1: keep: true']},
  );
  assert.throws(() => mergeOverrides({'1:ns': 'not-an-array'}, wanted, false), /is not an array/);
});

test('child processes do not see empty device-selection variables', async () => {
  const saved = {udid: process.env.IDB_UDID, companion: process.env.IDB_COMPANION};
  process.env.IDB_UDID = '';
  process.env.IDB_COMPANION = 'companion:1';
  try {
    const result = await spawnRunner(process.execPath, [
      '-e',
      'process.stdout.write(JSON.stringify([process.env.IDB_UDID ?? null, process.env.IDB_COMPANION ?? null]))',
    ]);
    assert.deepEqual(JSON.parse(result.stdout), [null, 'companion:1']);
  } finally {
    for (const [name, value] of [
      ['IDB_UDID', saved.udid],
      ['IDB_COMPANION', saved.companion],
    ] as const) {
      if (value === undefined) delete process.env[name];
      else process.env[name] = value;
    }
  }
});

test('spawnRunner passes stdin through and reports a failed exit with its stderr', async () => {
  const echoed = await spawnRunner(
    process.execPath,
    ['-e', 'process.stdin.pipe(process.stdout)'],
    'payload',
  );
  assert.equal(echoed.stdout, 'payload');
  await assert.rejects(
    spawnRunner(process.execPath, ['-e', 'process.stderr.write("broken"); process.exit(3)']),
    /exited 3: broken/,
  );
});

test('a failed step and a failed relaunch are both reported', async () => {
  const {client} = await connect({failPrefsFile: 'prefs_db', failRelaunch: true});
  const result = await client.callTool({
    name: 'whitehat_configure',
    arguments: {platform: 'android', app: 'facebook', enabled: true},
  });
  assert.equal(result.isError, true);
  assert.match(
    JSON.stringify(result.content),
    /simulated transfer failure; relaunching the app also failed: simulated relaunch failure/,
  );
  await client.close();
});

test('a relaunch failure after a successful change says the settings were applied', async () => {
  const {client} = await connect({failRelaunch: true});
  const result = await client.callTool({
    name: 'whitehat_configure',
    arguments: {platform: 'android', app: 'instagram', enabled: true},
  });
  assert.equal(result.isError, true);
  assert.match(
    JSON.stringify(result.content),
    /Settings were applied, but relaunching the app failed/,
  );
  await client.close();
});

test('staged write scripts replace the file in a real shell and keep it on a short transfer', async () => {
  const {client, calls} = await connect();
  for (const app of ['instagram', 'facebook']) {
    await client.callTool({
      name: 'whitehat_configure',
      arguments: {platform: 'android', app, enabled: true},
    });
  }
  await client.close();
  for (const path of [DEVPREFS, PREFS_DB_PATH]) {
    const call = calls.find((candidate) => writesTo(candidate, path));
    const staged = call === undefined ? undefined : stagedWrite(call.words);
    assert.ok(call?.stdin !== undefined && staged !== undefined);
    const expected =
      staged.decoder === 'cat' ? Buffer.from(call.stdin) : Buffer.from(call.stdin, 'base64');
    const directory = mkdtempSync(join(tmpdir(), 'zurp-test-staged-'));
    try {
      mkdirSync(join(directory, path, '..'), {recursive: true});
      writeFileSync(join(directory, path), 'original');
      const script = `cd ${directory} && ${appScript(call.words)}`;
      await assert.rejects(spawnRunner('sh', ['-c', script], call.stdin.slice(0, -8)));
      assert.equal(readFileSync(join(directory, path), 'utf8'), 'original');
      assert.ok(!existsSync(join(directory, `${path}.whitehat`)));
      await spawnRunner('sh', ['-c', script], call.stdin);
      assert.deepEqual(readFileSync(join(directory, path)), expected);
      assert.ok(!existsSync(join(directory, `${path}.whitehat`)));
    } finally {
      rmSync(directory, {recursive: true, force: true});
    }
  }
});
