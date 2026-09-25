/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import type {Catalog, Finding, SpartaTarget} from './findings.js';
import type {Poc} from './poc.js';
import {buildPoc} from './poc.js';

const INCOMPLETE =
  'The catalog sweep did not finish, so this is a partial view: a target listed with ' +
  'nothing may still have findings.';

/** Unix seconds is what the endpoint sends; a millisecond value is accepted rather than misread. */
function formatDate(seconds: number): string {
  const ms = seconds > 1e12 ? seconds : seconds * 1000;
  return new Date(ms).toISOString().slice(0, 10);
}

function describeTarget(target: SpartaTarget | undefined): string {
  if (target === undefined) {
    return 'target not named';
  }
  return target.type === 'published_doc_id'
    ? `doc_id=${target.id}`
    : `operation ${target.id}`;
}

function priorityTag(finding: Finding): string {
  return finding.priority === '' ? '' : `[${finding.priority}] `;
}

/** One line of prose, so a long summary does not swamp a list of forty findings. */
function firstSentence(summary: string, limit = 160): string {
  const flat = summary.replace(/\s+/g, ' ').trim();
  if (flat === '') {
    return '';
  }
  if (flat.length <= limit) {
    return flat;
  }
  return `${flat.slice(0, limit).trimEnd()}…`;
}

function renderEntry(finding: Finding): string[] {
  const lines = [`${priorityTag(finding)}${finding.id}  ${finding.title}`];
  const meta = [describeTarget(finding.target)];
  if (finding.publishedAt !== undefined) {
    meta.push(`disclosed ${formatDate(finding.publishedAt)}`);
  }
  if (buildPoc(finding) === undefined) {
    meta.push('no PoC');
  }
  lines.push(`  ${meta.join('  ·  ')}`);
  const summary = firstSentence(finding.summary);
  if (summary !== '') {
    lines.push(`  ${summary}`);
  }
  return lines;
}

export function formatCatalog(catalog: Catalog, options: {note?: string} = {}): string {
  const suffix = options.note === undefined ? '' : `\n\n${options.note}`;

  if (catalog.findings.length === 0) {
    const head = catalog.complete
      ? 'No SPARTA findings are disclosed to this account. That is a normal state — findings are disclosed selectively, and an empty catalog is not an error.'
      : `No findings retrieved. ${INCOMPLETE}`;
    return head + suffix;
  }

  const lines = [
    `${catalog.findings.length} SPARTA finding${catalog.findings.length === 1 ? '' : 's'} ` +
      `disclosed to this account${catalog.complete ? '' : ' so far'}.`,
    '',
  ];
  for (const finding of catalog.findings) {
    lines.push(...renderEntry(finding));
  }
  if (options.note !== undefined) {
    lines.push('', options.note);
  }
  if (!catalog.complete) {
    lines.push('', INCOMPLETE);
  }
  return lines.join('\n');
}

export function formatFinding(finding: Finding): string {
  const lines = [`${priorityTag(finding)}${finding.id}`, finding.title, ''];
  lines.push(`Raised against: ${describeTarget(finding.target)}`);
  if (finding.publishedAt !== undefined) {
    lines.push(`Disclosed: ${formatDate(finding.publishedAt)}`);
  }
  if (finding.summary !== '') {
    lines.push('', finding.summary.trim());
  }
  lines.push(
    '',
    buildPoc(finding) === undefined
      ? 'No proof of concept: this finding names neither a document id nor an operation to call.'
      : `Proof of concept available — sparta_build_poc({finding_id: "${finding.id}"}).`,
  );
  return lines.join('\n');
}

/** Renders whatever the API said each token wants, without insisting on a shape. */
function renderPlaceholderNotes(placeholdersJson: string): string[] {
  if (placeholdersJson.trim() === '') {
    return [];
  }
  try {
    const parsed: unknown = JSON.parse(placeholdersJson);
    if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) {
      const entries = Object.entries(parsed as Record<string, unknown>);
      if (entries.length > 0) {
        const width = Math.max(...entries.map(([key]) => key.length));
        return entries.map(([key, value]) => `  ${key.padEnd(width)}  ${String(value)}`);
      }
    }
  } catch {
    // Not JSON we recognise; the raw text is still what the API wanted to say.
  }
  return [`  ${placeholdersJson}`];
}

export function formatPoc(finding: Finding, poc: Poc): string {
  const lines = [
    `${poc.method} ${poc.url}`,
    `Content-Type: ${poc.contentType}`,
    '',
    poc.body,
    '',
  ];

  if (poc.placeholders.length > 0) {
    lines.push(
      `This reproduces nothing as written. Substitute ${poc.placeholders.length} ` +
        `placeholder${poc.placeholders.length === 1 ? '' : 's'} first: ` +
        poc.placeholders.map((name) => `{{${name}}}`).join(', '),
    );
    const notes = renderPlaceholderNotes(poc.placeholdersJson);
    if (notes.length > 0) {
      lines.push('', 'What each one wants:', ...notes);
    }
    lines.push(
      '',
      'Use identifiers from your own test environment — build one with the fbdl server ' +
        'if you do not have one. Do not fill these with identifiers belonging to real ' +
        'accounts or real objects.',
    );
  }

  lines.push('', `Finding: ${priorityTag(finding)}${finding.id} — ${finding.title}`);
  return lines.join('\n');
}

export interface TargetHit {
  readonly target: SpartaTarget;
  readonly findings: readonly Finding[];
}

export function formatScan(hits: readonly TargetHit[], complete: boolean): string {
  if (hits.length === 0) {
    return 'No SPARTA targets found in that text. The endpoint keys findings on persisted GraphQL document ids (`doc_id=…`) and operation shortnames (`fb_api_req_friendly_name=…`); traffic carrying neither cannot be looked up.';
  }

  const withFindings = hits.filter((hit) => hit.findings.length > 0);
  const total = withFindings.reduce((sum, hit) => sum + hit.findings.length, 0);

  const lines = [
    `Found ${hits.length} target${hits.length === 1 ? '' : 's'} in that traffic. ` +
      (total === 0
        ? 'None has a finding disclosed to you.'
        : `${total} finding${total === 1 ? '' : 's'} disclosed across ${withFindings.length} of them.`),
  ];

  for (const hit of withFindings) {
    lines.push('', describeTarget(hit.target));
    for (const finding of hit.findings) {
      lines.push(...renderEntry(finding).map((line) => `  ${line}`));
    }
  }

  if (!complete) {
    lines.push('', INCOMPLETE);
  }
  return lines.join('\n');
}
