/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import {McpServer} from '@modelcontextprotocol/sdk/server/mcp.js';
import type {CallToolResult} from '@modelcontextprotocol/sdk/types.js';
import {z} from 'zod';
import {PACKAGE_VERSION} from '../core/version.js';
import {Adb} from './adb.js';
import {setAndroidWhitehatPreferences} from './androidPreferences.js';
import {Idb, type IdbOptions} from './idb.js';
import {setAndroidMobileConfig, setIosMobileConfig} from './mobileconfig.js';
import type {CommandRunner} from './process.js';
import {type AppProfile, profileFor} from './profiles.js';

export const SERVER_NAME = 'whitehat-device-mcp';
const BYPASS_KEY = 'FBWhitehatSSLPinningBypass';
const EXPIRY_KEY = 'FBWhitehatSSLPinningBypassExpiry';
const OWNER_KEY = 'FBWhitehatSSLPinningBypassOwner';
const DEFAULT_DURATION_SECONDS = 24 * 60 * 60;
const MAX_DURATION_SECONDS = 7 * 24 * 60 * 60;

export interface ServerDeps {
  readonly run?: CommandRunner;
  readonly idbExecutable?: string;
  readonly androidSerial?: string;
  readonly now?: () => number;
}

interface Configuration {
  readonly profile: AppProfile;
  readonly enabled: boolean;
  readonly accountId: string | undefined;
  readonly deviceId: string | undefined;
  readonly durationSeconds: number;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

// The app rewrites its settings from memory, so it is stopped first and relaunched even if a
// step fails.
async function whileStopped(
  stop: () => Promise<void>,
  start: () => Promise<void>,
  work: () => Promise<void>,
): Promise<void> {
  await stop();
  try {
    await work();
  } catch (error) {
    await start().catch((relaunchError: unknown) => {
      throw new Error(
        `${errorMessage(error)}; relaunching the app also failed: ${errorMessage(relaunchError)}`,
      );
    });
    throw error;
  }
  await start().catch((relaunchError: unknown) => {
    throw new Error(
      `Settings were applied, but relaunching the app failed: ${errorMessage(relaunchError)}`,
    );
  });
}

async function cleanUp(work: () => Promise<void>): Promise<string> {
  try {
    await work();
    return '';
  } catch (error) {
    return ` MobileConfig cleanup did not complete: ${errorMessage(error)}.`;
  }
}

async function configureIos(idb: Idb, request: Configuration, now: () => number): Promise<string> {
  const {profile, enabled, accountId, deviceId} = request;
  if (profile.sessionParents !== undefined && enabled && accountId === undefined) {
    throw new Error(`${profile.app} iOS requires account_id`);
  }
  const device =
    deviceId === undefined ? {bundleId: profile.appId} : {bundleId: profile.appId, udid: deviceId};
  let note = '';
  await whileStopped(
    () => idb.terminate(profile.appId, deviceId),
    () => idb.launch(profile.appId, deviceId),
    async () => {
      if (!enabled) {
        await idb.setPreference(
          device,
          BYPASS_KEY,
          'bool',
          'false',
          'disable whitehat network inspection',
        );
        note = await cleanUp(() => setIosMobileConfig(idb, profile, false, deviceId, accountId));
        return;
      }
      await setIosMobileConfig(idb, profile, true, deviceId, accountId);
      if (profile.app === 'threads' && accountId !== undefined) {
        const expiry = Math.floor(now() / 1000) + request.durationSeconds;
        await idb.setPreference(
          device,
          EXPIRY_KEY,
          'float',
          String(expiry),
          'set whitehat inspection expiry',
        );
        await idb.setPreference(
          device,
          OWNER_KEY,
          'string',
          accountId,
          'scope whitehat inspection to its account',
        );
      }
      await idb.setPreference(
        device,
        BYPASS_KEY,
        'bool',
        'true',
        'enable whitehat network inspection',
      );
    },
  );
  return enabled
    ? `${profile.app} iOS restarted with its whitehat MobileConfig gate overridden and inspection requested. ` +
        'Configure the system proxy and install and trust its CA separately.'
    : `${profile.app} iOS restarted with inspection disabled.${note}`;
}

async function configureAndroid(
  adb: Adb,
  request: Configuration,
  now: () => number,
): Promise<string> {
  const {profile, enabled} = request;
  let note = '';
  await whileStopped(
    () => adb.stop(profile.appId),
    () => adb.restart(profile.appId),
    async () => {
      if (enabled) {
        await setAndroidMobileConfig(adb, profile, true);
        await setAndroidWhitehatPreferences(
          adb,
          profile,
          true,
          `${now()}:${request.durationSeconds * 1000}`,
        );
      } else {
        note = await cleanUp(() => setAndroidMobileConfig(adb, profile, false));
        await setAndroidWhitehatPreferences(adb, profile, false, '');
      }
    },
  );
  return enabled
    ? `${profile.app} Android restarted with its whitehat MobileConfig gate enabled and inspection requested. ` +
        'Configure the proxy and CA separately.'
    : `${profile.app} Android restarted with inspection disabled.${note}`;
}

export function buildServer(deps: ServerDeps = {}): McpServer {
  const idb = new Idb({
    ...(deps.idbExecutable === undefined ? {} : {executable: deps.idbExecutable}),
    ...(deps.run === undefined ? {} : {run: deps.run}),
  } satisfies IdbOptions);
  const now = deps.now ?? Date.now;
  const server = new McpServer({name: SERVER_NAME, version: PACKAGE_VERSION});

  server.registerTool(
    'whitehat_configure',
    {
      title: 'Configure a Meta mobile app for whitehat traffic inspection',
      description:
        'Override the app MobileConfig gate, update its persisted certificate-pinning settings, ' +
        'and restart it. iOS needs a simulator; Android needs a debuggable app or a root adb ' +
        'shell. Proxy and CA installation remain separate.',
      inputSchema: {
        platform: z.enum(['ios', 'android']),
        app: z.enum(['facebook', 'instagram', 'messenger', 'threads']),
        enabled: z.boolean(),
        account_id: z
          .string()
          .regex(/^\d+$/)
          .optional()
          .describe('Logged-in Instagram user id. Required for Instagram and Threads on iOS.'),
        app_id: z
          .string()
          .min(1)
          .optional()
          .describe('Override the default bundle id or Android package.'),
        device_id: z
          .string()
          .min(1)
          .optional()
          .describe('iOS UDID or Android serial. Environment defaults are used when omitted.'),
        duration_seconds: z
          .number()
          .int()
          .positive()
          .max(MAX_DURATION_SECONDS)
          .optional()
          .describe('Inspection lifetime for Threads iOS and Android. Defaults to 24 hours.'),
      },
    },
    async (input): Promise<CallToolResult> => {
      try {
        const request: Configuration = {
          profile: profileFor(input.platform, input.app, input.app_id),
          enabled: input.enabled,
          accountId: input.account_id,
          deviceId: input.device_id,
          durationSeconds: input.duration_seconds ?? DEFAULT_DURATION_SECONDS,
        };
        const text =
          input.platform === 'ios'
            ? await configureIos(idb, request, now)
            : await configureAndroid(
                new Adb(deps.run, input.device_id ?? deps.androidSerial),
                request,
                now,
              );
        return {content: [{type: 'text', text}]};
      } catch (error) {
        return {
          content: [{type: 'text', text: `Device command failed: ${errorMessage(error)}`}],
          isError: true,
        };
      }
    },
  );
  return server;
}
