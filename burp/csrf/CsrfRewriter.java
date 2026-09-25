/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.csrf;

import burp.api.montoya.http.message.ContentType;
import burp.api.montoya.http.message.requests.HttpRequest;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes live CSRF tokens into an outgoing request and recomputes the accompanying sprinkle
 * checksum.
 *
 * <p>Two modes, independently selectable per tool: explicit {@code {{fb_dtsg}}} placeholders, and
 * auto-refresh of a token already present in a replayed request.
 *
 * <p>Rewrites operate on the query string and body as text rather than through
 * withUpdatedParameters, because the Montoya API does not specify whether parameter values are
 * inserted verbatim or percent-encoded, and a wrong guess would double-encode the token.
 */
public final class CsrfRewriter {

  /** Resolves to fb_dtsg_ag on a GET and fb_dtsg on anything else, falling back to lsd. */
  public static final String GENERIC_PLACEHOLDER = "csrf";

  private static final Pattern PLACEHOLDER =
      Pattern.compile("\\{\\{\\s*(fb_dtsg_ag|fb_dtsg|lsd|csrf)\\s*\\}\\}");

  private static final String[] TOKEN_NAMES = {
    CsrfTokenStore.FB_DTSG, CsrfTokenStore.FB_DTSG_AG, CsrfTokenStore.LSD
  };

  private static final String LSD_HEADER = "x-fb-lsd";

  // The sprinkle parameter name rotates by sitevar, so the pattern set cannot be fully static.
  private static final Map<String, Pattern> PARAM_PATTERNS = new ConcurrentHashMap<>();

  private CsrfRewriter() {}

  /** Returns null when nothing changed, so the caller can forward the original request. */
  public static HttpRequest rewrite(
      HttpRequest request,
      CsrfTokenStore.SessionTokens tokens,
      boolean placeholders,
      boolean autoRefresh) {
    if (tokens == null || (!placeholders && !autoRefresh)) {
      return null;
    }

    String method = request.method();
    String originalPath = request.path();
    String originalBody = request.bodyToString();
    String path = originalPath;
    String body = originalBody;

    // withPath is the only way to reach the query string, and the API does not state whether path()
    // includes it. If it does not, rewriting the path would silently drop the query, so verify that
    // path() round-trips before touching it.
    boolean pathIsSafeToRewrite = originalPath.indexOf('?') >= 0 || request.url().indexOf('?') < 0;

    // A JSON or multipart body takes the token verbatim; only a form-encoded body escapes it.
    boolean encodeBody = request.contentType() == ContentType.URL_ENCODED;

    if (placeholders) {
      if (pathIsSafeToRewrite) {
        path = substitutePlaceholders(path, tokens, method, true);
      }
      body = substitutePlaceholders(body, tokens, method, encodeBody);
    }

    if (autoRefresh) {
      for (String tokenName : TOKEN_NAMES) {
        String live = tokens.get(tokenName);
        if (live == null) {
          continue;
        }
        String encoded = urlEncode(live);
        if (pathIsSafeToRewrite) {
          String updatedPath = replaceParamValue(path, tokenName, encoded);
          if (updatedPath != null) {
            path = updatedPath;
          }
        }
        // Auto-refresh only matches a name=value pair, which implies a form-encoded surface.
        String updatedBody = replaceParamValue(body, tokenName, encoded);
        if (updatedBody != null) {
          body = updatedBody;
        }
      }
    }

    boolean tokenChanged = !path.equals(originalPath) || !body.equals(originalBody);

    // Only after substitution, and only when a token actually moved: the checksum covers whichever
    // token the request now carries, and an untouched request already has a matching one.
    if (tokenChanged) {
      String effectiveToken = effectiveToken(path, body);
      if (effectiveToken != null) {
        String checksum = tokens.checksumFor(effectiveToken);
        String paramName = tokens.sprinkleParamName();
        if (pathIsSafeToRewrite) {
          String updatedPath = replaceParamValue(path, paramName, checksum);
          if (updatedPath != null) {
            path = updatedPath;
          }
        }
        String updatedBody = replaceParamValue(body, paramName, checksum);
        if (updatedBody != null) {
          body = updatedBody;
        }
      }
    }

    HttpRequest result = null;
    if (!path.equals(originalPath)) {
      result = request.withPath(path);
    }
    if (!body.equals(originalBody)) {
      // withBody updates Content-Length.
      result = (result == null ? request : result).withBody(body);
    }

    HttpRequest withHeaders =
        rewriteHeaders(
            result == null ? request : result, tokens, method, placeholders, autoRefresh);
    if (withHeaders != null) {
      result = withHeaders;
    }

    return result;
  }

