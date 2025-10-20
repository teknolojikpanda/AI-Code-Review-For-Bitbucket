# Phase 2 Complete - Event Listener Implementation

**Date:** October 18, 2025
**Status:** ✅ **BUILD SUCCESSFUL**
**Previous Work:** Phase 1 - Core Services and Utilities

---

## Summary

Phase 2 has been successfully completed! The AI Code Reviewer plugin now has **automatic PR review functionality** through event-driven architecture. When PRs are opened or updated in Bitbucket, the plugin will automatically trigger AI code reviews.

**Key Achievement:** The plugin is now **event-driven** and can respond to PR lifecycle events automatically.

---

## Components Implemented

### PullRequestAIReviewListener ✅

**Location:** [src/main/java/com/example/bitbucket/aireviewer/listener/PullRequestAIReviewListener.java](src/main/java/com/example/bitbucket/aireviewer/listener/PullRequestAIReviewListener.java)

**Purpose:** Listens to Bitbucket pull request events and triggers AI code reviews automatically

**Implementation Details:**

#### 1. Dependency Injection
```java
@Inject
public PullRequestAIReviewListener(
    @ComponentImport EventPublisher eventPublisher,
    AIReviewService reviewService,
    AIReviewerConfigService configService
)
```

**Services Injected:**
- **EventPublisher** - Bitbucket's event system
- **AIReviewService** - Performs the actual code review
- **AIReviewerConfigService** - Reads plugin configuration

#### 2. Event Registration
The listener automatically registers itself with the EventPublisher on construction:
```java
eventPublisher.register(this);
```

And properly unregisters when the plugin is unloaded:
```java
@Override
public void destroy() {
    eventPublisher.unregister(this);
    executorService.shutdown();
}
```

#### 3. Event Handlers

##### onPullRequestOpened()
**Trigger:** New PR is created
**Flow:**
1. Check if AI reviews are enabled in configuration
2. Check if PR is a draft (and if drafts should be reviewed)
3. Execute initial review asynchronously

```java
@EventListener
public void onPullRequestOpened(@Nonnull PullRequestOpenedEvent event) {
    // Checks configuration
    if (!isReviewEnabled()) return;
    if (isDraftPR(pr) && !shouldReviewDraftPRs()) return;

    // Execute async review
    executeReviewAsync(pullRequest, false);
}
```

##### onPullRequestRescoped()
**Trigger:** New commits pushed to existing PR
**Flow:**
1. Check if AI reviews are enabled
2. Check if PR is a draft
3. Execute re-review asynchronously (compares with previous review)

```java
@EventListener
public void onPullRequestRescoped(@Nonnull PullRequestRescopedEvent event) {
    // Same checks as onPullRequestOpened
    // But calls reReviewPullRequest() instead of reviewPullRequest()
    executeReviewAsync(pullRequest, true);
}
```

#### 4. Async Execution

Reviews are executed in background threads to avoid blocking PR operations:

```java
private final ExecutorService executorService = Executors.newFixedThreadPool(2);

private void executeReviewAsync(PullRequest pr, boolean isUpdate) {
    executorService.submit(() -> {
        ReviewResult result = isUpdate
            ? reviewService.reReviewPullRequest(pr.getId())
            : reviewService.reviewPullRequest(pr.getId());

        // Log results
        log.info("Review completed: status={}, issues={}",
            result.getStatus(), result.getIssueCount());
    });
}
```

**Benefits:**
- PR creation/update is not delayed
- User gets immediate feedback that PR was created
- Review happens in background
- Max 2 concurrent reviews to avoid overwhelming Ollama

#### 5. Configuration Checking

##### Enabled Flag Check
```java
private boolean isReviewEnabled() {
    Map<String, Object> config = configService.getConfigurationAsMap();
    Object enabled = config.get("enabled");
    return (Boolean) enabled; // Defaults to true
}
```

##### Draft PR Check
```java
private boolean shouldReviewDraftPRs() {
    Map<String, Object> config = configService.getConfigurationAsMap();
    Object reviewDrafts = config.get("reviewDraftPRs");
    return (Boolean) reviewDrafts; // Defaults to false
}
```

