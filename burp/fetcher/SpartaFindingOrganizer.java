/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.fetcher;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.persistence.PersistedList;
import burp.api.montoya.persistence.PersistedObject;
import burp.models.SpartaFindingModel;
import burp.zurp.*;
import java.util.Collection;
import java.util.Set;

/**
 * Puts the proof of concept for each disclosed finding into Burp's Organizer, so the researcher
 * works it from their own queue alongside their traffic rather than only from the Zurp tab.
 *
 * <p>Once per finding for the life of the project file. The Organizer belongs to the researcher,
 * and one they have already triaged and deleted must not reappear the next time they browse the
 * endpoint it was raised against.
 */
class SpartaFindingOrganizer {

  private static final String KEY_ORGANIZED = "organized";

  private final PersistedObject fetcherData;

  /** Authoritative in memory; see {@link ZurpDataFetcher} for why a PersistedList is not. */
  private final Set<String> organized;

  /**
   * Cleared the first time the annotated call fails, so a Burp that will not take a request without
   * a response costs one exception rather than one per finding.
   */
  private volatile boolean annotationsSupported = true;

  SpartaFindingOrganizer(PersistedObject fetcherData) {
    this.fetcherData = fetcherData;
    this.organized = ZurpDataFetcher.toStringSet(fetcherData.getStringList(KEY_ORGANIZED));
  }

  /**
   * Sends the ones not sent before. Never throws: this runs on the fetch path, and an Organizer
   * that will not take a request is no reason to fail the fetch that produced it.
   */
  void publish(Collection<SpartaFindingModel> findings) {
    if (findings == null || findings.isEmpty() || !ZurpUtils.isOrganizerPushEnabled()) {
      return;
    }

    int sent = 0;
    boolean changed = false;
    for (SpartaFindingModel finding : findings) {
      if (finding == null || !isUnsent(finding.bbFindingId)) {
        continue;
      }
      try {
        if (send(finding)) {
          sent++;
        }
        // Marked even when there was no PoC to build, so a finding that cannot produce one is not
        // reconsidered every time the researcher touches its endpoint.
        changed |= mark(finding.bbFindingId);
      } catch (Exception e) {
        ZurpLog.caught("[SpartaFindingOrganizer] Could not send " + finding.bbFindingId, e);
      }
    }

    if (changed) {
      persist();
    }
    if (sent > 0) {
      ZurpLog.output("[SpartaFindingOrganizer] Sent " + sent + " finding(s) to the Organizer");
    }
  }

  private synchronized boolean isUnsent(String bbFindingId) {
    return bbFindingId != null && !bbFindingId.isEmpty() && !organized.contains(bbFindingId);
  }

  private synchronized boolean mark(String bbFindingId) {
    return organized.add(bbFindingId);
  }

  /** False when the finding describes no PoC that can be turned into a request. */
  private boolean send(SpartaFindingModel finding) {
    HttpRequest request = SpartaPocRequest.build(finding);
    if (request == null) {
      ZurpLog.debug("[SpartaFindingOrganizer] No PoC to send for " + finding.bbFindingId);
      return false;
    }

    if (annotationsSupported) {
      try {
        Zurp.organizer.sendToOrganizer(
            HttpRequestResponse.httpRequestResponse(
                request, null, SpartaPocRequest.annotations(finding)));
        return true;
      } catch (Exception e) {
        annotationsSupported = false;
        ZurpLog.caught("[SpartaFindingOrganizer] Sending without notes from here on", e);
      }
    }

    Zurp.organizer.sendToOrganizer(request);
    return true;
  }

  /** Rewritten whole rather than appended to: a PersistedList is a write sink. */
  private synchronized void persist() {
    PersistedList<String> list = PersistedList.persistedStringList();
    for (String bbFindingId : organized) {
      list.add(bbFindingId);
    }
    fetcherData.setStringList(KEY_ORGANIZED, list);
  }
}
