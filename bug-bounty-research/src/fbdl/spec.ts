/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import type { Action, SetupEntity } from "./schema.js";

export interface FbdlSpec {
  readonly entities: readonly SetupEntity[];
  readonly actions: readonly Action[];
  readonly entityIndex: ReadonlyMap<string, SetupEntity>;
  readonly actionIndex: ReadonlyMap<string, Action>;
  readonly entityTypes: ReadonlySet<string>;
}

let current: FbdlSpec | null = null;

export function setSpec(entities: readonly SetupEntity[], actions: readonly Action[]): FbdlSpec {
  const entityIndex = new Map(entities.map((e) => [e.type, e]));
  const actionIndex = new Map(actions.map((a) => [a.name, a]));
  const entityTypes = new Set(entities.map((e) => e.type));
  current = { entities, actions, entityIndex, actionIndex, entityTypes };
  return current;
}

export function clearSpec(): void {
  current = null;
}

export function getSpec(): FbdlSpec {
  if (current === null) {
    throw new Error(
      "FBDL spec has not been loaded. Call loadSpec() during server startup (or setSpec() in tests).",
    );
  }
  return current;
}

export function getSetupEntities(): readonly SetupEntity[] {
  return getSpec().entities;
}

export function getActions(): readonly Action[] {
  return getSpec().actions;
}

export function getSetupEntity(type: string): SetupEntity | undefined {
  return getSpec().entityIndex.get(type);
}

export function getAction(name: string): Action | undefined {
  return getSpec().actionIndex.get(name);
}

export function getEntityTypes(): ReadonlySet<string> {
  return getSpec().entityTypes;
}
