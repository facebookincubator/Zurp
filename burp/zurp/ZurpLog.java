/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.zurp;

/**
 * The one gate every Zurp log line passes through. Silent unless asked: Burp's Logger belongs to
 * the researcher's own traffic, and an extension narrating itself into it uninvited is noise.
 */
public final class ZurpLog {

  public enum Level {
    /** Nothing reaches Burp at all. */
    NONE,
    /** What Zurp is doing: a line per fetch, per tick, per failure. */
    VERBOSE,
    /** VERBOSE, plus per-item detail and caught exceptions with their stack traces. */
    DEBUG;

    /** Preferences hold scalars only, so the level round-trips as its name. */
    static Level parse(String stored, Level fallback) {
      if (stored == null) {
        return fallback;
      }
      for (Level level : values()) {
        if (level.name().equalsIgnoreCase(stored.trim())) {
          return level;
        }
      }
      return fallback;
    }
  }

  /**
   * Held here rather than read from Preferences per call: at NONE every log line in the extension
   * still reaches this class, and a preference read costs a great deal more than a field read.
   */
  private static volatile Level level = ZurpPrefEnum.LOG_LEVEL_DEFAULT;

  private ZurpLog() {}

  public static Level level() {
    return level;
  }

  public static void setLevel(Level newLevel) {
    level = newLevel;
  }

  /** For the few callers whose message costs real work to build. Most should just call debug. */
  public static boolean isDebug() {
    return level == Level.DEBUG;
  }

  public static void output(String message) {
    if (level != Level.NONE) {
      Zurp.logger.logToOutput(message);
    }
  }

  public static void error(String message) {
    if (level != Level.NONE) {
      Zurp.logger.logToError(message);
    }
  }

  /** DEBUG only, so callers may say things that would be too noisy to print on every run. */
  public static void debug(String message) {
    if (level == Level.DEBUG) {
      Zurp.logger.logToOutput("[debug] " + message);
    }
  }

  /**
   * Every exception Zurp swallows comes through here. Zurp sits on the researcher's traffic path,
   * so none of them may propagate; at DEBUG they instead keep their stack trace and raise a Burp
   * error event, which surfaces on the Dashboard without interrupting anything.
   */
  public static void caught(String context, Throwable throwable) {
    if (level == Level.NONE) {
      return;
    }
    if (level != Level.DEBUG) {
      Zurp.logger.logToError(context + ": " + throwable);
      return;
    }
    Zurp.logger.logToError(context, throwable);
    Zurp.logger.raiseErrorEvent(context + ": " + throwable);
  }
}
