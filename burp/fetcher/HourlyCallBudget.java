/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.fetcher;

import java.util.concurrent.TimeUnit;

/**
 * A rolling-hour ceiling on outbound calls, kept below the endpoint's own hourly limit so a long
 * browsing session cannot spend the researcher's whole quota on background lookups.
 *
 * <p>The window resets wholesale rather than sliding: coarse, but it only ever undercounts against
 * a true sliding window, which is the safe direction.
 */
final class HourlyCallBudget {

  private static final long WINDOW_MILLIS = TimeUnit.HOURS.toMillis(1);

  private final int maxCalls;
  private long windowStartMillis;
  private int used;

  HourlyCallBudget(int maxCalls) {
    this.maxCalls = maxCalls;
    this.windowStartMillis = System.currentTimeMillis();
  }

  synchronized boolean tryAcquire() {
    rollWindow();
    if (used >= maxCalls) {
      return false;
    }
    used++;
    return true;
  }

  /**
   * Hands back a slot taken for a call that never reached the server. Not rolled first: if the
   * window turned over in between, the slot has already been forgiven.
   */
  synchronized void release() {
    if (used > 0) {
      used--;
    }
  }

  synchronized int remaining() {
    rollWindow();
    return Math.max(0, maxCalls - used);
  }

  private void rollWindow() {
    long now = System.currentTimeMillis();
    if (now - windowStartMillis >= WINDOW_MILLIS) {
      windowStartMillis = now;
      used = 0;
    }
  }
}
