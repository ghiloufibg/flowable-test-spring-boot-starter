package com.flowabletest.autoconfigure.http;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * Resolves the set of HTTP mock services to start, either by scanning for immediate subdirectories
 * of the mocks root or by validating an explicitly declared list of service names against that same
 * convention. Pattern-matching the resource URL, rather than trying to list a directory, is what
 * makes the scan work identically whether resources are read from {@code target/test-classes} on
 * disk or packaged inside a jar.
 */
public final class HttpMockDiscovery {

  private final ResourcePatternResolver resourcePatternResolver;

  public HttpMockDiscovery(ResourcePatternResolver resourcePatternResolver) {
    this.resourcePatternResolver = resourcePatternResolver;
  }

  /**
   * @return service name -> classpath location (without a "classpath:" prefix), e.g.
   *     "payment-gateway" -> "httpmocks/payment-gateway"
   */
  public Map<String, String> discoverDefaultServices(String root) {
    final String rawRoot = stripClasspathPrefix(root);
    final Map<String, String> services = new LinkedHashMap<>();
    final Pattern serviceNamePattern =
        Pattern.compile(Pattern.quote(rawRoot) + "/([^/]+)/mappings/");

    try {
      final Resource[] resources =
          resourcePatternResolver.getResources("classpath*:" + rawRoot + "/*/mappings/**");
      for (final Resource resource : resources) {
        if (!resource.isReadable()) {
          continue;
        }
        final String url = resource.getURL().toString();
        final Matcher matcher = serviceNamePattern.matcher(url);
        if (matcher.find()) {
          final String name = matcher.group(1);
          services.putIfAbsent(name, rawRoot + "/" + name);
        }
      }
    } catch (final IOException e) {
      throw new IllegalStateException(
          "Failed to scan for HTTP mock service folders under '" + root + "'", e);
    }
    return Map.copyOf(services);
  }

  /**
   * Resolves an explicitly declared list of service names against the {@code root/<name>}
   * convention, instead of discovering the set of names by scanning the classpath. Unlike {@link
   * #discoverDefaultServices(String)}, this fails fast -- for every declared name, not just the
   * ones a scan happens to turn up -- when its {@code mappings} folder doesn't exist, so a missing
   * or misspelled service is caught before the {@code ApplicationContext} even starts refreshing,
   * with an error naming exactly which declared service is missing.
   *
   * @return service name -> classpath location, same shape as {@link #discoverDefaultServices}, in
   *     declaration order
   */
  public Map<String, String> resolveDeclaredServices(String root, List<String> declaredNames) {
    final String rawRoot = stripClasspathPrefix(root);
    final Map<String, String> services = new LinkedHashMap<>();
    for (final String name : declaredNames) {
      final String location = rawRoot + "/" + name;
      final Resource mappingsResource =
          resourcePatternResolver.getResource("classpath:" + location + "/mappings");
      if (!mappingsResource.exists()) {
        throw new IllegalStateException(
            "flowable.test.http-mocks.services declares '"
                + name
                + "' but no mappings folder was found at classpath:"
                + location
                + "/mappings -- check for a typo in `services`, or that the folder exists under "
                + "src/test/resources.");
      }
      services.put(name, location);
    }
    return Map.copyOf(services);
  }

  static String stripClasspathPrefix(String root) {
    String stripped = root;
    if (stripped.startsWith("classpath*:")) {
      stripped = stripped.substring("classpath*:".length());
    } else if (stripped.startsWith("classpath:")) {
      stripped = stripped.substring("classpath:".length());
    }
    while (stripped.startsWith("/")) {
      stripped = stripped.substring(1);
    }
    while (stripped.endsWith("/")) {
      stripped = stripped.substring(0, stripped.length() - 1);
    }
    return stripped;
  }
}
