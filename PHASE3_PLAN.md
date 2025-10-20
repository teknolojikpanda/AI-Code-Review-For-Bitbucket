# Phase 3 Implementation Plan - AI Integration

**Date:** October 18, 2025
**Status:** READY TO START
**Previous Phases:** ✅ Phase 1 Complete, ✅ Phase 2 Complete

---

## Overview

Phase 3 will implement the core AI code review logic by porting functionality from the Groovy script (lines 234-2077) into AIReviewServiceImpl.java. This is the largest and most complex phase.

**Estimated Effort:** ~1,500 lines of Java code
**Complexity:** HIGH
**Dependencies:** Bitbucket REST API, Ollama API

---

## Implementation Plan

### Group 1: Bitbucket API Integration (Priority 1)

#### 1.1 fetchDiff() - Fetch PR Diff
**Groovy Reference:** Lines 2061-2077
**Java Location:** AIReviewServiceImpl.java
**Purpose:** Fetch unified diff from Bitbucket REST API

**Groovy Logic:**
```groovy
def url = "${baseUrl}/rest/api/1.0/projects/${project}/repos/${slug}" +
          "/pull-requests/${prId}/diff?withComments=false&whitespace=ignore-all"
def resp = httpRequest(url, 'GET', BB_BASIC_AUTH, null, READ_TIMEOUT, 2)
return resp.body
```

**Java Implementation Needed:**
- Inject ApplicationPropertiesService for base URL
- Use Bitbucket REST API: `/rest/api/1.0/projects/{project}/repos/{slug}/pull-requests/{prId}/diff`
- Parameters: `withComments=false&whitespace=ignore-all`
- Authentication: Use plugin's security context (no hardcoded credentials)
- Error handling: PR not found, network errors
- Return: String (raw diff)

#### 1.2 getPullRequestDetails() - Get PR Metadata
**Purpose:** Get PR object from Bitbucket
**Java Implementation:**
```java
private PullRequest getPullRequest(long pullRequestId) {
    // Use injected PullRequestService
    return pullRequestService.getById(pullRequestId);
}
```

---

### Group 2: Diff Processing (Priority 1)

#### 2.1 validatePRSize() - Check Diff Size
**Groovy Reference:** Lines 1680-1698
**Purpose:** Validate diff is not too large

**Groovy Logic:**
```groovy
def sizeBytes = diffText.getBytes('UTF-8').length
def sizeMB = (sizeBytes / (1024 * 1024)).round(2)
def lines = diffText.split('\n').length

if (sizeMB > (MAX_DIFF_SIZE / (1024 * 1024))) {
    return [valid: false, message: "Diff too large: ${sizeMB}MB exceeds ${maxMB}MB limit"]
}
return [valid: true, sizeMB: sizeMB, lines: lines]
```

**Java Implementation Needed:**
- Calculate diff size in bytes and MB
- Count lines
- Compare against maxDiffSize config
- Return validation result with metrics

#### 2.2 analyzeDiffForSummary() - Parse Diff
**Groovy Reference:** Lines 1700-1750
**Purpose:** Parse diff to extract file changes (additions/deletions per file)

**Groovy Logic:**
```groovy
def fileChanges = [:]
def currentFile = null
diffText.eachLine { line ->
    if (line.startsWith('diff --git')) {
        // Extract file name: diff --git a/path/file.java b/path/file.java
        currentFile = line.split(' ')[2].substring(2)
        fileChanges[currentFile] = [additions: 0, deletions: 0]
    } else if (line.startsWith('+') && !line.startsWith('+++')) {
        fileChanges[currentFile].additions++
    } else if (line.startsWith('-') && !line.startsWith('---')) {
        fileChanges[currentFile].deletions++
    }
}
return fileChanges
```

**Java Implementation Needed:**
- Parse diff line by line
- Extract file paths from `diff --git` lines
- Count additions (+) and deletions (-)
- Return Map<String, FileChange> where FileChange has additions/deletions counts

#### 2.3 filterFilesForReview() - Filter Files
**Groovy Reference:** Lines 525-550
**Purpose:** Filter files based on extensions, patterns, and paths

