/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import {mkdtemp, readFile, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {DatabaseSync} from 'node:sqlite';

import {Adb} from './adb.js';
import type {AppProfile} from './profiles.js';

// Value types in the prefs_db preferences table.
const STRING = 1;
const BOOLEAN = 2;

function xmlEntry(name: string, value: boolean | string): string {
  return typeof value === 'boolean'
    ? `    <boolean name="${name}" value="${value}" />`
    : `    <string name="${name}">${value}</string>`;
}

function setXmlValue(xml: string, name: string, value: boolean | string): string {
  const entry = xmlEntry(name, value);
  xml = xml.replace(/<map\s*\/>/, '<map>\n</map>');
  const pattern = new RegExp(
    `\\s*<(?:boolean|string)\\s+name="${name}"[^>]*(?:/>|>[^<]*</string>)`,
  );
  if (pattern.test(xml)) return xml.replace(pattern, `\n${entry}`);
  const updated = xml.replace(/\s*<\/map>\s*$/, `\n${entry}\n</map>\n`);
  if (updated === xml) throw new Error('devprefs.xml is not a shared preferences map');
  return updated;
}

async function setInstagramPreferences(
  adb: Adb,
  packageName: string,
  enabled: boolean,
  ttl: string,
): Promise<void> {
  const path = 'shared_prefs/devprefs.xml';
  let xml =
    (await adb.readSandboxFile(packageName, path)) ??
    `<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n`;
  xml = setXmlValue(xml, 'debug_allow_user_certs', enabled);
  xml = setXmlValue(xml, 'debug_allow_user_certs_ttl', ttl);
  await adb.writeSandboxFile(packageName, path, xml);
}

async function setFacebookPreferences(
  adb: Adb,
  packageName: string,
  enabled: boolean,
  ttl: string,
): Promise<void> {
  const existing = await adb.listSandboxDirectory(packageName, 'databases');
  if (!existing.includes('prefs_db')) {
    if (!enabled) return;
    throw new Error(
      `${packageName} has no prefs_db; launch it once before configuring whitehat settings`,
    );
  }
  const directory = await mkdtemp(join(tmpdir(), 'zurp-prefs-'));
  const names = ['prefs_db', 'prefs_db-wal', 'prefs_db-shm', 'prefs_db-journal'] as const;
  try {
    for (const name of names) {
      if (existing.includes(name)) {
        const contents = await adb.readSandboxBase64(packageName, `databases/${name}`);
        await writeFile(join(directory, name), contents);
      }
    }
    const dbPath = join(directory, 'prefs_db');
    const database = new DatabaseSync(dbPath);
    const statement = database.prepare(
      'INSERT OR REPLACE INTO preferences(key,type,value) VALUES(?,?,?)',
    );
    statement.run('/settings/http/user_certs', BOOLEAN, enabled ? '1' : '0');
    // check_certs=0 makes WebViews accept any certificate, so it is only ever restored.
    if (!enabled) statement.run('/settings/http/check_certs', BOOLEAN, '1');
    statement.run('/settings/http/disable_fizz', BOOLEAN, enabled ? '1' : '0');
    statement.run('/settings/http/user_certs_ttl', STRING, ttl);
    database.exec('PRAGMA wal_checkpoint(TRUNCATE)');
    database.close();
    await adb.writeSandboxBase64(packageName, 'databases/prefs_db', await readFile(dbPath), [
      'databases/prefs_db-wal',
      'databases/prefs_db-shm',
      'databases/prefs_db-journal',
    ]);
  } finally {
    await rm(directory, {recursive: true, force: true});
  }
}

export async function setAndroidWhitehatPreferences(
  adb: Adb,
  profile: AppProfile,
  enabled: boolean,
  ttl: string,
): Promise<void> {
  if (profile.app === 'instagram' || profile.app === 'threads') {
    await setInstagramPreferences(adb, profile.appId, enabled, ttl);
  } else {
    await setFacebookPreferences(adb, profile.appId, enabled, ttl);
  }
}