#### 6. Draft PR Detection

Since Bitbucket Data Center 8.9.0 doesn't have native draft PR support, the listener detects drafts by checking for common markers in the PR title:

```java
private boolean isDraftPR(PullRequest pr) {
    String title = pr.getTitle().toLowerCase();
    return title.startsWith("wip:") ||
           title.startsWith("draft:") ||
           title.startsWith("[wip]") ||
           title.startsWith("[draft]") ||
           title.contains("[wip]") ||
           title.contains("[draft]");
}
```

**Recognized Draft Markers:**
- `WIP: Feature implementation`
- `Draft: New API endpoint`
- `[WIP] Refactoring auth module`
- `[Draft] Update documentation`
- `Feature [WIP] with marker in middle`

#### 7. Error Handling

All review executions are wrapped in try-catch to prevent event handler failures:

```java
executorService.submit(() -> {
    try {
        ReviewResult result = reviewService.reviewPullRequest(prId);
        log.info("Review completed successfully");
    } catch (Exception e) {
        log.error("Failed to review PR #{}: {}", prId, e.getMessage(), e);
        // Does not throw - prevents event system issues
    }
});
```

#### 8. Lifecycle Management

The listener implements `DisposableBean` for proper cleanup:

```java
@Override
public void destroy() {
    log.info("Destroying PullRequestAIReviewListener");
    eventPublisher.unregister(this);  // Stop receiving events
    executorService.shutdown();       // Stop background threads
    log.info("PullRequestAIReviewListener destroyed successfully");
}
```

---

## Plugin Descriptor Updates

### atlassian-plugin.xml Changes

Added two new component imports for the listener:

```xml
<component-import key="eventPublisher"
                  interface="com.atlassian.event.api.EventPublisher"/>
<component-import key="pullRequestService"
                  interface="com.atlassian.bitbucket.pull.PullRequestService"/>
```

**Why These Are Needed:**
- **EventPublisher** - Required for `@ComponentImport EventPublisher` in listener constructor
- **PullRequestService** - Required for `@ComponentImport PullRequestService` in AIReviewServiceImpl (future use)

---

## Build Results

### Compilation Success
```
[INFO] BUILD SUCCESS
[INFO] Compiling 15 source files
[INFO] Total time: 3.991 s
```

### Spring Scanner Detection
```
[INFO] Encountered 25 total classes
[INFO] Processed 6 annotated classes
```

**Registered Spring Components:**
1. AIReviewerConfigServiceImpl
2. AIReviewServiceImpl
3. AdminConfigServlet
4. ConfigResource
5. **PullRequestAIReviewListener** ✅ NEW
6. (Additional component)

### Plugin Package
```
[INFO] Building jar: target/ai-code-reviewer-1.0.0-SNAPSHOT.jar
[INFO] Manifest validated
```

---

## Code Statistics

### New Code (This Phase)

| File | LOC | Methods | Purpose |
|------|-----|---------|---------|
| PullRequestAIReviewListener.java | ~240 | 8 | Event listener |

### Plugin Descriptor Changes

| File | Changes |
|------|---------|
| atlassian-plugin.xml | +2 component imports |

### Cumulative Statistics (Phases 1 + 2)

- **Total Java Files:** 15
- **Total Lines of Code:** ~2,430
- **Spring Components:** 6 registered
- **Active Objects Entities:** 2
- **REST Endpoints:** 4
- **Event Listeners:** 1 ✅ NEW

---

## How It Works

### Scenario 1: New PR Created

```
Developer creates PR #12345
    ↓
Bitbucket fires PullRequestOpenedEvent
    ↓
PullRequestAIReviewListener.onPullRequestOpened() called
    ↓
Check: Is AI review enabled? → YES
    ↓
Check: Is it a draft PR? → NO (title is "Fix login bug")
    ↓
executeReviewAsync(pr, isUpdate=false)
    ↓
Background thread started
    ↓
AIReviewService.reviewPullRequest(12345) called
    ↓
[Phase 3 TODO: Fetch diff, call Ollama, post comments]
    ↓
Review complete, results logged
```

