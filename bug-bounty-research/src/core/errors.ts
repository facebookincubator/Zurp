/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

/**
 * One failure taxonomy for every researcher-facing endpoint. The kinds are chosen by
 * what the researcher should do next, not by what the transport did — `forbidden` and
 * `unauthorized` are both 4xx but they send you to completely different places.
 */
export type ApiErrorKind =
  | 'no-token'
  | 'unauthorized'
  | 'forbidden'
  | 'not-found'
  | 'bad-request'
  | 'rate-limited'
  | 'local-budget'
  | 'server-error'
  | 'network'
  | 'malformed';

export class ApiError extends Error {
  readonly kind: ApiErrorKind;
  readonly status: number | undefined;

  constructor(kind: ApiErrorKind, message: string, status?: number) {
    super(message);
    this.name = 'ApiError';
    this.kind = kind;
    this.status = status;
  }
}

/** True when the same request, sent later, has a real chance of a different answer. */
export function isTransient(kind: ApiErrorKind): boolean {
  return kind === 'rate-limited' || kind === 'server-error' || kind === 'network';
}
