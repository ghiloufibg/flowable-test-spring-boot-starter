package com.flowabletest.autoconfigure.http;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans {@code classpath*:<root>/*<name>/mappings/**} for immediate subdirectories of the HTTP
 * mocks root and treats each as a declared external service (design doc section 4.3's
 * convention-over-configuration default). Pattern-matching the resource URL (rather than trying
 * to "list a directory") is what makes this work identically whether resources are read from
 * {@code target/test-classes} on disk or packaged inside a jar.
 */
public class HttpMockDiscovery {

    private final ResourcePatternResolver resourcePatternResolver;

    public HttpMockDiscovery(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    /** @return service name -> classpath location (without a "classpath:" prefix), e.g. "payment-gateway" -> "httpmocks/payment-gateway" */
    public Map<String, String> discoverDefaultServices(String root) {
        String rawRoot = stripClasspathPrefix(root);
        Map<String, String> services = new LinkedHashMap<>();
        Pattern serviceNamePattern = Pattern.compile(Pattern.quote(rawRoot) + "/([^/]+)/mappings/");

        try {
            Resource[] resources = resourcePatternResolver.getResources("classpath*:" + rawRoot + "/*/mappings/**");
            for (Resource resource : resources) {
                if (!resource.isReadable()) {
                    continue;
                }
                String url = resource.getURL().toString();
                Matcher matcher = serviceNamePattern.matcher(url);
                if (matcher.find()) {
                    String name = matcher.group(1);
                    services.putIfAbsent(name, rawRoot + "/" + name);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan for HTTP mock service folders under '" + root + "'", e);
        }
        return services;
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
