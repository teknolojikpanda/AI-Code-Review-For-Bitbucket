package com.example.bitbucket.aicode.core;

import com.example.bitbucket.aireviewer.dto.ReviewIssue;
import org.junit.Test;

import static org.junit.Assert.*;

public class IssueFingerprintUtilTest {

    @Test
    public void fingerprintsAreStableForSameIssue() {
        ReviewIssue issue = ReviewIssue.builder()
                .path("src/Main.java")
                .lineRange(42, 45)
                .summary("Possible NPE")
                .type("bug")
                .severity(ReviewIssue.Severity.HIGH)
                .build();

        String fp1 = IssueFingerprintUtil.fingerprint(issue);
        String fp2 = IssueFingerprintUtil.fingerprint(issue);
        assertEquals(fp1, fp2);
    }

    @Test
    public void fingerprintsChangeWhenSummaryDiffers() {
        ReviewIssue issueA = ReviewIssue.builder()
                .path("src/Main.java")
                .lineRange(42, 45)
                .summary("Possible NPE")
                .type("bug")
                .severity(ReviewIssue.Severity.HIGH)
                .build();

        ReviewIssue issueB = ReviewIssue.builder()
                .path("src/Main.java")
                .lineRange(42, 45)
                .summary("Improper null check")
                .type("bug")
                .severity(ReviewIssue.Severity.HIGH)
                .build();

        String fpA = IssueFingerprintUtil.fingerprint(issueA);
        String fpB = IssueFingerprintUtil.fingerprint(issueB);
        assertNotEquals(fpA, fpB);
    }
}