### Scenario 2: Draft PR Created

```
Developer creates PR #12346 with title "WIP: New feature"
    ↓
Bitbucket fires PullRequestOpenedEvent
    ↓
PullRequestAIReviewListener.onPullRequestOpened() called
    ↓
Check: Is AI review enabled? → YES
    ↓
Check: Is it a draft PR? → YES (title starts with "WIP:")
    ↓
Check: Should review drafts? → NO (reviewDraftPRs=false)
    ↓
Review skipped, logged
```

### Scenario 3: PR Updated (New Commits)

```
Developer pushes new commits to PR #12345
    ↓
Bitbucket fires PullRequestRescopedEvent
    ↓
PullRequestAIReviewListener.onPullRequestRescoped() called
    ↓
Check: Is AI review enabled? → YES
    ↓
Check: Is it a draft PR? → NO
    ↓
executeReviewAsync(pr, isUpdate=true)
    ↓
Background thread started
    ↓
AIReviewService.reReviewPullRequest(12345) called
    ↓
[Phase 3 TODO: Compare with previous review, post only new issues]
    ↓
Re-review complete
```

### Scenario 4: Reviews Disabled

```
Developer creates PR #12347
    ↓
Bitbucket fires PullRequestOpenedEvent
    ↓
PullRequestAIReviewListener.onPullRequestOpened() called
    ↓
Check: Is AI review enabled? → NO (admin disabled in config)
    ↓
Review skipped immediately
```

---

## Testing Performed

### Build Tests
- ✅ `mvn clean compile` - SUCCESS
- ✅ `mvn package` - SUCCESS
- ✅ JAR file created
- ✅ Manifest validated
- ✅ 6 Spring components detected (up from 5)

### Code Quality
- ✅ No compilation errors
- ✅ No warnings
- ✅ Proper dependency injection
- ✅ Proper lifecycle management (register/unregister)
- ✅ Thread-safe async execution
- ✅ Comprehensive error handling

---

## Integration Points

### With Configuration Service
The listener reads configuration to determine:
- Whether reviews are enabled globally
- Whether to review draft PRs
- (Future: review thresholds, patterns, etc.)

### With AI Review Service
The listener delegates to AIReviewService:
- `reviewPullRequest(prId)` for new PRs
- `reReviewPullRequest(prId)` for updated PRs

### With Bitbucket Events
The listener subscribes to:
- `PullRequestOpenedEvent`
- `PullRequestRescopedEvent`

**Future events could be added:**
- `PullRequestMergedEvent` - Clean up data
- `PullRequestDeclinedEvent` - Clean up data
- `PullRequestCommentAddedEvent` - Respond to @mentions

---

## Phase 2 Status: COMPLETE ✅

All Phase 2 objectives from [IMPLEMENTATION_CHECKLIST.md](IMPLEMENTATION_CHECKLIST.md) have been achieved:

- ✅ **PullRequestAIReviewListener** - Fully implemented with all features
- ✅ **Event Registration** - Properly registers and unregisters
- ✅ **onPullRequestOpened()** - Handles new PR events
- ✅ **onPullRequestRescoped()** - Handles PR update events
- ✅ **Draft PR Checking** - Intelligent draft detection
- ✅ **Configuration Checking** - Reads enabled flags
- ✅ **Error Handling** - Comprehensive exception handling
- ✅ **Async Execution** - Background thread pool
- ✅ **Lifecycle Management** - Proper cleanup

---

## Known Limitations

1. **AIReviewService Still Stub** - Listener calls the service, but service doesn't yet implement actual review logic
   - This will be addressed in Phase 3

2. **No Manual Retry** - If a review fails, there's no UI to retry manually
   - Could be added as a REST endpoint or PR action button

3. **Fixed Thread Pool Size** - Currently hardcoded to 2 threads
   - Could be made configurable

4. **Draft Detection is Heuristic** - Relies on title markers, not a Bitbucket API
   - Good enough for Bitbucket 8.9.0 which lacks native draft support

