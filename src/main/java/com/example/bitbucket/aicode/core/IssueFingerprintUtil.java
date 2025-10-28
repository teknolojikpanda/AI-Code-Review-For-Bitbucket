package com.example.bitbucket.aicode.core;

import com.example.bitbucket.aireviewer.dto.ReviewIssue;

import javax.annotation.Nonnull;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;

/**
 * Generates deterministic fingerprints for review issues to detect duplicates.
 */
public final class IssueFingerprintUtil {

    private IssueFingerprintUtil() {
    }

    @Nonnull
    public static String fingerprint(@Nonnull ReviewIssue issue) {
        Objects.requireNonNull(issue, "issue");
        String normalizedPath = issue.getPath().replace('\\', '/');
        String range = issue.getLineRangeDisplay();
        String summary = issue.getSummary();
        String category = issue.getType().toLowerCase(Locale.ENGLISH);
        String severity = issue.getSeverity().getValue();
        String payload = String.join("|", normalizedPath, range, severity, category, summary).toLowerCase(Locale.ENGLISH);
        return sha256(payload);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
