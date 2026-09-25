/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.csrf;

import burp.api.montoya.http.message.params.HttpParameterType;
import burp.api.montoya.http.message.params.ParsedHttpParameter;
import burp.api.montoya.http.message.requests.HttpRequest;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches the CSRF tokens most recently observed, per host and per account.
 *
 * <p>Tokens are site-bound: CSRFTokenSite mixes the site into the MAC plaintext, so a token scraped
 * from one host will not validate on another. They are also session-bound, which is the reason for
 * the second level: a researcher testing an IDOR is logged into a victim and an attacker at once,
 * and a store keyed on host alone hands whichever account they browsed last to both. That does not
 * fail loudly. It sends a request that succeeds as the wrong user.
 *
 * <p>The account comes from the request's own cookies, so nothing has to be chosen: a request
 * carrying the victim's cookies resolves the victim's token by construction.
 *
 * <p>The sprinkle configuration stays on the host rather than the account. It rotates by sitevar,
 * not by who is logged in, and duplicating it per account would make each newly seen account
 * compute a wrong checksum until it happened to observe the config again.
 *
 * <p>Deliberately in-memory only. These are live session credentials and must not reach the Burp
 * project file on disk, which rules out Zurp.extensionData.
 */
public class CsrfTokenStore {

  public static final String FB_DTSG = "fb_dtsg";
  public static final String FB_DTSG_AG = "fb_dtsg_ag";
  public static final String LSD = "lsd";

  /** Facebook's logged-in user id. Safe to key on and to show: it is an id, not a credential. */
  private static final String C_USER = "c_user";

  /**
   * Requests with no {@code c_user}: logged out, a host that does not use that cookie, or a tab the
   * researcher stripped cookies from. They share one bucket per host, which is what the store did
   * for everything before it knew about accounts.
   */
  public static final String ANONYMOUS = "anonymous";

  private static final String DEFAULT_SPRINKLE_PARAM = "jazoest";

  private final Map<String, HostEntry> byHost = new ConcurrentHashMap<>();

  /** The account a request is authenticated as, never null. */
  public static String accountFor(HttpRequest request) {
    if (request == null) {
      return ANONYMOUS;
    }
    ParsedHttpParameter cookie = request.parameter(C_USER, HttpParameterType.COOKIE);
    if (cookie == null) {
      return ANONYMOUS;
    }
    String value = cookie.value();
    return value == null || value.isEmpty() ? ANONYMOUS : value;
  }

  public HostEntry forHost(String host) {
    return byHost.computeIfAbsent(host, unused -> new HostEntry());
  }

  /** Null when the host has never been seen, so callers can avoid creating an empty entry. */
  public HostEntry peek(String host) {
    return byHost.get(host);
  }

  /** Null when nothing has been scraped for that account on that host. */
  public SessionTokens peek(String host, String account) {
    HostEntry entry = byHost.get(host);
    return entry == null ? null : entry.peekSession(account);
  }

  public void clear() {
    byHost.clear();
  }

  public int hostCount() {
    return byHost.size();
  }

  public Set<String> hosts() {
    return Collections.unmodifiableSet(byHost.keySet());
  }

  /** One host: its sitevar-driven sprinkle configuration, and a token set per account. */
  public static class HostEntry {
    private volatile String sprinkleParamName = DEFAULT_SPRINKLE_PARAM;
    private volatile int sprinkleVersion = SprinkleChecksum.DEFAULT_VERSION;
    private volatile boolean sprinkleRandomized;

    private final Map<String, SessionTokens> bySession = new ConcurrentHashMap<>();

    public SessionTokens forSession(String account) {
      return bySession.computeIfAbsent(account, unused -> new SessionTokens(this));
    }

    /** Null when this account has not been seen on this host. */
    public SessionTokens peekSession(String account) {
      return bySession.get(account);
    }

    public Set<String> accounts() {
      return Collections.unmodifiableSet(bySession.keySet());
    }

    public boolean putSprinkleConfig(String paramName, int version, boolean randomized) {
      boolean changed =
          version != sprinkleVersion
              || randomized != sprinkleRandomized
              || (paramName != null
                  && !paramName.isEmpty()
                  && !paramName.equals(sprinkleParamName));
      if (paramName != null && !paramName.isEmpty()) {
        sprinkleParamName = paramName;
      }
      sprinkleVersion = version;
      sprinkleRandomized = randomized;
      return changed;
    }

    /** Rotates via sitevar, so it is read from the SprinkleConfig payload rather than hardcoded. */
    public String sprinkleParamName() {
      return sprinkleParamName;
    }

    public int sprinkleVersion() {
      return sprinkleVersion;
    }

    public boolean sprinkleRandomized() {
      return sprinkleRandomized;
    }
  }

  /**
   * Tokens seen for one account on one host. Fields are updated independently because a given
   * response carries only some of them; last write wins, which tracks the researcher's own
   * browsing.
   */
  public static class SessionTokens {
    // The sprinkle config lives on the host, but the rewriter only ever holds one of these, so the
    // checksum is reachable from here rather than making every caller carry both.
    private final HostEntry host;

    private volatile String fbDtsg;
    private volatile String fbDtsgAg;
    private volatile String lsd;
    private volatile long lastSeenMillis;

    SessionTokens(HostEntry host) {
      this.host = host;
    }

    public String get(String tokenName) {
      switch (tokenName) {
        case FB_DTSG:
          return fbDtsg;
        case FB_DTSG_AG:
          return fbDtsgAg;
        case LSD:
          return lsd;
        default:
          return null;
      }
    }

    public boolean put(String tokenName, String value) {
      // DTSGInitData emits empty strings rather than omitting keys when logged out.
      if (value == null || value.isEmpty()) {
        return false;
      }
      switch (tokenName) {
        case FB_DTSG:
          if (value.equals(fbDtsg)) {
            return false;
          }
          fbDtsg = value;
          break;
        case FB_DTSG_AG:
          if (value.equals(fbDtsgAg)) {
            return false;
          }
          fbDtsgAg = value;
          break;
        case LSD:
          if (value.equals(lsd)) {
            return false;
          }
          lsd = value;
          break;
        default:
          return false;
      }
      lastSeenMillis = System.currentTimeMillis();
      return true;
    }

    public boolean hasAnyToken() {
      return fbDtsg != null || fbDtsgAg != null || lsd != null;
    }

    public long lastSeenMillis() {
      return lastSeenMillis;
    }

    public String sprinkleParamName() {
      return host.sprinkleParamName();
    }

    public String checksumFor(String token) {
      return host.sprinkleRandomized()
          ? SprinkleChecksum.computeWithoutVersion(token)
          : SprinkleChecksum.compute(token, host.sprinkleVersion());
    }
  }
}
