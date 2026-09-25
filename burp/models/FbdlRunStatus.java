/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.models;

/** Status values as the API sends them in {@code run_status}. */
public enum FbdlRunStatus {
  RUNNING("Running"),
  COMPLETED("Completed"),
  FAILED("Failed"),
  UNKNOWN("Unknown");

  private final String wireValue;

  FbdlRunStatus(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }

  /** {@link #UNKNOWN} rather than null for an unrecognised value, so a new status cannot throw. */
  public static FbdlRunStatus fromWireValue(String value) {
    for (FbdlRunStatus status : values()) {
      if (status.wireValue.equals(value)) {
        return status;
      }
    }
    return UNKNOWN;
  }

  /**
   * Whether the run has settled and is worth caching.
   *
   * <p>UNKNOWN is deliberately not terminal: the server uses it when it cannot say, which is a
   * reason to ask again rather than to store the answer. The fetcher bounds that on the run's age
   * so a run stuck this way is not polled forever.
   */
  public boolean isTerminal() {
    return this == COMPLETED || this == FAILED;
  }
}
