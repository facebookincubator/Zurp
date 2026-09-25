/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.fetcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The budget stands in for the researcher's server-side hourly limit, so only calls that actually
 * reached the server may consume it.
 */
public class HourlyCallBudgetTest {

  @Test
  public void testEachCallSpendsOne() {
    HourlyCallBudget budget = new HourlyCallBudget(3);
    assertEquals(3, budget.remaining());
    assertTrue(budget.tryAcquire());
    assertEquals(2, budget.remaining());
  }

  @Test
  public void testASpentBudgetRefuses() {
    HourlyCallBudget budget = new HourlyCallBudget(2);
    assertTrue(budget.tryAcquire());
    assertTrue(budget.tryAcquire());
    assertFalse(budget.tryAcquire());
    assertEquals(0, budget.remaining());
  }

  /** The unset-token case: nothing reached the server, so the hour is not poorer for it. */
  @Test
  public void testAReleasedSlotIsSpendableAgain() {
    HourlyCallBudget budget = new HourlyCallBudget(1);
    assertTrue(budget.tryAcquire());
    assertFalse(budget.tryAcquire());
    budget.release();
    assertEquals(1, budget.remaining());
    assertTrue(budget.tryAcquire());
  }

  /** A release for a slot the rolled window already forgave must not mint a new one. */
  @Test
  public void testReleasingMoreThanWasTakenAddsNothing() {
    HourlyCallBudget budget = new HourlyCallBudget(2);
    budget.release();
    budget.release();
    assertEquals(2, budget.remaining());
  }
}
