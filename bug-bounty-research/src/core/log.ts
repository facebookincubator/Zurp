/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

/**
 * Logging for a stdio MCP server. stdout carries the JSON-RPC framing, so every
 * diagnostic goes to stderr — a stray `console.log` corrupts the stream and the client
 * reports it as a protocol error rather than as output from here.
 */

export type LogLevel = 'silent' | 'error' | 'warn' | 'info' | 'debug';

const RANK: Record<LogLevel, number> = {silent: 0, error: 1, warn: 2, info: 3, debug: 4};

export const LOG_LEVELS = Object.keys(RANK) as readonly LogLevel[];

export function parseLogLevel(raw: string | undefined, fallback: LogLevel): LogLevel {
  const candidate = raw?.trim().toLowerCase();
  return candidate !== undefined && candidate in RANK ? (candidate as LogLevel) : fallback;
}

/**
 * Blanks anything shaped like a credential. Applied to log lines *and* to error messages,
 * because an MCP error surfaces in the agent's transcript — a token echoed there outlives
 * the process in ways a researcher will not think to clean up.
 */
export function redact(text: string, ...secrets: readonly string[]): string {
  let out = text;
  for (const secret of secrets) {
    // Short values are too collision-prone to blank safely; a real token is far longer.
    if (secret.length >= 8) {
      out = out.split(secret).join('<redacted>');
    }
  }
  return out
    .replace(/\b(Bearer)\s+[\w.~+/-]+=*/gi, '$1 <redacted>')
    .replace(/\b(access_token|client_secret|api_key|token)=[^&\s"']+/gi, '$1=<redacted>')
    .replace(/\bEAA[\w-]{12,}/g, '<redacted>');
}

export interface Logger {
  error(message: string): void;
  warn(message: string): void;
  info(message: string): void;
  debug(message: string): void;
}

export interface LoggerOptions {
  readonly level: LogLevel;
  /** Tags each line, so two servers sharing a terminal stay tellable apart. */
  readonly name: string;
  /** Blanked wherever it appears in a log line. */
  readonly secrets?: readonly string[];
  readonly write?: (chunk: string) => void;
  readonly now?: () => Date;
}

export function createLogger(options: LoggerOptions): Logger {
  const {level, name, secrets = [], now = () => new Date()} = options;
  const write = options.write ?? ((chunk: string) => void process.stderr.write(chunk));
  const threshold = RANK[level];

  const emit = (lineLevel: Exclude<LogLevel, 'silent'>, message: string): void => {
    if (RANK[lineLevel] > threshold) {
      return;
    }
    write(`[${name}] ${now().toISOString()} ${lineLevel} ${redact(message, ...secrets)}\n`);
  };

  return {
    error: (message) => emit('error', message),
    warn: (message) => emit('warn', message),
    info: (message) => emit('info', message),
    debug: (message) => emit('debug', message),
  };
}

export const silentLogger: Logger = {
  error: () => {},
  warn: () => {},
  info: () => {},
  debug: () => {},
};