**Groovy Logic:**
```groovy
return files.findAll { file ->
    // Check ignore paths
    if (IGNORE_PATHS.any { path -> file.contains(path) }) return false

    // Check ignore patterns
    def fileName = file.substring(file.lastIndexOf('/') + 1)
    if (IGNORE_PATTERNS.any { pattern -> fileName.matches(pattern.replace('*', '.*')) }) return false

    // Check extension
    def extension = file.substring(file.lastIndexOf('.') + 1).toLowerCase()
    return REVIEW_EXTENSIONS.contains(extension)
}
```

**Java Implementation Needed:**
- Get reviewExtensions, ignorePatterns, ignorePaths from config
- Filter out ignored paths
- Filter out ignored patterns (convert glob to regex)
- Filter out non-reviewable extensions
- Return Set<String> of files to review

---

### Group 3: Chunking Algorithm (Priority 2)

#### 3.1 smartChunkDiff() - Chunk Diff
**Groovy Reference:** Lines 741-900
**Purpose:** Split large diffs into chunks for Ollama processing

**Groovy Logic (Simplified):**
```groovy
def chunks = []
def currentChunk = [content: '', files: [], size: 0]

def fileDiffs = splitDiffByFile(diffText)
fileDiffs.each { filePath, fileDiff ->
    if (!filesToReview.contains(filePath)) return

    def fileSize = fileDiff.length()

    if (currentChunk.size + fileSize > MAX_CHARS_PER_CHUNK ||
        currentChunk.files.size >= MAX_FILES_PER_CHUNK) {
        chunks << currentChunk
        currentChunk = [content: '', files: [], size: 0]
    }

    currentChunk.content += fileDiff
    currentChunk.files << filePath
    currentChunk.size += fileSize
}

if (currentChunk.size > 0) {
    chunks << currentChunk
}

return chunks.take(MAX_CHUNKS)
```

**Java Implementation Needed:**
- Split diff by file boundaries
- Group files into chunks
- Respect maxCharsPerChunk limit
- Respect maxFilesPerChunk limit
- Limit total chunks to maxChunks
- Return List<DiffChunk> where DiffChunk has content, files, size

#### 3.2 extractFilesFromChunk() - Parse Chunk
**Groovy Reference:** Lines 1800-1850
**Purpose:** Extract file names and modified line numbers from chunk

**Java Implementation Needed:**
- Parse diff hunks in chunk
- Extract file paths
- Extract modified line ranges from @@ markers
- Return Map with files and modifiedLines

---

### Group 4: Ollama Integration (Priority 2)

#### 4.1 buildPrompt() - Create Review Prompt
**Groovy Reference:** Lines 1500-1600
**Purpose:** Build prompt for Ollama

**Groovy Logic:**
```groovy
def prompt = """You are an expert code reviewer. Review the following code changes and identify issues.

For each issue found, provide a JSON object with:
- path: file path
- line: line number
- severity: "critical", "high", "medium", or "low"
- type: issue category (security, performance, bug, style, etc.)
- summary: brief description
- details: detailed explanation (optional)
- fix: suggested fix (optional)

Return ONLY a JSON array of issues, no other text.

Code diff:
${chunkContent}

JSON array of issues:"""
return prompt
```

**Java Implementation Needed:**
- Build structured prompt for code review
- Include chunk content
- Specify JSON output format
- Return String prompt

#### 4.2 callOllama() - Call Ollama API
**Groovy Reference:** Lines 1400-1500
**Purpose:** Send chunk to Ollama for analysis

**Groovy Logic:**
```groovy
def requestBody = [
    model: model,
    prompt: buildPrompt(chunkContent),
    stream: false,
    options: [
        temperature: 0.1,
        top_p: 0.9,
        num_predict: 8192
    ]
]

def url = "${OLLAMA_URL}/api/generate"
def response = httpClient.postJson(url, requestBody)

// Parse response
def responseText = response.get("response").getAsString()
def issues = parseOllamaResponse(responseText)
return [issues: issues, logs: logs]
```

**Java Implementation Needed:**
- Build Ollama request body
- Use HttpClientUtil.postJson()
- Parse JSON response
- Extract issues array
- Return Map with issues and logs

#### 4.3 parseOllamaResponse() - Parse AI Response
**Groovy Reference:** Lines 1300-1400
**Purpose:** Extract JSON issues from Ollama response

**Groovy Logic:**
```groovy
// Find JSON array in response (may have extra text)
def jsonMatch = responseText =~ /\[[\s\S]*\]/
if (jsonMatch) {
    def jsonText = jsonMatch[0]
    def issues = new JsonSlurper().parseText(jsonText)
    return issues
}
return []
```

