package com.example.bitbucket.aicode.api;

import com.example.bitbucket.aicode.model.ReviewContext;
import com.example.bitbucket.aicode.model.ReviewFinding;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Persistence layer for storing previous review findings (Active Objects).
 */
public interface IssueRepository {

    @Nonnull
    List<ReviewFinding> loadFindings(@Nonnull ReviewContext context);

    void saveFindings(@Nonnull ReviewContext context, @Nonnull List<ReviewFinding> findings);

    void markResolved(@Nonnull ReviewContext context, @Nonnull List<ReviewFinding> resolved);
}
