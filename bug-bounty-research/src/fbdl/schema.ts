/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

/**
 * FBDL language types. The concrete spec data (entities, actions, examples) is
 * loaded at runtime from the FBDL reference API — see specLoader.ts and spec.ts.
 */

export interface Param {
  readonly name: string;
  readonly required: boolean;
  readonly description: string;
  readonly values?: readonly string[];
  readonly isList?: boolean;
}

export interface SetupEntity {
  readonly type: string;
  readonly description: string;
  readonly hasLabel: boolean;
  readonly params: readonly Param[];
  readonly example: string;
}

export interface Action {
  readonly name: string;
  readonly description: string;
  readonly signature: string;
  readonly supportsVoiceSwitcher: boolean;
  readonly targetTypes: readonly string[];
  readonly keywordParams: readonly Param[];
  readonly examples: readonly string[];
}