**Java Implementation Needed:**
- Find JSON array in response text
- Parse with Gson
- Convert to List<ReviewIssue>
- Handle parse errors
- Return list

#### 4.4 robustOllamaCall() - Retry Logic
**Groovy Reference:** Lines 679-750
**Purpose:** Call Ollama with retry and fallback

**Groovy Logic:**
```groovy
// Try primary model (2 attempts)
for (int i = 0; i < 2; i++) {
    try {
        def response = ollamaCircuitBreaker.execute {
            callOllama(chunkContent, chunkIndex, totalChunks, OLLAMA_MODEL)
        }
        if (response.issues != null) return response
    } catch (Exception e) {
        lastError = e
    }
}

// Fallback to smaller model
try {
    return callOllama(chunkContent, chunkIndex, totalChunks, FALLBACK_MODEL)
} catch (Exception e) {
    throw lastError ?: e
}
```

**Java Implementation Needed:**
- Try primary model (2 attempts)
- Use circuit breaker
- Fallback to fallbackModel if primary fails
- Collect logs
- Return result with issues and logs

---

### Group 5: Parallel Processing (Priority 2)

#### 5.1 processChunksInParallel() - Parallel Execution
**Groovy Reference:** Lines 552-677
**Purpose:** Process multiple chunks concurrently

**Groovy Logic:**
```groovy
def executor = Executors.newFixedThreadPool(Math.min(PARALLEL_CHUNK_THREADS, chunks.size()))
def futures = []

chunks.eachWithIndex { chunk, index ->
    futures << executor.submit({
        apiRateLimiter.acquire()
        def result = robustOllamaCall(chunk.content, index + 1, chunks.size())
        return [success: result.issues != null, issues: result.issues, ...]
    } as Callable)
}

def results = []
futures.each { future ->
    def result = future.get(OLLAMA_TIMEOUT + 10000, TimeUnit.MILLISECONDS)
    results << result
}

executor.shutdown()
return results
```

**Java Implementation Needed:**
- Create ExecutorService with parallelChunkThreads
- Submit all chunks as Callable tasks
- Each task: acquire rate limit, call robustOllamaCall
- Wait for all futures with timeout
- Collect results
- Shutdown executor
- Return List<ChunkResult>

---

### Group 6: Comment Posting (Priority 3)

#### 6.1 addPRComment() - Post Comment
**Groovy Reference:** Lines 1900-1950
**Purpose:** Post general comment to PR

**Bitbucket API:**
```
POST /rest/api/1.0/projects/{project}/repos/{slug}/pull-requests/{prId}/comments
Body: { "text": "comment text" }
```

**Java Implementation Needed:**
- Build JSON body
- POST to Bitbucket API
- Parse response (get comment ID and version)
- Return Comment object

#### 6.2 updatePRComment() - Update Comment
**Groovy Reference:** Lines 1950-2000
**Purpose:** Update existing comment

**Bitbucket API:**
```
PUT /rest/api/1.0/projects/{project}/repos/{slug}/pull-requests/{prId}/comments/{commentId}
Body: { "text": "updated text", "version": 1 }
```

**Java Implementation Needed:**
- Build JSON body with version
- PUT to Bitbucket API
- Handle version conflicts
- Return updated Comment

#### 6.3 replyToComment() - Reply to Comment
**Groovy Reference:** Lines 2000-2050
**Purpose:** Reply to (thread under) a comment

**Bitbucket API:**
```
POST /rest/api/1.0/projects/{project}/repos/{slug}/pull-requests/{prId}/comments
Body: { "text": "reply text", "parent": { "id": parentId } }
```

**Java Implementation Needed:**
- Build JSON body with parent reference
- POST to Bitbucket API
- Return reply Comment

#### 6.4 buildSummaryComment() - Build Summary
**Groovy Reference:** Lines 900-1000
**Purpose:** Build formatted summary comment

**Format:**
```markdown
🤖 **AI Code Review**

**Summary:** Found 12 issue(s) across 5 file(s)

**Severity Breakdown:**
- 🔴 Critical: 2
- 🟠 High: 3
- 🟡 Medium: 5
- 🟢 Low: 2

**Files Changed:**
- src/Main.java (+50, -10)
- src/Utils.java (+20, -5)
...

⏱️ Review completed in 15.3s
```

