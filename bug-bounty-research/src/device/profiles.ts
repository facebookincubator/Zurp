/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

export type App = 'facebook' | 'instagram' | 'messenger' | 'threads';
export type Platform = 'android' | 'ios';

export interface McOverride {
  readonly namespace: string;
  readonly parameter: string;
}

export interface AppProfile {
  readonly app: App;
  readonly platform: Platform;
  readonly appId: string;
  readonly appGroup?: string;
  readonly sessionParents?: readonly string[];
  readonly overrides: readonly McOverride[];
}

const IG_IOS: McOverride = {
  namespace: '120291:whitehat_settings_ios_ig',
  parameter: '0: show_settings: true',
};
const FB_IOS_SESSIONED: McOverride = {
  namespace: '121208:whitehat_sessioned_settings_fb_ios',
  parameter: '0: show_settings: true',
};
const IG_ANDROID: McOverride = {
  namespace: '115529:whitehat_settings_android_ig',
  parameter: '0: show_settings: true',
};

export const PROFILES: readonly AppProfile[] = [
  {
    app: 'facebook',
    platform: 'ios',
    appId: 'com.facebook.Facebook',
    appGroup: 'group.com.facebook.Facebook',
    overrides: [
      {namespace: '114381:whitehat_settings_ios', parameter: '0: enabled: true'},
      FB_IOS_SESSIONED,
    ],
  },
  {
    app: 'messenger',
    platform: 'ios',
    appId: 'com.facebook.Messenger',
    appGroup: 'group.com.facebook.Messenger',
    overrides: [FB_IOS_SESSIONED],
  },
  {
    app: 'instagram',
    platform: 'ios',
    appId: 'com.burbn.instagram',
    appGroup: 'group.com.burbn.instagram',
    sessionParents: ['', 'Documents'],
    overrides: [IG_IOS],
  },
  {
    app: 'threads',
    platform: 'ios',
    appId: 'com.burbn.barcelona',
    appGroup: 'group.com.burbn.instagram',
    sessionParents: ['barcelona/Documents'],
    overrides: [IG_IOS],
  },
  {
    app: 'facebook',
    platform: 'android',
    appId: 'com.facebook.katana',
    overrides: [
      {
        namespace: '29603:android_bookmark_settings',
        parameter: '0: android_show_whitehat_settings: true',
      },
      {
        namespace: '30954:android_katana_internsettingsactivity',
        parameter: '2: android_show_whitehat_settings: true',
      },
      {
        namespace: '30954:android_katana_internsettingsactivity',
        parameter: '5: android_whitehat_setting_intercept_traffic: true',
      },
    ],
  },
  {
    app: 'messenger',
    platform: 'android',
    appId: 'com.facebook.orca',
    overrides: [{namespace: '23066:android_messenger_whitehat', parameter: '2: enabled: true'}],
  },
  {app: 'instagram', platform: 'android', appId: 'com.instagram.android', overrides: [IG_ANDROID]},
  {app: 'threads', platform: 'android', appId: 'com.instagram.barcelona', overrides: [IG_ANDROID]},
];

const BUNDLE_ID = /^[A-Za-z0-9][A-Za-z0-9-]*(?:\.[A-Za-z0-9-]+)+$/;

export function profileFor(platform: Platform, app: App, appId?: string): AppProfile {
  const profile = PROFILES.find(
    (candidate) => candidate.platform === platform && candidate.app === app,
  );
  if (profile === undefined) throw new Error(`Unsupported app/platform pair: ${app}/${platform}`);
  if (appId === undefined) return profile;
  if (platform === 'ios' && !BUNDLE_ID.test(appId))
    throw new Error(`Invalid iOS bundle id: ${appId}`);
  return {...profile, appId};
}
