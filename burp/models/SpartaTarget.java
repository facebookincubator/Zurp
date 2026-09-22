/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.models;

/** Something the SPARTA finding API can be asked about. */
public final class SpartaTarget {

  /** Wire values must match the Hack {@code SpartaBountyLeadTargetType} enum. */
  public enum Type {
    PUBLISHED_DOC_ID("published_doc_id"),
    ENDPOINT_NAME("endpoint_name");

    private final String wireValue;

    Type(String wireValue) {
      this.wireValue = wireValue;
    }

    public String wireValue() {
      return wireValue;
    }

    public static Type fromWireValue(String value) {
      for (Type type : values()) {
        if (type.wireValue.equals(value)) {
          return type;
        }
      }
      return null;
    }
  }

  private static final String SEPARATOR = "|";

  public final Type type;
  public final String id;

  private SpartaTarget(Type type, String id) {
    this.type = type;
    this.id = id;
  }

  public static SpartaTarget publishedDocId(String docId) {
    return new SpartaTarget(Type.PUBLISHED_DOC_ID, docId);
  }

  public static SpartaTarget endpointName(String name) {
    return new SpartaTarget(Type.ENDPOINT_NAME, name);
  }

  /**
   * Queue and cache key. The type is part of it so a doc id and an endpoint name cannot collide.
   */
  public String key() {
    return type.wireValue() + SEPARATOR + id;
  }

  /** Null for a key written by an older build, or hand-edited in the project file. */
  public static SpartaTarget fromKey(String key) {
    if (key == null) {
      return null;
    }
    int separator = key.indexOf(SEPARATOR);
    if (separator <= 0 || separator == key.length() - 1) {
      return null;
    }
    Type type = Type.fromWireValue(key.substring(0, separator));
    return type == null ? null : new SpartaTarget(type, key.substring(separator + 1));
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof SpartaTarget && key().equals(((SpartaTarget) other).key());
  }

  @Override
  public int hashCode() {
    return key().hashCode();
  }

  @Override
  public String toString() {
    return key();
  }
}