  private static HttpRequest rewriteHeaders(
      HttpRequest request,
      CsrfTokenStore.SessionTokens tokens,
      String method,
      boolean placeholders,
      boolean autoRefresh) {
    HttpRequest result = null;

    if (placeholders) {
      for (burp.api.montoya.http.message.HttpHeader header : request.headers()) {
        String value = header.value();
        if (value == null || value.indexOf("{{") < 0) {
          continue;
        }
        // Header values are not form-encoded, so the raw token goes in.
        String substituted = substitutePlaceholders(value, tokens, method, false);
        if (!substituted.equals(value)) {
          result =
              (result == null ? request : result).withUpdatedHeader(header.name(), substituted);
        }
      }
    }

    if (autoRefresh) {
      String lsd = tokens.get(CsrfTokenStore.LSD);
      HttpRequest current = result == null ? request : result;
      if (lsd != null && current.hasHeader(LSD_HEADER)) {
        String existing = current.headerValue(LSD_HEADER);
        if (!lsd.equals(existing)) {
          result = current.withUpdatedHeader(LSD_HEADER, lsd);
        }
      }
    }

    return result;
  }

  private static String substitutePlaceholders(
      String surface, CsrfTokenStore.SessionTokens tokens, String method, boolean encode) {
    if (surface == null || surface.isEmpty() || surface.indexOf("{{") < 0) {
      return surface;
    }

    Matcher matcher = PLACEHOLDER.matcher(surface);
    StringBuilder out = null;
    int last = 0;
    while (matcher.find()) {
      String requested = matcher.group(1);
      String token =
          GENERIC_PLACEHOLDER.equals(requested)
              ? selectByMethod(tokens, method)
              : tokens.get(requested);
      if (token == null) {
        // Leave the placeholder in place; a silently blank token is harder to debug.
        continue;
      }
      if (out == null) {
        out = new StringBuilder(surface.length() + 64);
      }
      out.append(surface, last, matcher.start());
      out.append(encode ? urlEncode(token) : token);
      last = matcher.end();
    }

    if (out == null) {
      return surface;
    }
    out.append(surface, last, surface.length());
    return out.toString();
  }

  private static String selectByMethod(CsrfTokenStore.SessionTokens tokens, String method) {
    String preferred =
        "GET".equalsIgnoreCase(method)
            ? tokens.get(CsrfTokenStore.FB_DTSG_AG)
            : tokens.get(CsrfTokenStore.FB_DTSG);
    if (preferred != null) {
      return preferred;
    }
    // Logged out, so no dtsg was ever issued.
    return tokens.get(CsrfTokenStore.LSD);
  }

  /** Mirrors the server-side precedence in Sprinkle.php: fb_dtsg, else fb_dtsg_ag, else lsd. */
  private static String effectiveToken(String path, String body) {
    for (String tokenName : TOKEN_NAMES) {
      String value = readParamValue(body, tokenName);
      if (value == null) {
        value = readParamValue(path, tokenName);
      }
      if (value != null && !value.isEmpty()) {
        // The server checksums the decoded value.
        return urlDecode(value);
      }
    }
    return null;
  }

  private static Matcher paramMatcher(String surface, String paramName) {
    if (surface == null || surface.isEmpty() || surface.indexOf(paramName) < 0) {
      return null;
    }
    Pattern pattern =
        PARAM_PATTERNS.computeIfAbsent(
            paramName, name -> Pattern.compile("(?:\\A|[?&])" + Pattern.quote(name) + "=([^&#]*)"));
    Matcher matcher = pattern.matcher(surface);
    return matcher.find() ? matcher : null;
  }

  private static String readParamValue(String surface, String paramName) {
    Matcher matcher = paramMatcher(surface, paramName);
    return matcher == null ? null : matcher.group(1);
  }

  /**
   * Returns null when the parameter is absent, to distinguish "no change" from "changed to same".
   */
  private static String replaceParamValue(String surface, String paramName, String newValue) {
    Matcher matcher = paramMatcher(surface, paramName);
    if (matcher == null) {
      return null;
    }
    return surface.substring(0, matcher.start(1)) + newValue + surface.substring(matcher.end(1));
  }

  private static String urlEncode(String value) {
    try {
      return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    } catch (UnsupportedEncodingException e) {
      return value;
    }
  }

  private static String urlDecode(String value) {
    try {
      return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
    } catch (UnsupportedEncodingException | IllegalArgumentException e) {
      // A malformed escape means the value is not a token we minted; checksum it as-is.
      return value;
    }
  }
}
