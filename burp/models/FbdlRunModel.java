/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.models;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One FBDL run belonging to the researcher, as returned by /bug_bounty/fbdl_runs.
 *
 * <p>The list endpoint sends a summary and the get endpoint sends the whole run, so {@link
 * #results} and {@link #exceptionStack} are empty on anything that came from a list.
 */
public class FbdlRunModel {

  public final String id;
  public final FbdlRunStatus status;

  /** Unix seconds. */
  public final long creationTime;

  public final String runCode;
  public final String note;

  /** Label to value, in the order the server sent them. */
  public final Map<String, String> results;

  public final String exceptionStack;

  public FbdlRunModel(
      String id,
      FbdlRunStatus status,
      long creationTime,
      String runCode,
      String note,
      Map<String, String> results,
      String exceptionStack) {
    this.id = id;
    this.status = status;
    this.creationTime = creationTime;
    this.runCode = runCode;
    this.note = note;
    this.results = Collections.unmodifiableMap(new LinkedHashMap<>(results));
    this.exceptionStack = exceptionStack;
  }
}
