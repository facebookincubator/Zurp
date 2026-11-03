/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import {Adb} from './adb.js';
import {Idb} from './idb.js';
import type {AppProfile, McOverride} from './profiles.js';

type Overrides = Record<string, unknown>;

const ANDROID_OVERRIDES = 'files/mobileconfig/mc_overrides.json';

function parseOverrides(raw: string): Overrides {
  const value: unknown = JSON.parse(raw);
  if (typeof value !== 'object' || value === null || Array.isArray(value))
    throw new Error('mc_overrides.json must contain a JSON object');
  return value as Overrides;
}

export function mergeOverrides(
  existing: Overrides,
  wanted: readonly McOverride[],
  enabled: boolean,
): Overrides {
  const merged: Overrides = enabled
    ? {...existing, _qe_overrides_: existing._qe_overrides_ ?? []}
    : {...existing};
  for (const {namespace, parameter} of wanted) {
    const current = merged[namespace];
    if (current !== undefined && !Array.isArray(current))
      throw new Error(`${namespace} is not an array in mc_overrides.json`);
    if (!/:\s*true\s*$/.test(parameter))
      throw new Error(`Whitehat override does not enable a boolean: ${parameter}`);
    const id = parameter.split(':', 1)[0]?.trim();
    const kept = ((current ?? []) as unknown[]).filter(
      (entry) => typeof entry !== 'string' || !entry.trimStart().startsWith(`${id}:`),
    );
    if (enabled) merged[namespace] = [...kept, parameter];
    else if (kept.length > 0) merged[namespace] = kept;
    else delete merged[namespace];
  }
  return merged;
}

async function iosOverridePath(
  idb: Idb,
  profile: AppProfile,
  udid: string | undefined,
  accountId: string | undefined,
): Promise<string> {
  const group = profile.appGroup;
  if (group === undefined) throw new Error(`No app group configured for ${profile.app}`);
  if (profile.sessionParents === undefined) return `${group}/mobileconfig/mc_overrides.json`;
  if (accountId === undefined)
    throw new Error(
      `${profile.app} iOS requires account_id to select its MobileConfig session directory`,
    );
  for (const parent of profile.sessionParents.map((subdirectory) =>
    subdirectory === '' ? group : `${group}/${subdirectory}`,
  )) {
    const entries = await idb.groupList(parent, udid);
    if (!entries.includes('mobileconfig')) continue;
    const directory = `${parent}/mobileconfig`;
    const sessions = await idb.groupList(directory, udid);
    const wanted = `${accountId}.data`;
    if (sessions.includes(wanted)) return `${directory}/${wanted}/mc_overrides.json`;
  }
  throw new Error(
    `No MobileConfig session directory for account ${accountId}; launch ${profile.app} and log in first`,
  );
}

export async function setIosMobileConfig(
  idb: Idb,
  profile: AppProfile,
  enabled: boolean,
  udid: string | undefined,
  accountId: string | undefined,
): Promise<void> {
  const path = await iosOverridePath(idb, profile, udid, accountId);
  const directory = path.slice(0, path.lastIndexOf('/'));
  const exists = (await idb.groupList(directory, udid)).includes('mc_overrides.json');
  if (!exists && !enabled) return;
  const existing = exists ? parseOverrides(await idb.groupRead(path, udid)) : {};
  await idb.groupWrite(
    path,
    `${JSON.stringify(mergeOverrides(existing, profile.overrides, enabled), null, 2)}\n`,
    udid,
  );
}

export async function setAndroidMobileConfig(
  adb: Adb,
  profile: AppProfile,
  enabled: boolean,
): Promise<void> {
  const raw = await adb.readSandboxFile(profile.appId, ANDROID_OVERRIDES);
  if (raw === undefined && !enabled) return;
  const existing = raw === undefined ? {} : parseOverrides(raw);
  await adb.writeSandboxFile(
    profile.appId,
    ANDROID_OVERRIDES,
    `${JSON.stringify(mergeOverrides(existing, profile.overrides, enabled), null, 2)}\n`,
  );
}
