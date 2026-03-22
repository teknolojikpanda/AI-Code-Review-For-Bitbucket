package com.teknolojikpanda.bitbucket.aireviewer.util;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OutboundUrlValidatorTest {

    private static final String ALLOW_LOCAL_TARGETS_PROPERTY = "ai.reviewer.outbound.allowLocalTargets";

    @Test
    public void rejectsLocalhostAlias() {
        System.clearProperty(ALLOW_LOCAL_TARGETS_PROPERTY);
        OutboundUrlValidator.ValidationResult result =
                OutboundUrlValidator.validateHttpUrl("http://localhost:11434");

        assertFalse(result.isAllowed());
    }

    @Test
    public void rejectsPrivateAddress() {
        System.clearProperty(ALLOW_LOCAL_TARGETS_PROPERTY);
        OutboundUrlValidator.ValidationResult result =
                OutboundUrlValidator.validateHttpUrl("http://10.0.0.5:11434");

        assertFalse(result.isAllowed());
    }

    @Test
    public void allowsPublicAddress() {
        System.clearProperty(ALLOW_LOCAL_TARGETS_PROPERTY);
        OutboundUrlValidator.ValidationResult result =
                OutboundUrlValidator.validateHttpUrl("https://8.8.8.8:443");

        assertTrue(result.isAllowed());
    }

    @Test
    public void enforcesHostAllowlist() {
        System.clearProperty(ALLOW_LOCAL_TARGETS_PROPERTY);
        OutboundUrlValidator.ValidationResult denied =
                OutboundUrlValidator.validateHttpUrl("https://8.8.8.8", List.of("example.com"));
        OutboundUrlValidator.ValidationResult allowed =
                OutboundUrlValidator.validateHttpUrl("https://8.8.8.8", List.of("8.8.8.8"));

        assertFalse(denied.isAllowed());
        assertTrue(allowed.isAllowed());
    }

    @Test
    public void allowsLocalTargetsWhenLocalOverrideEnabled() {
        System.setProperty(ALLOW_LOCAL_TARGETS_PROPERTY, "true");
        try {
            OutboundUrlValidator.ValidationResult localhost =
                    OutboundUrlValidator.validateHttpUrl("http://localhost:11434");
            OutboundUrlValidator.ValidationResult loopbackIp =
                    OutboundUrlValidator.validateHttpUrl("http://127.0.0.1:11434");

            assertTrue(localhost.isAllowed());
            assertTrue(loopbackIp.isAllowed());
        } finally {
            System.clearProperty(ALLOW_LOCAL_TARGETS_PROPERTY);
        }
    }
}