package com.teknolojikpanda.bitbucket.aireviewer.util;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Centralized outbound URL validation used to reduce SSRF risk.
 */
public final class OutboundUrlValidator {

    private static final String ALLOWED_HOSTS_PROPERTY = "ai.reviewer.outbound.allowedHosts";

    private OutboundUrlValidator() {
    }

    @Nonnull
    public static ValidationResult validateHttpUrl(@Nullable String url) {
        return validateHttpUrl(url, parseAllowedHosts(System.getProperty(ALLOWED_HOSTS_PROPERTY)));
    }

    @Nonnull
    public static ValidationResult validateHttpUrl(@Nullable String url,
                                                   @Nullable Collection<String> allowedHosts) {
        if (url == null || url.trim().isEmpty()) {
            return ValidationResult.invalid("URL is required");
        }

        final URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException ex) {
            return ValidationResult.invalid("Invalid URL format");
        }

        return validateHttpUri(uri, allowedHosts);
    }

    @Nonnull
    public static ValidationResult validateHttpUri(@Nullable URI uri,
                                                   @Nullable Collection<String> allowedHosts) {
        if (uri == null) {
            return ValidationResult.invalid("URL is required");
        }

        String scheme = normalize(uri.getScheme());
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return ValidationResult.invalid("URL must use http or https");
        }

        if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
            return ValidationResult.invalid("URL must not include user credentials");
        }

        String host = normalize(uri.getHost());
        if (host == null) {
            return ValidationResult.invalid("URL host is required");
        }

        if (isBlockedAlias(host)) {
            return ValidationResult.invalid("Host is not allowed for outbound connections");
        }

        List<String> normalizedAllowedHosts = normalizeAllowedHosts(allowedHosts);
        if (!normalizedAllowedHosts.isEmpty() && !isHostAllowed(host, normalizedAllowedHosts)) {
            return ValidationResult.invalid("Host is not present in outbound allowlist");
        }

        try {
            InetAddress[] resolved = InetAddress.getAllByName(host);
            if (resolved == null || resolved.length == 0) {
                return ValidationResult.invalid("Host does not resolve to an address");
            }
            for (InetAddress address : resolved) {
                if (isBlockedAddress(address)) {
                    return ValidationResult.invalid("Host resolves to a blocked network address");
                }
            }
        } catch (UnknownHostException ex) {
            return ValidationResult.invalid("Host resolution failed");
        }

        return ValidationResult.valid(uri);
    }

    @Nonnull
    public static List<String> parseAllowedHosts(@Nullable String propertyValue) {
        if (propertyValue == null || propertyValue.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String[] tokens = propertyValue.split(",");
        List<String> list = new ArrayList<>();
        for (String token : tokens) {
            String value = normalize(token);
            if (value != null) {
                list.add(value);
            }
        }
        return list;
    }

    private static List<String> normalizeAllowedHosts(@Nullable Collection<String> allowedHosts) {
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> normalized = new ArrayList<>(allowedHosts.size());
        for (String host : allowedHosts) {
            String value = normalize(host);
            if (value != null) {
                normalized.add(value);
            }
        }
        return normalized;
    }

    private static boolean isBlockedAlias(String host) {
        return "localhost".equals(host)
                || "localhost.localdomain".equals(host)
                || "host.docker.internal".equals(host)
                || "docker.for.mac.localhost".equals(host)
                || "docker.for.win.localhost".equals(host)
                || host.endsWith(".internal");
    }

    private static boolean isHostAllowed(String host, List<String> allowedHosts) {
        for (String allowed : allowedHosts) {
            if (allowed.startsWith("*.") && host.endsWith(allowed.substring(1))) {
                return true;
            }
            if (host.equals(allowed)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlockedAddress(InetAddress address) {
        if (address == null) {
            return true;
        }
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] raw = address.getAddress();
        if (address instanceof Inet4Address && raw != null && raw.length == 4) {
            int b0 = raw[0] & 0xFF;
            int b1 = raw[1] & 0xFF;

            // Carrier-grade NAT 100.64.0.0/10
            if (b0 == 100 && (b1 >= 64 && b1 <= 127)) {
                return true;
            }
            // Benchmark testing 198.18.0.0/15
            if (b0 == 198 && (b1 == 18 || b1 == 19)) {
                return true;
            }
            // Documentation ranges 192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24
            if ((b0 == 192 && b1 == 0 && (raw[2] & 0xFF) == 2)
                    || (b0 == 198 && b1 == 51 && (raw[2] & 0xFF) == 100)
                    || (b0 == 203 && b1 == 0 && (raw[2] & 0xFF) == 113)) {
                return true;
            }
        }

        if (address instanceof Inet6Address && raw != null && raw.length == 16) {
            int first = raw[0] & 0xFF;
            int second = raw[1] & 0xFF;
            // Unique local fc00::/7
            if ((first & 0xFE) == 0xFC) {
                return true;
            }
            // Link-local fe80::/10
            if (first == 0xFE && (second & 0xC0) == 0x80) {
                return true;
            }
        }

        return false;
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static final class ValidationResult {
        private final boolean allowed;
        private final String reason;
        private final URI uri;

        private ValidationResult(boolean allowed, String reason, URI uri) {
            this.allowed = allowed;
            this.reason = reason;
            this.uri = uri;
        }

        public static ValidationResult valid(URI uri) {
            return new ValidationResult(true, null, uri);
        }

        public static ValidationResult invalid(String reason) {
            return new ValidationResult(false, reason, null);
        }

        public boolean isAllowed() {
            return allowed;
        }

        @Nullable
        public String getReason() {
            return reason;
        }

        @Nullable
        public URI getUri() {
            return uri;
        }
    }
}