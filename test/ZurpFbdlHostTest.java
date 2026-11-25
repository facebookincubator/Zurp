/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.zurp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * FBDL placeholder substitution is confined to the Meta app properties a replay legitimately
 * targets. The set is deliberately broader than {@link ZurpUtils#isMetaUrl} (facebook.com|meta.com)
 * because FBDL test environments are replayed against Instagram, WhatsApp, Messenger and Threads
 * too, and must still reject a look-alike host.
 */
public class ZurpFbdlHostTest {

  @Test
  public void substitutesForTheInScopeAppProperties() {
    assertTrue(ZurpUtils.isFbdlSubstitutionHost("https://www.facebook.com/api/graphql/"));
    assertTrue(ZurpUtils.isFbdlSubstitutionHost("https://m.facebook.com/x"));
    assertTrue(ZurpUtils.isFbdlSubstitutionHost("https://www.instagram.com/api/v1/"));
    assertTrue(ZurpUtils.isFbdlSubstitutionHost("https://web.whatsapp.com/send"));
    assertTrue(ZurpUtils.isFbdlSubstitutionHost("https://www.messenger.com/t/1"));
    assertTrue(ZurpUtils.isFbdlSubstitutionHost("https://www.threads.com/@x"));
    assertTrue(ZurpUtils.isFbdlSubstitutionHost("https://www.threads.net/@x"));
    assertTrue(ZurpUtils.isFbdlSubstitutionHost("https://meta.ai/"));
  }

  @Test
  public void skipsNonMetaAndLookAlikeHosts() {
    assertFalse(ZurpUtils.isFbdlSubstitutionHost("https://attacker.example/collect"));
    assertFalse(ZurpUtils.isFbdlSubstitutionHost("https://evil.test/x"));
    // The base domain must be the registrable suffix, not a label in an attacker domain.
    assertFalse(ZurpUtils.isFbdlSubstitutionHost("https://facebook.com.evil.test/x"));
    assertFalse(ZurpUtils.isFbdlSubstitutionHost("https://notfacebook.com/x"));
    assertFalse(ZurpUtils.isFbdlSubstitutionHost(null));
  }
}
