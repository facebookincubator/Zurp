/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

export const PACKAGE_NAME = 'bug-bounty-research';
export const PACKAGE_VERSION = '0.1.0';

/**
 * Identifies this client to the endpoint. Researcher traffic is monitored, and a request
 * that names the tool it came from is one nobody has to guess about later.
 */
export function userAgent(serverName: string): string {
  return `${PACKAGE_NAME}/${PACKAGE_VERSION} (${serverName})`;
}
