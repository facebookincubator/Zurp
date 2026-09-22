/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.models;

/** One SPARTA finding disclosed to the researcher, as returned by /bug_bounty/sparta_findings. */
public class SpartaFindingModel {

  public final String bbFindingId;
  public final String title;
  public final String summary;
  public final String priority;
  public final String targetId;
  public final String targetType;
  public final String pocDocId;
  public final String pocVariablesJson;
  public final String pocPlaceholdersJson;
  public final long publishedAt;

  public SpartaFindingModel(
      String bbFindingId,
      String title,
      String summary,
      String priority,
      String targetId,
      String targetType,
      String pocDocId,
      String pocVariablesJson,
      String pocPlaceholdersJson,
      long publishedAt) {
    this.bbFindingId = bbFindingId;
    this.title = title;
    this.summary = summary;
    this.priority = priority;
    this.targetId = targetId;
    this.targetType = targetType;
    this.pocDocId = pocDocId;
    this.pocVariablesJson = pocVariablesJson;
    this.pocPlaceholdersJson = pocPlaceholdersJson;
    this.publishedAt = publishedAt;
  }

  /** The target this finding was raised against, or null if the API sent an unknown target type. */
  public SpartaTarget target() {
    SpartaTarget.Type type = SpartaTarget.Type.fromWireValue(targetType);
    if (type == null || targetId == null || targetId.isEmpty()) {
      return null;
    }
    return type == SpartaTarget.Type.PUBLISHED_DOC_ID
        ? SpartaTarget.publishedDocId(targetId)
        : SpartaTarget.endpointName(targetId);
  }
}
