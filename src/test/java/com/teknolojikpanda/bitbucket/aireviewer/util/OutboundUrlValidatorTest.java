package com.teknolojikpanda.bitbucket.aireviewer.util;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OutboundUrlValidatorTest {

    @Test
    public void rejectsLocalhostAlias() {
        OutboundUrlValidator.ValidationResult result =
                OutboundUrlValidator.validateHttpUrl("http://localhost:11434");

        assertFalse(result.isAllowed());
    }

    @Test
    public void rejectsPrivateAddress() {
        OutboundUrlValidator.ValidationResult result =
                OutboundUrlValidator.validateHttpUrl("http://10.0.0.5:11434");

        assertFalse(result.isAllowed());
    }

    @Test
    public void allowsPublicAddress() {
        OutboundUrlValidator.ValidationResult result =
                OutboundUrlValidator.validateHttpUrl("https://8.8.8.8:443");

        assertTrue(result.isAllowed());
    }

    @Test
    public void enforcesHostAllowlist() {
        OutboundUrlValidator.ValidationResult denied =
                OutboundUrlValidator.validateHttpUrl("https://8.8.8.8", List.of("example.com"));
        OutboundUrlValidator.ValidationResult allowed =
                OutboundUrlValidator.validateHttpUrl("https://8.8.8.8", List.of("8.8.8.8"));

        assertFalse(denied.isAllowed());
        assertTrue(allowed.isAllowed());
    }
}