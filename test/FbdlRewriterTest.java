/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.fbdl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Covers the placeholder grammar and the pin rewrite. Resolving a value reads the store, which is
 * Burp-provided, so that half is verified in Burp.
 */
public class FbdlRewriterTest {

  private static final String RUN = "1075679148163220";
  private static final String OTHER_RUN = "1075612345678901";

  @Test
  public void testPinNamesTheRun() {
    assertEquals("{{fbdl." + RUN + ".UserOne.uid}}", FbdlRewriter.pin("{{fbdl.UserOne.uid}}", RUN));
  }

  @Test
  public void testPinRepointsAnAlreadyPinnedPlaceholder() {
    // Pinning again is how a researcher moves a saved request onto a fresh run.
    assertEquals(
        "{{fbdl." + RUN + ".UserOne.uid}}",
        FbdlRewriter.pin("{{fbdl." + OTHER_RUN + ".UserOne.uid}}", RUN));
  }

  @Test
  public void testLabelsKeepTheirDots() {
    // UserOne.uid is one label, not a run and a field. Splitting on the first dot would break
    // every label researchers actually use.
    assertEquals(
        "{{fbdl." + RUN + ".UserOne.password}}",
        FbdlRewriter.pin("{{fbdl.UserOne.password}}", RUN));
  }

  @Test
  public void testEveryPlaceholderInTheSurfaceIsPinned() {
    assertEquals(
        "a={{fbdl." + RUN + ".UserOne.uid}}&b={{fbdl." + RUN + ".PageOne.id}}",
        FbdlRewriter.pin("a={{fbdl.UserOne.uid}}&b={{fbdl.PageOne.id}}", RUN));
  }

  @Test
  public void testWhitespaceInsideBracesIsTolerated() {
    assertEquals(
        "{{fbdl." + RUN + ".UserOne.uid}}", FbdlRewriter.pin("{{ fbdl.UserOne.uid }}", RUN));
  }

  @Test
  public void testCsrfPlaceholdersAreLeftAlone() {
    // Both rewriters run over the same request; neither may touch the other's placeholders.
    assertEquals("fb_dtsg={{fb_dtsg}}", FbdlRewriter.pin("fb_dtsg={{fb_dtsg}}", RUN));
  }

  @Test
  public void testSurfaceWithNoPlaceholderIsUntouched() {
    assertEquals("nothing here", FbdlRewriter.pin("nothing here", RUN));
    assertEquals("", FbdlRewriter.pin("", RUN));
  }

  @Test
  public void testUnpinnedPlaceholderIsNotSubstituted() {
    // Pin-only: without a run id there is no answer, and a visible {{fbdl...}} in the request is
    // easier to diagnose than a silently empty value.
    assertEquals("{{fbdl.UserOne.uid}}", FbdlRewriter.substitute("{{fbdl.UserOne.uid}}", false));
  }

  @Test
  public void testShortNumberIsALabelNotARunId() {
    // Below the bound a leading number is part of the label, so pinning must keep it rather than
    // treat it as a run already named.
    assertEquals("{{fbdl." + RUN + ".12345.uid}}", FbdlRewriter.pin("{{fbdl.12345.uid}}", RUN));
  }

  @Test
  public void testFifteenDigitRunIdIsRecognised() {
    // Real run ids are not a uniform length: 843467661384371 is 15 digits and 1075694828161652 is
    // 16. Both must read back as a run rather than as part of the label.
    String short15 = "843467661384371";
    assertEquals(
        "{{fbdl." + RUN + ".UserOne.uid}}",
        FbdlRewriter.pin("{{fbdl." + short15 + ".UserOne.uid}}", RUN));
    assertEquals(
        "{{fbdl." + short15 + ".UserOne.uid}}", FbdlRewriter.pin("{{fbdl.UserOne.uid}}", short15));
  }
}
