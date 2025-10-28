# Duplicate Fingerprint Store Migration Plan

## Goal
Persist AI issue fingerprints between review runs so we can suppress re-posting comments that already exist. This aligns with Phase 1 backlog item **F3** and prepares the ground for Phase 3 observability work.

## Data Model
Introduce an Active Objects entity `ReviewIssueFingerprint`:

| Field | Type | Notes |
|-------|------|-------|
| `ID` | AUTO | Primary key. |
| `PULL_REQUEST_ID` | long | PR identifier (`PullRequest#getId`). |
| `REPOSITORY_ID` | long | Repository id (for faster lookups + cleanup). |
| `FINGERPRINT` | String (SHA-256, base64 - 44 chars) | Unique constraint with PR + repo. |
| `SEVERITY` | String (enum) | Stored for diagnostics/reporting. |
| `CATEGORY` | String | Category/type. |
| `SUMMARY_HASH` | Integer | Optional hash of summary for debugging/analytics. |
| `CREATED_AT` | long | Timestamp when fingerprint persisted. |
| `LAST_SEEN_AT` | long | Timestamp when fingerprint matched again (for decay/cleanup). |

Indexes/constraints:
- Unique compound index: `(repositoryId, pullRequestId, fingerprint)`.
- Secondary index on `repositoryId` for bulk cleanup when a repository is removed.

## Migration Steps
1. **Entity Definition**: Add `ReviewIssueFingerprint` interface under `com.example.bitbucket.aireviewer.ao`.
2. **Upgrade Task**:
   - Create `DuplicateFingerprintUpgradeTask implements PluginUpgradeTask`.
   - Register across versions (from current → next schema version).
   - No backfill required (new table); upgrade just ensures table creation.
3. **Service Layer**:
   - Add `FingerprintRepository` (Active Objects wrapper) to store, query, and update `LAST_SEEN_AT`.
   - Provide purge method to remove stale entries (e.g., older than 90 days).
4. **Integration**:
   - During review, query repository for fingerprints for the current PR.
   - Skip comments/finding if fingerprint exists; update `LAST_SEEN_AT`.
   - After posting new issues, store their fingerprints.
5. **Cleanup Strategy**:
   - Background job (optional) or scheduled maintenance to purge stale fingerprints.
   - On PR merge/close, optionally prune fingerprints for that PR.

## Testing Strategy
1. **Unit Tests**: Service-layer tests using AO test harness (upgrade task executed in test container).
2. **Integration Test**: Simulate two review runs on the same PR; assert second run skips duplicates.
3. **Upgrade Test**: Verify plugin starts cleanly on instances with prior schema (no table), upgrade task runs, and AO schema contains new table.

## Risks & Mitigations
| Risk | Mitigation |
|------|------------|
| Schema upgrade failure on startup | Follow AO migration best practices, include defensive logging. |
| Table growth over time | Implement TTL-based purge using `LAST_SEEN_AT`. |
| Race conditions when multiple reviews run concurrently | Keep fingerprint operations within review transaction or use AO’s transaction template. |
| Schema backward compatibility | Guard code paths to handle missing table gracefully (feature flag) until upgrade confirmed. |

## Follow-up Tasks
1. Define `ReviewIssueFingerprint` AO entity + upgrade task.
2. Implement repository/service APIs for storing and querying fingerprints.
3. Integrate with review orchestration and add tests.
4. Add admin documentation describing duplicate suppression behaviour and retention.
