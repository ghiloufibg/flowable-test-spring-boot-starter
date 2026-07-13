package com.flowabletest.autoconfigure.guard;

/** Minimal major.minor.patch version comparison, deliberately dependency-free. */
final class FlowableVersions {

  private static final int VERSION_SEGMENTS = 3;

  private FlowableVersions() {}

  static boolean isSupported(String version, String minInclusive, String maxExclusive) {
    final int[] v = parse(version);
    return compare(v, parse(minInclusive)) >= 0 && compare(v, parse(maxExclusive)) < 0;
  }

  private static int[] parse(String version) {
    final String numericPart = version.split("-")[0];
    final String[] parts = numericPart.split("\\.");
    final int[] result = new int[VERSION_SEGMENTS];
    for (int i = 0; i < VERSION_SEGMENTS && i < parts.length; i++) {
      result[i] = parseIntSafely(parts[i]);
    }
    return result;
  }

  private static int parseIntSafely(String segment) {
    final String digitsOnly = segment.replaceAll("[^0-9].*$", "");
    if (digitsOnly.isEmpty()) {
      return 0;
    }
    try {
      return Integer.parseInt(digitsOnly);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static int compare(int[] a, int[] b) {
    for (int i = 0; i < VERSION_SEGMENTS; i++) {
      final int cmp = Integer.compare(a[i], b[i]);
      if (cmp != 0) {
        return cmp;
      }
    }
    return 0;
  }
}