**Java Implementation Needed:**
- Group issues by severity
- Format statistics
- Include file changes
- Add timing information
- Return formatted String

#### 6.5 postIssueComments() - Post Issues
**Groovy Reference:** Lines 1800-1900
**Purpose:** Post individual issues as replies to summary

**Java Implementation Needed:**
- Filter issues by minSeverity
- Limit to maxIssueComments
- Format each issue as comment
- Reply to summary comment
- Add API delay between posts
- Return count of posted comments

---

### Group 7: PR Actions (Priority 3)

#### 7.1 approvePR() - Approve PR
**Groovy Reference:** Lines 1900+
**Bitbucket API:**
```
POST /rest/api/1.0/projects/{project}/repos/{slug}/pull-requests/{prId}/approve
```

#### 7.2 shouldRequestChanges() - Decide Action
**Groovy Reference:** Lines 519-523
**Logic:** Check if any issue severity matches requireApprovalFor config

---

### Group 8: Update/Re-review Logic (Priority 3)

#### 8.1 getPreviousIssues() - Fetch Previous Review
**Groovy Reference:** Lines 1700+
**Purpose:** Get issues from previous review (from comments)

**Java Implementation Needed:**
- Fetch all PR comments
- Find comments from bot/plugin
- Parse issue format
- Return List<ReviewIssue> and comment references

#### 8.2 findResolvedIssues() - Compare Issues
**Groovy Reference:** Lines 780-790
**Purpose:** Find issues that were resolved

**Logic:**
```java
return previousIssues.stream()
    .filter(prev -> !currentIssues.stream()
        .anyMatch(curr -> isSameIssue(prev, curr)))
    .collect(Collectors.toList());
```

#### 8.3 findNewIssues() - Find New Issues
**Groovy Reference:** Lines 791-800
**Purpose:** Find issues that are new

**Logic:**
```java
return currentIssues.stream()
    .filter(curr -> !previousIssues.stream()
        .anyMatch(prev -> isSameIssue(prev, curr)))
    .collect(Collectors.toList());
```

#### 8.4 isSameIssue() - Compare Issues
**Logic:**
```java
return issue1.getPath().equals(issue2.getPath()) &&
       Objects.equals(issue1.getLine(), issue2.getLine()) &&
       issue1.getType().equals(issue2.getType());
```

---

## Implementation Strategy

### Iteration 1: Basic Flow (Days 1-2)
1. ✅ Implement fetchDiff()
2. ✅ Implement validatePRSize()
3. ✅ Implement filterFilesForReview()
4. ✅ Implement simple chunking (no smart algorithm yet)
5. ✅ Build and test compilation

### Iteration 2: Ollama Integration (Days 3-4)
6. ✅ Implement buildPrompt()
7. ✅ Implement callOllama()
8. ✅ Implement parseOllamaResponse()
9. ✅ Implement robustOllamaCall()
10. ✅ Test with real Ollama

### Iteration 3: Comment Posting (Days 5-6)
11. ✅ Implement addPRComment()
12. ✅ Implement buildSummaryComment()
13. ✅ Implement postIssueComments()
14. ✅ Test end-to-end flow

### Iteration 4: Advanced Features (Days 7-8)
15. ✅ Implement smart chunking algorithm
16. ✅ Implement parallel processing
17. ✅ Implement updatePRComment()
18. ✅ Implement replyToComment()
19. ✅ Implement approvePR()

### Iteration 5: Re-review Logic (Days 9-10)
20. ✅ Implement getPreviousIssues()
21. ✅ Implement issue comparison
22. ✅ Implement resolved issue tracking
23. ✅ Test update scenarios

---

## Current Status

- ✅ Phase 1: Complete
- ✅ Phase 2: Complete
- ⏳ Phase 3: Ready to start
- **Next Task:** Start with Iteration 1 (Basic Flow)

---

## Success Criteria

Phase 3 is complete when:
- [ ] Can fetch PR diff from Bitbucket
- [ ] Can validate diff size
- [ ] Can filter files for review
- [ ] Can chunk large diffs
- [ ] Can call Ollama API and parse responses
- [ ] Can post summary comment to PR
- [ ] Can post individual issue comments
- [ ] Can approve PR or request changes
- [ ] Can handle PR updates (re-reviews)
- [ ] All tests pass
- [ ] Plugin works end-to-end in Bitbucket

**Estimated Completion:** Phase 3 will add ~1,500 lines of code across multiple sessions.
