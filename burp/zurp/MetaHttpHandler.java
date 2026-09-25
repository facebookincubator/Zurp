/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.zurp;

import static burp.api.montoya.http.handler.RequestToBeSentAction.continueWith;
import static burp.api.montoya.http.handler.ResponseReceivedAction.continueWith;

import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.handler.*;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.requests.MalformedRequestException;
import burp.csrf.CsrfRewriter;
import burp.csrf.CsrfScraper;
import burp.csrf.CsrfTokenStore;
import burp.fbdl.FbdlRewriter;
import burp.models.SpartaTarget;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;

class MetaHttpHandler implements HttpHandler {

  public MetaHttpHandler() {}

  @Override
  public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent requestToBeSent) {
    HttpRequest request = requestToBeSent;

    HttpRequest csrfRewritten = maybeRewriteCsrf(requestToBeSent);
    if (csrfRewritten != null) {
      request = csrfRewritten;
    }

    // After CSRF, and on whatever that produced: the two touch different placeholders, but a
    // request can carry both and the second pass must see the first pass's output.
    HttpRequest fbdlRewritten = maybeRewriteFbdl(request);
    if (fbdlRewritten != null) {
      request = fbdlRewritten;
    }

    return continueWith(request);
  }

  /** Returns null when the request should be forwarded untouched. */
  private HttpRequest maybeRewriteFbdl(HttpRequest request) {
    try {
      return FbdlRewriter.rewrite(request);
    } catch (Exception e) {
      // Same rule as the CSRF pass: this is on the request path for every proxied request, so an
      // unexpected request shape must not break the researcher's traffic.
      ZurpLog.caught("FBDL rewrite skipped", e);
      return null;
    }
  }

  /** Returns null when the request should be forwarded untouched. */
  private HttpRequest maybeRewriteCsrf(HttpRequestToBeSent requestToBeSent) {
    try {
      if (!ZurpUtils.isMetaUrl(requestToBeSent.url())) {
        return null;
      }

      ToolType tool = requestToBeSent.toolSource().toolType();
      boolean placeholders = ZurpUtils.isCsrfPlaceholderEnabled(tool);
      boolean autoRefresh = ZurpUtils.isCsrfAutoRefreshEnabled(tool);
      if (!placeholders && !autoRefresh) {
        return null;
      }

      // peek avoids creating a cache entry for a host we have never scraped. The account comes
      // from this request's own cookies, so a Repeater tab carrying the victim's session resolves
      // the victim's token without the researcher choosing anything.
      String account = CsrfTokenStore.accountFor(requestToBeSent);
      CsrfTokenStore.SessionTokens tokens =
          Zurp.csrfTokenStore.peek(requestToBeSent.httpService().host(), account);
      if (tokens == null || !tokens.hasAnyToken()) {
        // Deliberately no fall back to another account's token on the same host: that is the bug
        // this keying exists to stop, and it fails by succeeding as the wrong user.
        ZurpLog.debug(
            "No CSRF token held for account "
                + account
                + " on "
                + requestToBeSent.httpService().host()
                + "; leaving placeholders as written");
        return null;
      }

      HttpRequest rewritten =
          CsrfRewriter.rewrite(requestToBeSent, tokens, placeholders, autoRefresh);
      if (rewritten != null) {
        // Otherwise this is entirely silent, which is fine until a researcher is working out why a
        // token they altered on purpose keeps being accepted. Naming the account matters now that
        // the substitution picks one.
        ZurpLog.debug(
            "CSRF rewrite for account "
                + account
                + " on "
                + requestToBeSent.httpService().host()
                + " ("
                + (placeholders ? "placeholders" : "")
                + (placeholders && autoRefresh ? ", " : "")
                + (autoRefresh ? "auto-refresh" : "")
                + ")");
      }
      return rewritten;
    } catch (MalformedRequestException e) {
      // Burp raises this for any request it cannot parse a URL from. Routine on live traffic, and
      // such a request is not one we can rewrite anyway, so stay silent rather than fill the log.
      return null;
    } catch (Exception e) {
      // This runs on Burp's request path for every proxied request; never break the researcher's
      // traffic because of an unexpected request shape.
      ZurpLog.caught("CSRF rewrite skipped", e);
      return null;
    }
  }

  @Override
  public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived responseReceived) {
    // Zurp's own API calls come back through here, and their host matches isMetaUrl. Harvesting
    // ids out of a findings payload would queue those ids as fresh lookups, whose responses would
    // do it again.
    if (responseReceived.toolSource().toolType() == ToolType.EXTENSIONS) {
      return continueWith(responseReceived);
    }

    HttpRequest request = responseReceived.initiatingRequest();
    String url = request.url();
    if (ZurpUtils.isMetaUrl(url)) {
      Zurp.metaUrlInfoFetcher.addToQueue(url);
      HttpRequestResponse requestResponse =
          HttpRequestResponse.httpRequestResponse(request, responseReceived);
      String responseText = requestResponse.response().toString();

      scrapeCsrf(request, responseText);
      // Ahead of the object scan, so the doc ids it finds can be kept out of it: a doc id is 16-17
      // digits and matches FBID_PATTERN, so without this every GraphQL request spends a lookup
      // asking what its own doc id is as an object, and is told nothing.
      Set<String> docIds = queueSpartaTargets(request, url);

      Matcher matcherResponse = ZurpUtils.FBID_PATTERN.matcher(responseText);
      while (matcherResponse.find()) {
        queueObjectId(matcherResponse.group(), docIds);
      }

      Matcher matcherRequest = ZurpUtils.FBID_PATTERN.matcher(request.toString());
      while (matcherRequest.find()) {
        queueObjectId(matcherRequest.group(), docIds);
      }

      // Ad accounts are the primary object on the Ads and Business surfaces, and their ids are
      // written act_<digits> there, which the bare-number scan above cannot match. Queue the
      // digits: that is the FBID, and it is what the asset endpoint resolves.
      queueAdAccounts(responseText, docIds);
      queueAdAccounts(request.toString(), docIds);
    }

    return continueWith(responseReceived);
  }

  /**
   * Queues the FBID out of every {@code act_<digits>} in {@code text}. Separate from the bare
   * number scan because the prefix has to be dropped before the id is asked about.
   */
  private void queueAdAccounts(String text, Set<String> docIds) {
    Matcher matcher = ZurpUtils.AD_ACCOUNT_PATTERN.matcher(text);
    while (matcher.find()) {
      queueObjectId(matcher.group(1), docIds);
    }
  }

  /**
   * Queues one scraped number as an object id, unless it is one of the kinds the asset endpoint is
   * known not to resolve. Each skipped id is a lookup not spent, and one fewer permanent entry in
   * the failed set, which never shrinks.
   */
  private void queueObjectId(String candidate, Set<String> docIds) {
    // The endpoint documents both of these: an FBID is "never with a leading zero", and a doc id
    // has to be asked about as doc_id=<id>, which queueSpartaTargets already does.
    if (candidate.startsWith("0") || docIds.contains(candidate) || isMicrosecondClock(candidate)) {
      return;
    }
    Zurp.metaObjectInfoFetcher.addToQueue(candidate);
  }

  /** A microsecond reading of about now, which is 16 digits and so looks exactly like an FBID. */
  private static final int MICROSECOND_DIGITS = 16;

  /** How far from now a reading can be and still be a clock rather than an id. */
  private static final long CLOCK_WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000;

  /**
   * Whether this is a timestamp in microseconds rather than an id. Two thirds of what a page load
   * offers is these, and every one costs a lookup and then a permanent entry in the failed set.
   *
   * <p>Recognised by reading the leading digits as milliseconds and asking whether that lands near
   * now. Narrow on purpose: a real id would have to begin with the current epoch to the millisecond
   * to be caught by this, where a clock reading always does.
   */
  static boolean isMicrosecondClock(String candidate) {
    if (candidate.length() != MICROSECOND_DIGITS) {
      return false;
    }
    try {
      long asMillis = Long.parseLong(candidate.substring(0, 13));
      return Math.abs(asMillis - System.currentTimeMillis()) < CLOCK_WINDOW_MILLIS;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  /**
   * @return the published doc ids seen, which are not object ids however much they look like it.
   */
  private Set<String> queueSpartaTargets(HttpRequest request, String url) {
    Set<String> docIds = new HashSet<>();
    try {
      Set<SpartaTarget> targets = SpartaTargetExtractor.extract(url, request.bodyToString());
      for (SpartaTarget target : targets) {
        ZurpLog.debug("SPARTA target from " + url + ": " + target.key());
        if (target.type == SpartaTarget.Type.PUBLISHED_DOC_ID) {
          docIds.add(target.id);
        }
        Zurp.spartaFindingFetcher.addToQueue(target);
      }
      // The same two identifiers the asset endpoint can resolve, spelled the way it wants them.
      for (String query : MetaAssetQueries.fromTargets(targets)) {
        ZurpLog.debug("GraphQL asset from " + url + ": " + query);
        Zurp.metaGraphqlInfoFetcher.addToQueue(query);
      }
    } catch (Exception e) {
      // Runs on every proxied response; an unexpected body shape must not break the traffic.
      ZurpLog.caught("SPARTA target extraction skipped", e);
    }
    return docIds;
  }

  private void scrapeCsrf(HttpRequest request, String responseText) {
    try {
      String host = request.httpService().host();
      // The response was served to whoever the request was authenticated as, so its tokens belong
      // to that account and not to the host at large.
      String account = CsrfTokenStore.accountFor(request);
      boolean firstSighting = Zurp.csrfTokenStore.peek(host, account) == null;
      String changed = CsrfScraper.scrape(Zurp.csrfTokenStore, host, account, responseText);
      if (changed == null) {
        return;
      }
      // While logged in, LSD.php hands out a fresh random lsd on every page load as double-submit
      // filler, so it churns constantly and says nothing. Report it only the first time.
      if (!firstSighting && changed.equals(CsrfTokenStore.LSD)) {
        return;
      }
      ZurpLog.output("CSRF tokens updated for " + account + " on " + host + ": " + changed);
    } catch (Exception e) {
      ZurpLog.caught("CSRF scrape skipped", e);
    }
  }
}
