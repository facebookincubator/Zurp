/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import type { Action, Param, SetupEntity } from "./schema.js";

interface RawParam {
  readonly name: string;
  readonly type: string;
  readonly info: string;
  readonly optional: boolean;
  readonly default: string;
  readonly values_json: string;
}

interface RawData {
  readonly name: string;
  readonly type: string;
}

interface RawSetup {
  readonly name: string;
  readonly info: string;
  readonly params: readonly RawParam[];
  readonly data: readonly RawData[];
}

interface RawAction {
  readonly name: string;
  readonly info: string;
  readonly params: readonly RawParam[];
  readonly target_types: readonly string[];
  readonly data: readonly RawData[];
}

export interface RawFbdlReference {
  readonly hints?: {
    readonly setup?: readonly RawSetup[];
    readonly action?: readonly RawAction[];
  };
}

export class FbdlSpecParseError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "FbdlSpecParseError";
  }
}

export interface ParsedSpec {
  readonly entities: readonly SetupEntity[];
  readonly actions: readonly Action[];
}

/**
 * Convert the raw FBDL reference API response into the internal SetupEntity /
 * Action shape consumed by the validator and MCP tools.
 *
 * Setup entries are filtered to the FBDL setup-block types — those produce
 * either no labeled object (e.g. Friendship) or a single anonymous one
 * (User/Page/Group/...). The API also exposes multi-field test-user helpers
 * (OculusThirdPartyTestDeveloper, WhitehatTestUser, …) that are not valid in a
 * [setup] declaration and are excluded.
 */
export function parseFbdlReference(raw: unknown): ParsedSpec {
  if (raw === null || typeof raw !== "object") {
    throw new FbdlSpecParseError("FBDL reference response must be a JSON object.");
  }
  const hints = (raw as { hints?: unknown }).hints;
  if (hints === null || typeof hints !== "object") {
    throw new FbdlSpecParseError("FBDL reference response is missing the 'hints' object.");
  }

  const { setup, action } = hints as { setup?: unknown; action?: unknown };
  if (
    (setup !== undefined && !Array.isArray(setup)) ||
    (action !== undefined && !Array.isArray(action))
  ) {
    throw new FbdlSpecParseError("hints.setup and hints.action must be arrays.");
  }

  const rawSetup = (setup ?? []) as readonly RawSetup[];
  const rawAction = (action ?? []) as readonly RawAction[];

  const entities = rawSetup.filter(isStandardSetupEntry).map(convertSetupEntity);
  const actions = rawAction.map(convertAction);

  return { entities, actions };
}

function isStandardSetupEntry(entry: RawSetup): boolean {
  if (entry.data.length === 0) return true;
  return entry.data.length === 1 && entry.data[0]?.name === "";
}

function convertSetupEntity(entry: RawSetup): SetupEntity {
  return {
    type: entry.name,
    description: extractDescription(entry.info),
    hasLabel: entry.data.length > 0,
    params: entry.params.map(convertParam),
    example: extractSetupExample(entry.info),
  };
}

function convertAction(entry: RawAction): Action {
  return {
    name: entry.name,
    description: extractDescription(entry.info),
    signature: extractSignature(entry.info),
    supportsVoiceSwitcher: entry.target_types.includes("PAGE"),
    targetTypes: [...entry.target_types],
    keywordParams: entry.params.map(convertParam),
    examples: extractActionExamples(entry.info),
  };
}

function convertParam(raw: RawParam): Param {
  const values = deriveValues(raw);
  const isList = raw.type.startsWith("vec<");

  const base = {
    name: raw.name,
    required: !raw.optional,
    description: raw.info,
  };

  if (values !== undefined && isList) {
    return { ...base, values, isList: true };
  }
  if (values !== undefined) {
    return { ...base, values };
  }
  if (isList) {
    return { ...base, isList: true };
  }
  return base;
}

function deriveValues(raw: RawParam): readonly string[] | undefined {
  if (raw.type === "bool") {
    return ["false", "true"];
  }
  // Only treat values_json as an exhaustive enum for pure string params. Mixed
  // types like `string|APP` list well-known shortcuts but also accept labels,
  // so the enum check would reject valid label arguments.
  if (raw.type === "string" && raw.values_json.length > 0) {
    try {
      const parsed: unknown = JSON.parse(raw.values_json);
      if (parsed !== null && typeof parsed === "object") {
        const values = Object.values(parsed as Record<string, unknown>).filter(
          (v): v is string => typeof v === "string",
        );
        if (values.length > 0) return values;
      }
    } catch {
      // Malformed values_json — fall through.
    }
  }
  return undefined;
}

// ── Info markdown extraction ────────────────────────────────────────────────

function extractDescription(info: string): string {
  const headingIdx = info.search(/\n###\s/);
  const head = headingIdx === -1 ? info : info.slice(0, headingIdx);
  return head.trim();
}

function extractSignature(info: string): string {
  const match = /###\s+SIGNATURE\s*\n+```[^\n]*\n([\s\S]*?)\n```/.exec(info);
  if (match === null) return "";
  return match[1]?.trim() ?? "";
}

function extractActionExamples(info: string): readonly string[] {
  const examples: string[] = [];
  const sectionRegex = /###\s+EXAMPLE[^\n]*\n([\s\S]*?)(?=\n###\s|$)/g;
  let section: RegExpExecArray | null;
  while ((section = sectionRegex.exec(info)) !== null) {
    const body = section[1] ?? "";
    const codeRegex = /```[^\n]*\n([\s\S]*?)\n```/g;
    let code: RegExpExecArray | null;
    while ((code = codeRegex.exec(body)) !== null) {
      const example = (code[1] ?? "").trim();
      if (example.length > 0) examples.push(example);
    }
  }
  return examples;
}

function extractSetupExample(info: string): string {
  const codeRegex = /```[^\n]*\n([\s\S]*?)\n```/g;
  let match: RegExpExecArray | null;
  while ((match = codeRegex.exec(info)) !== null) {
    const content = (match[1] ?? "").trim();
    if (content.includes("[setup]")) {
      return content.replace(/\n\s+/g, " ").trim();
    }
  }
  return "";
}