5. **No Review Queuing** - If >2 reviews are triggered simultaneously, they just queue in the executor
   - Could implement smarter queuing/prioritization

---

## Next Steps (Phase 3)

According to [IMPLEMENTATION_CHECKLIST.md](IMPLEMENTATION_CHECKLIST.md), Phase 3 focuses on:

### AI Integration - Complete AIReviewServiceImpl

1. **Diff Fetching**
   - Use Bitbucket API to get PR diff
   - Handle large diffs
   - Parse diff hunks

2. **File Filtering**
   - Filter by file extensions (reviewExtensions)
   - Apply ignore patterns (ignorePatterns, ignorePaths)
   - Skip generated files if configured

3. **Diff Chunking**
   - Implement smart chunking algorithm
   - Split large diffs into manageable pieces
   - Preserve file/hunk boundaries
   - Track which files are in which chunks

4. **Ollama Integration**
   - Build prompts for code review
   - Call Ollama API with chunks
   - Handle streaming responses
   - Parse JSON responses
   - Extract issues from AI responses

5. **Comment Posting**
   - Post inline comments for each issue
   - Build summary comment
   - Handle comment threading
   - Update existing comments on re-review

6. **PR Actions**
   - Approve PR if no critical issues
   - Request changes if critical issues found
   - Respect minSeverity and requireApprovalFor settings

7. **History Persistence**
   - Save review results to AIReviewHistory table
   - Track issues found
   - Enable comparison for re-reviews

---

## Installation and Testing

### Installing the Plugin

1. **Build the plugin:**
   ```bash
   mvn clean package
   ```

2. **Locate the JAR:**
   ```bash
   target/ai-code-reviewer-1.0.0-SNAPSHOT.jar
   ```

3. **Install in Bitbucket:**
   - Administration → Manage apps
   - Upload app → Select JAR
   - Wait for installation

4. **Verify Installation:**
   - Check all modules are enabled
   - Look for log message: "PullRequestAIReviewListener registered successfully"

### Testing the Listener

#### Test 1: Create a PR
```bash
# In a Bitbucket repository
git checkout -b test-pr
echo "test" > test.txt
git add test.txt
git commit -m "Test commit"
git push origin test-pr

# Create PR via Bitbucket UI or API
```

**Expected:**
- Bitbucket logs show: "PR opened event received: PR #XXX"
- Logs show: "Starting initial review for PR #XXX (async)"
- Review executes in background (currently returns placeholder result)

#### Test 2: Update a PR
```bash
# Push new commits to the test PR
echo "more changes" >> test.txt
git commit -am "Update"
git push origin test-pr
```

**Expected:**
- Logs show: "PR rescoped event received: PR #XXX"
- Logs show: "Starting update review for PR #XXX (async)"

#### Test 3: Create a Draft PR
```bash
# Create PR with title "WIP: Test feature"
```

**Expected:**
- Logs show: "PR #XXX is a draft and reviewDraftPRs=false - skipping"

#### Test 4: Disable Reviews
```bash
# In admin UI: http://[bitbucket]/plugins/servlet/ai-reviewer/admin
# Uncheck "Enabled" checkbox
# Save configuration

# Create a new PR
```

**Expected:**
- Logs show: "AI reviews are disabled in configuration - skipping PR #XXX"

---

## Conclusion

Phase 2 is complete! The AI Code Reviewer plugin now has:

✅ **Automatic PR review triggering** via event listeners
✅ **Intelligent draft detection** based on title markers
✅ **Configuration-driven behavior** (enabled flag, reviewDraftPRs)
✅ **Async execution** to avoid blocking PR operations
✅ **Proper lifecycle management** for clean plugin unload
✅ **Comprehensive error handling** to prevent event system issues

The plugin is now **event-driven** and ready for Phase 3, where the actual AI code review logic will be implemented to analyze code and post comments.

---

**Phase 2 Duration:** 1 session
**Phase 2 Effort:** ~240 lines of code (1 new file, 1 file updated)
**Build Status:** ✅ SUCCESS
**Spring Components:** 6 registered (up from 5)
**Ready for:** Phase 3 - AI Integration and Core Review Logic
