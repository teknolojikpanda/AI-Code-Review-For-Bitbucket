import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.util.concurrent.*
import java.security.MessageDigest

import com.onresolve.scriptrunner.runner.customisers.PluginModule

import com.atlassian.bitbucket.pull.*
import com.atlassian.bitbucket.comment.*
import com.atlassian.bitbucket.server.ApplicationPropertiesService
import com.atlassian.bitbucket.event.pull.PullRequestOpenedEvent
import com.atlassian.bitbucket.event.pull.PullRequestRescopedEvent

@PluginModule PullRequestService prService
@PluginModule CommentService commentService
@PluginModule ApplicationPropertiesService appProps

// ============================================================================
// CONFIGURATION
// ============================================================================
@Field String OLLAMA_URL = System.getenv('OLLAMA_URL') ?: 'http://10.152.98.37:11434'
@Field String OLLAMA_MODEL = System.getenv('OLLAMA_MODEL') ?: 'qwen3-coder:30b'
@Field String FALLBACK_MODEL = System.getenv('FALLBACK_MODEL') ?: 'qwen3-coder:7b'
@Field int MAX_CHARS_PER_CHUNK = (System.getenv('REVIEW_CHUNK') ?: '60000') as int
@Field int MAX_FILES_PER_CHUNK = (System.getenv('FILES_PER_CHUNK') ?: '3') as int
@Field String BB_BASIC_AUTH = System.getenv('BB_ADMIN_AUTH') ?: "admin:20150467@Can"
@Field String REVIEWER_BOT_USER = System.getenv('REVIEWER_BOT_USER') ?: "reviewer_bot"
@Field String REVIEWER_BOT_AUTH = System.getenv('REVIEWER_BOT_AUTH') ?: "reviewer_bot:1234-asd"

@Field int CONNECT_TIMEOUT = 10000
@Field int READ_TIMEOUT = 30000
@Field int OLLAMA_TIMEOUT = 300000
@Field int MAX_CHUNKS = 20
@Field int MAX_ISSUES_PER_FILE = 50
@Field int MAX_RETRIES = 3
@Field int BASE_RETRY_DELAY_MS = 1000
@Field int MAX_ISSUE_COMMENTS = 30
@Field int API_DELAY_MS = 100
@Field int MAX_DIFF_SIZE = 10_000_000
@Field int PARALLEL_CHUNK_THREADS = 4

// File filtering configuration
@Field Set<String> REVIEW_EXTENSIONS = ['java', 'groovy', 'js', 'ts', 'tsx', 'jsx', 'py', 'go', 'rs', 'cpp', 'c', 'cs', 'php', 'rb', 'kt', 'swift', 'scala']
@Field Set<String> IGNORE_PATTERNS = ['*.min.js', '*.generated.*', 'package-lock.json', 'yarn.lock', '*.map']
@Field Set<String> IGNORE_PATHS = ['node_modules/', 'vendor/', 'build/', 'dist/', '.git/']

@Field JsonSlurper jsonParser = new JsonSlurper()
@Field String baseUrl
@Field String project
@Field String slug
@Field Long prId

// Circuit breaker for Ollama
@Field CircuitBreaker ollamaCircuitBreaker = new CircuitBreaker(5, 60000)

// Rate limiter
@Field RateLimiter apiRateLimiter = new RateLimiter(10, 1000) // 10 requests per second

// Metrics collector - will be initialized after log is available
@Field MetricsCollector metrics

// ============================================================================
// HELPER CLASSES
// ============================================================================

class CircuitBreaker {
    private int failureCount = 0
    private long lastFailureTime = 0
    private final int threshold
    private final long timeout
    private String lastError = null
    
    CircuitBreaker(int threshold, long timeout) {
        this.threshold = threshold
        this.timeout = timeout
    }
    
    boolean isOpen() {
        if (failureCount >= threshold) {
            if (System.currentTimeMillis() - lastFailureTime < timeout) {
                return true
            }
            reset()
        }
        return false
    }
    
    def execute(Closure operation) {
        if (isOpen()) {
            throw new RuntimeException("Circuit breaker open - service temporarily unavailable. Last error: ${lastError}")
        }
        
        try {
            def result = operation()
            reset()
            return result
        } catch (Exception e) {
            recordFailure(e.message)
            throw e
        }
    }
    
    void recordFailure(String error) {
        failureCount++
        lastFailureTime = System.currentTimeMillis()
        lastError = error
    }
    
    void reset() {
        failureCount = 0
        lastFailureTime = 0
        lastError = null
    }
}

class RateLimiter {
    private final int maxRequests
    private final long timeWindow
    private final Queue<Long> requestTimes = new ConcurrentLinkedQueue<>()
    
    RateLimiter(int maxRequests, long timeWindow) {
        this.maxRequests = maxRequests
        this.timeWindow = timeWindow
    }
    
    void acquire() {
        long now = System.currentTimeMillis()
        
        // Remove old entries
        while (!requestTimes.isEmpty() && requestTimes.peek() < now - timeWindow) {
            requestTimes.poll()
        }
        
        // Wait if we're at the limit
        while (requestTimes.size() >= maxRequests) {
            Thread.sleep(50)
            now = System.currentTimeMillis()
            while (!requestTimes.isEmpty() && requestTimes.peek() < now - timeWindow) {
                requestTimes.poll()
            }
        }
        
        requestTimes.offer(now)
    }
}

class MetricsCollector {
    private final Map<String, Object> currentMetrics = new ConcurrentHashMap<>()
    private final def logger
    
    MetricsCollector(def logger = null) {
        this.logger = logger
    }
    
    void recordStart() {
        currentMetrics.startTime = System.currentTimeMillis()
    }
    
    void recordMetric(String key, Object value) {
        currentMetrics[key] = value
    }
    
    void incrementCounter(String key) {
        currentMetrics[key] = (currentMetrics[key] ?: 0) + 1
    }
    
    Map<String, Object> getMetrics() {
        def endTime = System.currentTimeMillis()
        currentMetrics.elapsedTime = ((endTime - (currentMetrics.startTime ?: endTime)) / 1000).round(1)
        return new HashMap<>(currentMetrics)
    }
    
    void logMetrics() {
        def metrics = getMetrics()
        if (logger) {
            logger.warn("AI Review Metrics: ${JsonOutput.toJson(metrics)}")
        }
    }
}

class ReviewProfile {
    String minSeverity = 'medium'
    List<String> requireApprovalFor = ['critical', 'high']
    int maxIssuesPerFile = 50
    boolean skipGeneratedFiles = true
    boolean skipTests = false
}

// ============================================================================
// EVENT FILTERING AND FIELD RESET
// ============================================================================
if (!(event instanceof PullRequestOpenedEvent || event instanceof PullRequestRescopedEvent)) return

// CRITICAL: Reset all field variables to prevent ScriptRunner caching issues
// ScriptRunner may reuse script instances, causing stale field values
baseUrl = null
project = null
slug = null
prId = null

// Now initialize with fresh values from the event
def pr = event.pullRequest
def repo = pr.toRef.repository
project = repo.project.key
slug = repo.slug

// CRITICAL FIX: Always get PR ID from the event object directly
// ScriptRunner may cache script instances, causing stale field values
prId = event.pullRequest.id  // Force fresh PR ID from event
log.warn("AI Review: Event PR ID captured: ${prId}")

def isUpdate = event instanceof PullRequestRescopedEvent

// Initialize metrics with log after it's available
metrics = new MetricsCollector(log)

if (pr.draft) {
  log.warn("AI Review: Skipping draft PR #${prId}")
  return
}

if (isUpdate) {
  log.warn("AI Review: ========== Re-reviewing PR #${prId} after update in ${project}/${slug} ==========")
} else {
  log.warn("AI Review: ========== Starting review for PR #${prId} in ${project}/${slug} ==========")
}

baseUrl = appProps?.baseUrl?.toString() ?: "http://0.0.0.0:7990"

// ============================================================================
// MAIN EXECUTION
// ============================================================================
try {
  // Ensure critical variables are initialized
  if (!prId) {
    log.error("AI Review: CRITICAL - PR ID is null or undefined!")
    prId = event?.pullRequest?.id
    if (!prId) {
      throw new RuntimeException("Cannot determine PR ID from event")
    }
    log.warn("AI Review: Recovered PR ID from event: ${prId}")
  }
  
  if (!metrics) {
    log.warn("AI Review: Metrics collector not initialized, creating now")
    metrics = new MetricsCollector(log)
  }
  
  metrics.recordStart()
  metrics.recordMetric('project', project)
  metrics.recordMetric('repository', slug)
  metrics.recordMetric('prId', prId)
  metrics.recordMetric('isUpdate', isUpdate)
  
  def previousData = [issues: [], allComments: []]
  if (isUpdate) {
    previousData = getPreviousIssues()
    metrics.recordMetric('previousIssues', previousData.issues.size())
  }
  
  def previousIssues = previousData.issues
  def previousComments = previousData.allComments
  
  if (!validateOllama()) {
    metrics.recordMetric('ollamaValidation', false)
    return
  }
  metrics.recordMetric('ollamaValidation', true)

  final def ctx = [
    baseUrl: baseUrl,
    project: project,
    slug   : slug,
    prId   : prId
  ]

  String diffText = fetchDiff(ctx)
  
  if (!diffText || diffText.trim().isEmpty()) {
    log.warn("AI Review: No diff content found, skipping review")
    addPRComment("ℹ️ AI review: No changes to review.")
    metrics.recordMetric('emptyDiff', true)
    return
  }

  def sizeCheck = validatePRSize(diffText)
  if (!sizeCheck.valid) {
    log.error("AI Review: PR too large - ${sizeCheck.message}")
    addPRComment("❌ **AI Review: PR Too Large**\n\n${sizeCheck.message}")
    metrics.recordMetric('prTooLarge', true)
    return
  }
  
  metrics.recordMetric('diffSize', sizeCheck.sizeMB)
  metrics.recordMetric('lineCount', sizeCheck.lines)
  
  if (sizeCheck.lines) {
    log.warn("AI Review: PR size: ${sizeCheck.lines} lines, ${sizeCheck.sizeMB}MB")
  }

  def fileChanges = analyzeDiffForSummary(diffText)
  def filesToReview = filterFilesForReview(fileChanges.keySet())
  
  log.warn("AI Review: Analyzed ${fileChanges.size()} file(s), will review ${filesToReview.size()} file(s)")
  metrics.recordMetric('totalFiles', fileChanges.size())
  metrics.recordMetric('filesToReview', filesToReview.size())

  def progressComment = addPRComment("🔄 **AI Code Review in Progress...**\n\nAnalyzing changes with ${OLLAMA_MODEL}...")
  def progressCommentId = progressComment?.id
  def progressCommentVersion = progressComment?.version ?: 0

  def chunks = smartChunkDiff(diffText, filesToReview)
  def wasTruncated = chunks.size() >= MAX_CHUNKS && diffText.length() > MAX_CHARS_PER_CHUNK * MAX_CHUNKS
  
  metrics.recordMetric('chunks', chunks.size())
  metrics.recordMetric('wasTruncated', wasTruncated)

  List<Map> allIssues = []
  int failedChunks = 0

  log.warn("AI Review: Processing ${chunks.size()} chunk(s) with Ollama (${OLLAMA_MODEL})")
  
  // Log chunk details before processing
  chunks.eachWithIndex { chunk, index ->
    try {
      def chunkSize = chunk.content?.length() ?: 0
      def result = chunk.content ? extractFilesFromChunk(chunk.content) : [files: [], modifiedLines: [:]]
      log.warn("AI Review: [PRE-PROCESS] Chunk ${index + 1}: ${chunkSize} chars, files: ${result.files}")
    } catch (Exception e) {
      log.warn("AI Review: [PRE-PROCESS] Could not analyze chunk ${index + 1}: ${e.message}")
    }
  }
  
  def startTime = System.currentTimeMillis()

  // Process chunks in parallel
  def chunkResults = processChunksInParallel(chunks)
  
  chunkResults.eachWithIndex { result, index ->
    def chunkNum = index + 1
    if (result.success) {
      def issueCount = result.issues?.size() ?: 0
      log.warn("AI Review: [MAIN DEBUG] Chunk ${chunkNum} SUCCESS: ${issueCount} issues found")
      if (result.issues) {
        allIssues.addAll(result.issues)
      }
      metrics.incrementCounter('successfulChunks')
    } else {
      log.error("AI Review: [MAIN FAIL] ========== CHUNK ${chunkNum} FAILED IN MAIN PROCESSING ==========")
      log.error("AI Review: [MAIN FAIL] Chunk ${chunkNum} result: success=${result.success}")
      log.error("AI Review: [MAIN FAIL] Chunk ${chunkNum} issues: ${result.issues}")
      log.error("AI Review: [MAIN FAIL] Chunk ${chunkNum} error: ${result.error ?: 'No error message'}")
      
      // Log chunk details for debugging
      try {
        if (chunks[index]?.content) {
          def chunkContent = chunks[index].content
          def chunkSize = chunkContent.length()
          log.error("AI Review: [MAIN FAIL] Failed chunk ${chunkNum} size: ${chunkSize} chars")
          
          def files = extractFilesFromChunk(chunkContent)
          log.error("AI Review: [MAIN FAIL] Failed chunk ${chunkNum} files: ${files}")
          log.error("AI Review: [MAIN FAIL] Failed chunk ${chunkNum} preview: ${chunkContent.take(300)}...")
        } else {
          log.error("AI Review: [MAIN FAIL] Failed chunk ${chunkNum} has no content")
        }
      } catch (Exception ex) {
        log.error("AI Review: [MAIN FAIL] Could not analyze failed chunk ${chunkNum}: ${ex.message}")
      }
      
      log.error("AI Review: [MAIN FAIL] ========== END CHUNK ${chunkNum} MAIN FAILURE DEBUG ==========")
      
      failedChunks++
      metrics.incrementCounter('failedChunks')
    }
  }

  def elapsedTime = ((System.currentTimeMillis() - startTime) / 1000).round(1)
  log.warn("AI Review: Analysis completed in ${elapsedTime}s - Total issues: ${allIssues.size()}, Failed chunks: ${failedChunks}")
  
  // Log summary of chunk results
  log.warn("AI Review: [SUMMARY] Chunk processing summary:")
  chunkResults.eachWithIndex { result, index ->
    def chunkNum = index + 1
    def status = result.success ? "SUCCESS" : "FAILED"
    def issueCount = result.issues?.size() ?: 0
    def error = result.error ? " (${result.error})" : ""
    log.warn("AI Review: [SUMMARY] Chunk ${chunkNum}: ${status} - ${issueCount} issues${error}")
  }
  
  metrics.recordMetric('analysisTime', elapsedTime)
  metrics.recordMetric('totalIssues', allIssues.size())
  metrics.recordMetric('failedChunks', failedChunks)

  // Filter issues by severity based on profile
  def profile = getReviewProfile(repo)
  allIssues = filterIssuesByProfile(allIssues, profile)
  
  def resolvedIssues = []
  def newIssues = allIssues
  
  if (isUpdate && previousIssues) {
    resolvedIssues = findResolvedIssues(previousIssues, allIssues)
    newIssues = findNewIssues(previousIssues, allIssues)
    
    if (resolvedIssues) {
      log.warn("AI Review: Marking ${resolvedIssues.size()} resolved issue(s) in original comments")
      
      int markedCount = 0
      resolvedIssues.each { issue ->
        if (markIssueAsResolved(issue, previousComments)) {
          markedCount++
        }
      }
      
      log.warn("AI Review: Successfully marked ${markedCount}/${resolvedIssues.size()} issues as resolved")
      postResolvedComment(resolvedIssues)
    }
    
    metrics.recordMetric('resolvedIssues', resolvedIssues.size())
    metrics.recordMetric('newIssues', newIssues.size())
    log.warn("AI Review: Issue comparison - ${resolvedIssues.size()} resolved, ${newIssues.size()} new, ${allIssues.size()} total")
  }
  
  if (allIssues.isEmpty()) {
    handleNoIssuesFound(isUpdate, previousIssues, elapsedTime, failedChunks, wasTruncated, progressCommentId, progressCommentVersion, pr, chunks)
    approvePR()
    log.warn("AI Review: ✅ Review completed successfully - PR approved")
    metrics.recordMetric('approved', true)
    metrics.logMetrics()
    return
  }

  def summaryText = buildSummaryComment(allIssues, resolvedIssues, newIssues, isUpdate, fileChanges, elapsedTime, failedChunks, wasTruncated, pr, chunks)
  
  log.warn("AI Review: ====== POSTING SUMMARY NOW ======")
  def summaryComment = null
  if (progressCommentId) {
    updatePRComment(progressCommentId, summaryText, progressCommentVersion)
    summaryComment = [id: progressCommentId]
    log.warn("AI Review: ✅ Updated progress comment ${progressCommentId} with FINAL SUMMARY")
  } else {
    summaryComment = addPRComment(summaryText)
    log.warn("AI Review: ✅ Created new summary comment with ID ${summaryComment?.id}")
  }
  
  def summaryCommentId = summaryComment?.id
  
  if (!summaryCommentId) {
    log.error("AI Review: ❌ CRITICAL - No summary comment ID!")
  } else {
    log.warn("AI Review: ✅✅✅ Summary posted with ID: ${summaryCommentId} ✅✅✅")
    log.warn("AI Review: ====== NOW POSTING ISSUES AS REPLIES TO ${summaryCommentId} ======")
  }

  def commentsPosted = postIssueComments(allIssues, summaryCommentId, profile, diffText)
  metrics.recordMetric('commentsPosted', commentsPosted)
  
  updateSummaryWithReplyCount(summaryCommentId, commentsPosted, allIssues.size(), summaryText)
  
  def bySeverity = allIssues.groupBy { it.severity ?: "medium" }
  def criticalCount = bySeverity.critical?.size() ?: 0
  def highCount = bySeverity.high?.size() ?: 0
  
  if (shouldRequestChanges(profile, bySeverity)) {
    requestChanges()
    log.warn("AI Review: ⚠️ Review completed with ${allIssues.size()} issue(s) - changes requested")
    metrics.recordMetric('changesRequested', true)
  } else {
    approvePR()
    log.warn("AI Review: ✅ Review completed with ${allIssues.size()} issue(s) - approved (only medium/low issues)")
    metrics.recordMetric('approved', true)
  }
  
  metrics.logMetrics()

} catch (Exception e) {
  log.error("AI Review: Fatal error: ${e.message}", e)
  metrics.recordMetric('error', e.message)
  metrics.logMetrics()
  
  try {
    def errorDetails = """❌ **AI Review Failed**

An unexpected error occurred.

**Error:** ${e.message}

Contact an administrator if this persists."""
    
    addPRComment(errorDetails)
  } catch (Exception commentError) {
    log.error("AI Review: Could not post error comment: ${commentError.message}")
  }
}

log.warn("AI Review: ========== Review completed for PR #${prId} ==========")

// ============================================================================
// IMPROVED HELPER FUNCTIONS
// ============================================================================

def getReviewProfile(repo) {
  // Could be extended to read from repository settings
  return new ReviewProfile()
}

def filterIssuesByProfile(List<Map> issues, ReviewProfile profile) {
  def severityOrder = ['critical': 0, 'high': 1, 'medium': 2, 'low': 3]
  def minSeverityLevel = severityOrder[profile.minSeverity] ?: 2
  
  return issues.findAll { issue ->
    def issueLevel = severityOrder[issue.severity ?: 'medium'] ?: 2
    issueLevel <= minSeverityLevel
  }
}

def shouldRequestChanges(ReviewProfile profile, Map<String, List> bySeverity) {
  return profile.requireApprovalFor.any { severity ->
    (bySeverity[severity]?.size() ?: 0) > 0
  }
}

def filterFilesForReview(Set<String> files) {
  return files.findAll { file ->
    // Check if file should be ignored
    if (IGNORE_PATHS.any { path -> file.contains(path) }) {
      log.debug("AI Review: Ignoring file in excluded path: ${file}")
      return false
    }
    
    def fileName = file.substring(file.lastIndexOf('/') + 1)
    if (IGNORE_PATTERNS.any { pattern -> 
      fileName.matches(pattern.replace('*', '.*'))
    }) {
      log.debug("AI Review: Ignoring file matching pattern: ${file}")
      return false
    }
    
    // Check if file extension is reviewable
    def extension = file.substring(file.lastIndexOf('.') + 1).toLowerCase()
    if (!REVIEW_EXTENSIONS.contains(extension)) {
      log.debug("AI Review: Ignoring file with non-reviewable extension: ${file}")
      return false
    }
    
    return true
  }
}

def processChunksInParallel(List<Map> chunks) {
  def executor = Executors.newFixedThreadPool(Math.min(PARALLEL_CHUNK_THREADS, chunks.size()))
  def futures = []
  
  def results = []
  try {
    chunks.eachWithIndex { chunk, index ->
      log.warn("AI Review: [PARALLEL DEBUG] Starting chunk ${index + 1}/${chunks.size()}")
      
      futures << executor.submit({
        def chunkNum = index + 1
        def startTime = System.currentTimeMillis()
        
        try {
          apiRateLimiter.acquire()
          def result = robustOllamaCall(chunk.content, chunkNum, chunks.size())
          def elapsed = ((System.currentTimeMillis() - startTime) / 1000).round(1)
          
          return [
            index: index,
            success: result.issues != null,
            issues: result.issues,
            chunkNum: chunkNum,
            elapsed: elapsed,
            issueCount: result.issues?.size() ?: 0,
            logs: result.logs ?: []
          ]
        } catch (Exception e) {
          def elapsed = ((System.currentTimeMillis() - startTime) / 1000).round(1)
          
          // Collect error details for main thread logging
          def errorDetails = [
            chunkNum: chunkNum,
            elapsed: elapsed,
            error: e.message,
            exceptionType: e.class.name,
            stackTrace: e.stackTrace.take(3).collect { it.toString() },
            chunkSize: chunk.content?.length() ?: 0
          ]
          
          try {
            if (chunk.content) {
              def result = extractFilesFromChunk(chunk.content)
              errorDetails.chunkFiles = result.files
              errorDetails.modifiedLines = result.modifiedLines
              errorDetails.chunkPreview = chunk.content.take(500)
            }
          } catch (Exception ex) {
            errorDetails.analysisError = ex.message
          }
          
          return [
            index: index,
            success: false,
            issues: null,
            error: e.message,
            errorDetails: errorDetails
          ]
        }
      } as Callable)
    }
    
    // Collect results with timeout
    futures.eachWithIndex { future, index ->
      def chunkNum = index + 1
      try {
        log.warn("AI Review: [PARALLEL DEBUG] Waiting for chunk ${chunkNum} result...")
        def result = future.get(OLLAMA_TIMEOUT + 10000, TimeUnit.MILLISECONDS)
        
        if (result.success) {
          log.warn("AI Review: [PARALLEL DEBUG] Chunk ${result.chunkNum} SUCCESS in ${result.elapsed}s: ${result.issueCount} issues")
          // Log any debug info from callOllama/robustOllamaCall
          result.logs?.each { logMsg ->
            if (logMsg.contains('[FAIL]') || logMsg.contains('[PARSE FAIL]')) {
              log.error("AI Review: [CHUNK ${result.chunkNum}] ${logMsg}")
            } else {
              log.warn("AI Review: [CHUNK ${result.chunkNum}] ${logMsg}")
            }
          }
        } else if (result.errorDetails) {
          def ed = result.errorDetails
          log.error("AI Review: [PARALLEL FAIL] ========== CHUNK ${ed.chunkNum} FAILED IN ${ed.elapsed}s ==========")
          log.error("AI Review: [PARALLEL FAIL] Error: ${ed.error}")
          log.error("AI Review: [PARALLEL FAIL] Exception: ${ed.exceptionType}")
          log.error("AI Review: [PARALLEL FAIL] Stack: ${ed.stackTrace.join(' -> ')}")
          log.error("AI Review: [PARALLEL FAIL] Chunk size: ${ed.chunkSize} chars")
          if (ed.chunkFiles) {
            log.error("AI Review: [PARALLEL FAIL] Files: ${ed.chunkFiles}")
            log.error("AI Review: [PARALLEL FAIL] Modified lines: ${ed.modifiedLines}")
            log.error("AI Review: [PARALLEL FAIL] Preview: ${ed.chunkPreview}...")
          }
          if (ed.analysisError) {
            log.error("AI Review: [PARALLEL FAIL] Analysis error: ${ed.analysisError}")
          }
          log.error("AI Review: [PARALLEL FAIL] ========== END CHUNK ${ed.chunkNum} FAILURE ==========")
        }
        
        results << result
      } catch (TimeoutException e) {
        log.error("AI Review: [PARALLEL TIMEOUT] ========== CHUNK ${chunkNum} TIMEOUT ==========")
        log.error("AI Review: [PARALLEL TIMEOUT] Chunk ${chunkNum} timed out after ${OLLAMA_TIMEOUT + 10000}ms")
        log.error("AI Review: [PARALLEL TIMEOUT] This may indicate Ollama is overloaded or unresponsive")
        log.error("AI Review: [PARALLEL TIMEOUT] ========== END CHUNK ${chunkNum} TIMEOUT DEBUG ==========")
        results << [index: index, success: false, issues: null, error: "Timeout"]
      } catch (Exception e) {
        log.error("AI Review: [PARALLEL FAIL] ========== CHUNK ${chunkNum} FUTURE GET FAILED ==========")
        log.error("AI Review: [PARALLEL FAIL] Error getting future result: ${e.message}")
        log.error("AI Review: [PARALLEL FAIL] Exception type: ${e.class.name}")
        log.error("AI Review: [PARALLEL FAIL] ========== END CHUNK ${chunkNum} FUTURE FAILURE DEBUG ==========")
        results << [index: index, success: false, issues: null, error: e.message]
      }
    }
    
  } finally {
    executor.shutdown()
    try {
      if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
        executor.shutdownNow()
      }
    } catch (InterruptedException e) {
      executor.shutdownNow()
    }
  }
  
  return results.sort { it.index }
}

def robustOllamaCall(String chunkContent, int chunkIndex, int totalChunks) {
  def attempt = 0
  def lastError = null
  def logs = []
  def result = [issues: null, logs: logs]
  
  // Ana modelle deneme
  for (int i = 0; i < 2; i++) {
    try {
      logs << "[ROBUST] Attempting with primary model (attempt ${i + 1})"
      def response = ollamaCircuitBreaker.execute {
        callOllama(chunkContent, chunkIndex, totalChunks, OLLAMA_MODEL)
      }
      if (response.logs) logs.addAll(response.logs)
      if (response.issues != null) {
        logs << "[ROBUST] Primary model succeeded with ${response.issues.size()} issues"
        result = [issues: response.issues, logs: logs]
        break // Başarılı olduğunda döngüden çık
      } else {
        logs << "[ROBUST] Primary model returned null issues"
        lastError = new RuntimeException("Primary model returned null issues")
      }
    } catch (Exception e) {
      lastError = e
      logs << "[ROBUST] Attempt ${i + 1} failed with ${OLLAMA_MODEL}: ${e.message}"
      if (i < 1) {
        def delay = Math.pow(2, i) * BASE_RETRY_DELAY_MS
        logs << "[ROBUST] Retrying in ${delay}ms..."
        Thread.sleep(delay as long)
      }
    }
  }
  
  // Eğer ana model başarısız olduysa ve yedek model varsa
  if (result.issues == null && FALLBACK_MODEL && FALLBACK_MODEL != OLLAMA_MODEL) {
    logs << "[ROBUST] Trying fallback model: ${FALLBACK_MODEL}"
    try {
      def response = callOllama(chunkContent, chunkIndex, totalChunks, FALLBACK_MODEL)
      if (response.logs) logs.addAll(response.logs)
      if (response.issues != null) {
        logs << "[ROBUST] Fallback model succeeded with ${response.issues.size()} issues"
        result = [issues: response.issues, logs: logs]
      } else {
        logs << "[ROBUST] Fallback model returned null issues"
        lastError = new RuntimeException("Fallback model returned null issues")
      }
    } catch (Exception e) {
      logs << "[ROBUST] Fallback model failed: ${e.message}"
      lastError = e
    }
  }
  
  // Her iki model de başarısız olduysa ve bir hata varsa
  if (result.issues == null && lastError != null) {
    logs << "[ROBUST] All attempts failed. Last error: ${lastError.message}"
    throw new RuntimeException("Failed to process chunk after all attempts: ${lastError.message}", lastError)
  }
  
  result.logs = logs // Son logları ekle
  return result
}

def smartChunkDiff(String diffText, Set<String> filesToReview) {
  try {
    def diffData = jsonParser.parseText(diffText)
    def chunks = []
    def currentChunk = [diffs: []]
    def currentSize = 0
    def currentFileCount = 0
    def MAX_FILES_PER_CHUNK = 3 // Chunk başına maksimum dosya sayısı
    def MAX_CHARS_PER_CHUNK = 60000 // Maksimum karakter sayısı
    
    // Önce dosyaları boyutlarına göre sırala
    def sortedDiffs = diffData.diffs?.sort { diff ->
      def content = JsonOutput.toJson(diff)
      -1 * content.length() // Büyükten küçüğe sırala
    }
    
    sortedDiffs?.each { diff ->
      def filePath = diff.destination?.toString ?: diff.source?.toString
      
      if (filePath && !filesToReview.contains(filePath)) {
        return
      }
      
      def diffContent = JsonOutput.toJson(diff)
      def diffSize = diffContent.length()
      
      // Eğer tek bir diff bile chunk limitini aşıyorsa, onu parçala
      if (diffSize > MAX_CHARS_PER_CHUNK) {
        def subChunks = splitLargeDiff(diff)
        subChunks.each { subChunk ->
          chunks << [content: JsonOutput.toJson([diffs: [subChunk]])]
        }
        return
      }
      
      // Normal durumda chunking
      if ((currentSize + diffSize > MAX_CHARS_PER_CHUNK) || 
          (currentFileCount >= MAX_FILES_PER_CHUNK && currentChunk.diffs)) {
        if (currentChunk.diffs) {
          chunks << [content: JsonOutput.toJson([diffs: currentChunk.diffs])]
          currentChunk = [diffs: []]
          currentSize = 0
          currentFileCount = 0
        }
      }
      
      currentChunk.diffs << diff
      currentSize += diffSize
      currentFileCount++
      
      log.warn("AI Review: Added file ${filePath} to chunk (size: ${diffSize}, total: ${currentSize})")
    }
    
    if (currentChunk.diffs) {
      chunks << [content: JsonOutput.toJson([diffs: currentChunk.diffs])]
    }
    
    def result = chunks.take(20) // Maksimum 20 chunk
    log.warn("AI Review: Created ${result.size()} chunks")
    result.eachWithIndex { chunk, idx ->
      def chunkResult = extractFilesFromChunk(chunk.content)
      log.warn("AI Review: Chunk ${idx + 1}: size=${chunk.content.length()}, files=${chunkResult.files}, modifiedLines=${chunkResult.modifiedLines}")
    }
    return result
  } catch (Exception e) {
    log.error("AI Review: Failed to chunk JSON diff: ${e.message}", e)
    log.error("AI Review: Stack trace: ${e.stackTrace.take(5).join('\n')}")
    return [[content: diffText]]
  }
}

def splitLargeDiff(diff) {
  def chunks = []
  def currentHunks = []
  def currentSize = 0
  
  diff.hunks?.each { hunk ->
    def hunkContent = JsonOutput.toJson(hunk)
    def hunkSize = hunkContent.length()
    
    if (currentSize + hunkSize > MAX_CHARS_PER_CHUNK && currentHunks) {
      chunks << createDiffWithHunks(diff, currentHunks)
      currentHunks = []
      currentSize = 0
    }
    
    currentHunks << hunk
    currentSize += hunkSize
  }
  
  if (currentHunks) {
    chunks << createDiffWithHunks(diff, currentHunks)
  }
  
  return chunks
}

def createDiffWithHunks(originalDiff, hunks) {
  def newDiff = [:]
  newDiff.putAll(originalDiff)
  newDiff.hunks = hunks
  return newDiff
}

def calculateHash(String content) {
  MessageDigest.getInstance("MD5")
    .digest(content.bytes)
    .encodeHex()
    .toString()
}

def callOllama(String chunk, int chunkIndex, int totalChunks, String model = OLLAMA_MODEL) {
  def logs = []
  def chunkHash = calculateHash(chunk).take(8)
  logs << "[OLLAMA] Starting callOllama for chunk ${chunkIndex}/${totalChunks} with model ${model} (hash: ${chunkHash})"
  logs << "[OLLAMA] Chunk size: ${chunk.length()} chars"
  
  // Response validasyon fonksiyonu
  def validateResponse = { resp ->
    if (!resp) {
      logs << "[OLLAMA] Null response received"
      throw new RuntimeException("Null response from Ollama")
    }
    if (!resp.message) {
      logs << "[OLLAMA] No message in response"
      throw new RuntimeException("No message in Ollama response")
    }
    def content = resp.message.content?.trim()
    if (!content) {
      logs << "[OLLAMA] Empty content in response"
      throw new RuntimeException("Empty content in Ollama response")
    }
    return content
  }
  
  def systemPrompt = """You are an expert senior software engineer performing a comprehensive code review. ONLY analyze NEW or MODIFIED code, marked with '+' prefix in the diff.

⚠️ CRITICAL RULES:
1. ONLY analyze lines starting with '+' (new/modified code)
2. COMPLETELY IGNORE lines with '-' or ' ' prefix (old/unchanged code)
3. NEVER report issues in unchanged code sections
4. ONLY use EXACT file paths from the provided diff
5. VERIFY line numbers correspond to the new file version

CRITICAL ANALYSIS AREAS:
🔴 SECURITY: SQL injection, XSS, CSRF, authentication bypass, authorization flaws, input validation, data exposure, cryptographic issues
🔴 BUGS & LOGIC: Null pointer exceptions, array bounds, race conditions, deadlocks, infinite loops, incorrect algorithms, edge cases
🔴 PERFORMANCE: Memory leaks, inefficient queries, O(n²) algorithms, unnecessary computations, resource exhaustion
🔴 RELIABILITY: Error handling, exception management, transaction integrity, data consistency, retry logic
🔴 MAINTAINABILITY: Code complexity, duplicated logic, tight coupling, missing documentation, unclear naming
🔴 TESTING: Missing test coverage, inadequate assertions, test data issues, mock problems

SEVERITY GUIDELINES:
- CRITICAL: Security vulnerabilities, data corruption, system crashes, production outages
- HIGH: Logic errors, performance bottlenecks, reliability issues, significant bugs
- MEDIUM: Code quality issues, maintainability problems, minor performance issues
- LOW: Style improvements, documentation gaps, minor optimizations

BE THOROUGH - even small changes can introduce significant issues."""

  def userPrompt = """COMPREHENSIVE CODE REVIEW REQUEST

Repository: ${project}/${slug}
Pull Request: #${prId}
Analyzing chunk ${chunkIndex} of ${totalChunks}

PERFORM DETAILED LINE-BY-LINE ANALYSIS:

===DIFF START===
${chunk}
===DIFF END===

AVAILABLE FILES IN THIS DIFF:
${extractFilesFromChunk(chunk).files.join('\n')}

You MUST only report issues for files listed above. Do not invent or guess file paths.

CRITICAL INSTRUCTIONS:
1. ONLY analyze lines that start with '+' (additions) - these are the NEW/CHANGED code
2. IGNORE lines that start with '-' (deletions) or ' ' (context) - these are old/unchanged code
3. Focus exclusively on potential issues in the ADDED/MODIFIED lines
4. Report line numbers based on the NEW file version (after changes)
5. Consider the broader context but only flag issues in the changed code
6. Look for subtle bugs that could cause runtime failures in NEW code
7. Identify security vulnerabilities in ADDED lines
8. Check for performance implications of NEW changes

IMPORTANT FILE PATH RULES:
- ONLY use file paths that appear EXACTLY in the diff above
- DO NOT invent, guess, or modify file paths
- DO NOT use similar or related file paths
- If you see 'webui/src/default/login.tsx', use EXACTLY that path
- DO NOT change it to 'web/src/main/webapp/app/components/LoginComponent.tsx' or any other variation

Provide detailed findings in JSON format with EXACT file paths from the diff and specific line numbers for CHANGED CODE ONLY.

For each issue, include the 'problematicCode' field with the EXACT code snippet that has the problem (copy it exactly from the diff above)."""

  def requestBody = [
    model: model,
    stream: false,
    format: [
      type: "object",
      properties: [
        issues: [
          type: "array",
          items: [
            type: "object",
            properties: [
              path: [type: "string"],
              line: [type: "integer"],
              severity: [type: "string", enum: ["low", "medium", "high", "critical"]],
              type: [type: "string"],
              summary: [type: "string"],
              details: [type: "string"],
              fix: [type: "string"],
              problematicCode: [type: "string"],
            ],
            required: ["path", "line", "summary"]
          ]
        ]
      ],
      required: ["issues"]
    ],
    messages: [
      [role: "system", content: systemPrompt],
      [role: "user", content: userPrompt],
    ]
  ]
  logs << "[OLLAMA] Chunk ${chunkIndex} size: ${chunk.length()} chars"
  
  try {
    logs << "[OLLAMA] Sending request to Ollama for chunk ${chunkIndex}"
    def response = httpRequest(
      "${OLLAMA_URL}/api/chat",
      'POST',
      null,
      JsonOutput.toJson(requestBody),
      OLLAMA_TIMEOUT,
      MAX_RETRIES
    )
    
    logs << "[OLLAMA] Received response from Ollama for chunk ${chunkIndex}, status: ${response.statusCode}"
    def resp = jsonParser.parseText(response.body)
    
    def content = validateResponse(resp)
    logs << "[OLLAMA] Response content length: ${content.length()} chars"
    logs << "[OLLAMA] First 100 chars of response: ${content.take(100)}"
    
    def validated = jsonParser.parseText(content)
    if (!(validated?.issues instanceof List)) {
        logs << "[OLLAMA] Invalid JSON from chunk ${chunkIndex}"
        return [issues: null, logs: logs]
      }
      
      logs << "[OLLAMA] Raw AI response has ${validated.issues.size()} issues"
      
      // Extract actual file paths from the chunk to validate AI responses
      def result = extractFilesFromChunk(chunk)
      logs << "[OLLAMA] Actual files in chunk ${chunkIndex}: ${result.files}"
      
      // Log AI's reported file paths for comparison
      def aiReportedFiles = validated.issues.collect { it.path }.unique()
      logs << "[OLLAMA] AI reported files: ${aiReportedFiles}"
      
      // Filter out issues for files that don't exist in the actual diff
      def validIssues = validated.issues.findAll { issue ->
        // 1. Dosya yolu kontrolü
        def issuePath = issue.path
        if (!issuePath || !result.files.contains(issuePath)) {
          logs << "[OLLAMA] Filtering out issue - invalid file path: ${issuePath}"
          return false
        }

        // 2. Satır numarası kontrolü
        if (issue.line != null) {
          try {
            def lineNumber = issue.line as Integer
            if (lineNumber <= 0) {
              logs << "[OLLAMA] Filtering out issue - invalid line number: ${lineNumber}"
              return false
            }

            // 3. Değiştirilmiş kod kontrolü
            def isModifiedLine = isLineModified(chunk, issuePath, lineNumber)
            if (!isModifiedLine) {
              logs << "[OLLAMA] Filtering out issue - line ${lineNumber} is not modified in file ${issuePath}"
              return false
            }
          } catch (Exception e) {
            logs << "[OLLAMA] Filtering out issue - line number error: ${e.message}"
            return false
          }
        }

        // 4. Sorun açıklaması kontrolü
        if (!issue.summary?.trim()) {
          logs << "[OLLAMA] Filtering out issue - empty summary"
          return false
        }

        return true
      }
      
      logs << "[OLLAMA] Chunk ${chunkIndex} completed with ${validIssues.size()} valid issue(s) (filtered from ${validated.issues.size()})"
      return [issues: validIssues, logs: logs]
  } catch (Exception e) {
    if (e instanceof RuntimeException && e.message.contains("JSON")) {
      logs << "[PARSE FAIL] JSON parse failed for chunk ${chunkIndex}: ${e.message}"
      logs << "[PARSE FAIL] Exception type: ${e.class.name}"
      if (content) {
        logs << "[PARSE FAIL] Content length: ${content.length()} chars"
        logs << "[PARSE FAIL] Raw content (first 500 chars): ${content.take(500)}"
      }
      logs << "[PARSE FAIL] Model: ${model}"
      return [issues: null, logs: logs]
    }
    logs << "[FAIL] ========== CHUNK ${chunkIndex} ANALYSIS FAILED =========="
    logs << "[FAIL] Error: ${e.message}"
    logs << "[FAIL] Exception type: ${e.class.name}"
    logs << "[FAIL] Stack trace: ${e.stackTrace.take(5).join('\n')}"
    logs << "[FAIL] Model used: ${model}"
    logs << "[FAIL] Chunk hash: ${chunkHash}"
    logs << "[FAIL] Chunk size: ${chunk.length()} chars"
    logs << "[FAIL] Ollama URL: ${OLLAMA_URL}"
    logs << "[FAIL] Timeout: ${OLLAMA_TIMEOUT}ms"
    logs << "[FAIL] Response preview (if any): ${e.hasProperty('response') ? e.response?.take(500) : 'No response'}"
    
    // Try to extract files from failed chunk for context
    try {
      def result = extractFilesFromChunk(chunk)
      logs << "[FAIL] Files in failed chunk: ${result.files}"
      logs << "[FAIL] Modified lines: ${result.modifiedLines}"
    } catch (Exception ex) {
      logs << "[FAIL] Could not extract files from failed chunk: ${ex.message}"
    }
    
    throw e
  }
}

def extractFilesFromChunk(String chunk) {
  def files = [] as Set
  def modifiedLines = [:] as Map
  try {
    def chunkData = jsonParser.parseText(chunk)
    chunkData.diffs?.each { diff ->
      def filePath = diff.destination?.toString ?: diff.source?.toString
      if (filePath) {
        files << filePath
        modifiedLines[filePath] = [] as Set
        
        // Her dosya için değiştirilmiş satırları topla
        diff.hunks?.each { hunk ->
          hunk.segments?.each { segment ->
            if (segment.type == 'ADDED') {
              segment.lines?.each { line ->
                if (line.destination) {
                  modifiedLines[filePath] << line.destination
                }
              }
            }
          }
        }
      }
    }
    
    // Log detaylı bilgi
    files.each { file ->
      def lineCount = modifiedLines[file]?.size() ?: 0
      log.warn("AI Review: File '${file}' has ${lineCount} modified lines: ${modifiedLines[file]}")
    }
    
  } catch (Exception e) {
    log.warn("AI Review: Failed to extract files from chunk: ${e.message}")
  }
  return [files: files, modifiedLines: modifiedLines]
}

def isLineModified(String chunk, String filePath, Integer lineNumber) {
  try {
    def extracted = extractFilesFromChunk(chunk)
    def modifiedLines = extracted.modifiedLines[filePath]
    return modifiedLines?.contains(lineNumber) ?: false
  } catch (Exception e) {
    log.warn("AI Review: Failed to check if line is modified: ${e.message}")
    return false
  }
}

def postIssueComments(List<Map> allIssues, Long summaryCommentId, ReviewProfile profile, String diffText) {
  def issuesToPost = allIssues.take(Math.min(MAX_ISSUE_COMMENTS, profile.maxIssuesPerFile))
  int commentsCreated = 0
  int commentsFailed = 0
  def commentStartTime = System.currentTimeMillis()
  
  issuesToPost.eachWithIndex { issue, index ->
    try {
      apiRateLimiter.acquire()
      log.warn("AI Review: 🔗 [${index + 1}/${issuesToPost.size()}] Posting issue as REPLY to ${summaryCommentId}")
      def result = addIssueCommentAsReply(issue, index + 1, allIssues.size(), summaryCommentId, diffText)
      
      if (result.success) {
        commentsCreated++
      } else {
        commentsFailed++
      }
    } catch (Exception e) {
      log.error("AI Review: Failed to post comment ${index + 1}: ${e.message}")
      commentsFailed++
    }
    
    if (index < issuesToPost.size() - 1) {
      Thread.sleep(API_DELAY_MS)
    }
  }
  
  def commentElapsedTime = ((System.currentTimeMillis() - commentStartTime) / 1000).round(1)
  
  log.warn("AI Review: ✅ Finished: ${commentsCreated} replies created, ${commentsFailed} failed in ${commentElapsedTime}s")
  return commentsCreated
}

def handleNoIssuesFound(isUpdate, previousIssues, elapsedTime, failedChunks, wasTruncated, progressCommentId, progressCommentVersion, pr, chunks) {
  log.warn("AI Review: No issues found, posting approval")
  
  if (isUpdate && previousIssues && previousIssues.size() > 0) {
    log.warn("AI Review: All ${previousIssues.size()} previous issues have been resolved! Marking them...")
    
    int markedCount = 0
    previousIssues.each { issue ->
      if (markIssueAsResolved(issue, [])) {
        markedCount++
      }
    }
    
    log.warn("AI Review: Successfully marked ${markedCount}/${previousIssues.size()} issues as resolved")
    postResolvedComment(previousIssues)
  }
  
  def comment = "✅ **AI Code Review** – No issues found\n\n"
  
  if (isUpdate && previousIssues && previousIssues.size() > 0) {
    comment += "🎉 **All ${previousIssues.size()} previous issue(s) have been resolved!**\n\n"
    comment += "_Original issue comments have been updated with ✅ RESOLVED markers._\n\n"
  }
  
  comment += "Model: ${OLLAMA_MODEL}\n"
  comment += "Analysis time: ${elapsedTime}s\n"
  
  if (failedChunks > 0) {
    comment += "\n⚠️ **Note**: ${failedChunks} chunk(s) failed to analyze (may need manual review)"
  }
  
  if (wasTruncated) {
    comment += "\n\n⚠️ **Note**: This PR is very large. Only the first ${MAX_CHUNKS} chunks were analyzed."
  }
  
  def metadataJson = JsonOutput.toJson([
    timestamp: System.currentTimeMillis(),
    commitId: pr.fromRef.latestCommit,
    issues: [],
    chunks: chunks?.size() ?: 0,
    failedChunks: failedChunks,
    metrics: metrics.getMetrics()
  ])
  comment += "\n\n<!-- AI_REVIEW_METADATA:${metadataJson}-->"
  
  if (progressCommentId) {
    updatePRComment(progressCommentId, comment, progressCommentVersion)
  } else {
    addPRComment(comment)
  }
}

def buildSummaryComment(allIssues, resolvedIssues, newIssues, isUpdate, fileChanges, elapsedTime, failedChunks, wasTruncated, pr, chunks) {
  def byFile = allIssues.groupBy { it.path ?: "(multiple files)" }
  def bySeverity = allIssues.groupBy { it.severity ?: "medium" }
  
  def criticalCount = bySeverity.critical?.size() ?: 0
  def highCount = bySeverity.high?.size() ?: 0
  def mediumCount = bySeverity.medium?.size() ?: 0
  def lowCount = bySeverity.low?.size() ?: 0

  StringBuilder md = new StringBuilder()
  
  if (criticalCount > 0 || highCount > 0) {
    md << "🚫 **AI Code Review** – ${allIssues.size()} finding(s) - **CHANGES REQUIRED**\n\n"
    md << "> ⚠️ This PR has **${criticalCount + highCount} critical/high severity issue(s)** that must be addressed before merging.\n\n"
  } else {
    md << "⚠️ **AI Code Review** – ${allIssues.size()} finding(s) - **Review Recommended**\n\n"
    md << "> ℹ️ This PR has only medium/low severity issues. You may merge after review, but consider addressing these improvements.\n\n"
  }
  
  if (isUpdate && (resolvedIssues.size() > 0 || newIssues.size() > 0)) {
    md << "### 🔄 Update Summary\n"
    if (resolvedIssues.size() > 0) {
      md << "- ✅ **${resolvedIssues.size()} issue(s) resolved** (marked in original comments)\n"
    }
    if (newIssues.size() > 0) {
      md << "- 🆕 **${newIssues.size()} new issue(s) introduced**\n"
    }
    def persistentIssues = allIssues.size() - newIssues.size()
    if (persistentIssues > 0) {
      md << "- ⚠️ **${persistentIssues} existing issue(s) remain**\n"
    }
    md << "\n"
  }
  
  md << "### Summary\n"
  md << "| Severity | Count |\n"
  md << "|----------|-------|\n"
  if (criticalCount > 0) md << "| 🔴 Critical | ${criticalCount} |\n"
  if (highCount > 0) md << "| 🟠 High | ${highCount} |\n"
  if (mediumCount > 0) md << "| 🟡 Medium | ${mediumCount} |\n"
  if (lowCount > 0) md << "| 🔵 Low | ${lowCount} |\n"
  md << "\n"
  
  md << "### 📁 File-Level Changes\n\n"
  
  if (!fileChanges.isEmpty()) {
    def sortedFiles = fileChanges.sort { -1 * (it.value.additions + it.value.deletions) }
    
    md << "| File | +Added | -Deleted | Issues |\n"
    md << "|------|--------|----------|--------|\n"
    
    sortedFiles.take(20).each { fileName, stats ->
      def issuesInFile = byFile[fileName]?.size() ?: 0
      def issueIcon = issuesInFile > 0 ? "⚠️" : "✓"
      
      md << "| `${fileName}` | +${stats.additions} | -${stats.deletions} | ${issueIcon} ${issuesInFile} |\n"
    }
    
    if (sortedFiles.size() > 20) {
      md << "| _...and ${sortedFiles.size() - 20} more files_ | | | |\n"
    }
    
    md << "\n"
    
    def totalAdditions = fileChanges.values().sum { it.additions } ?: 0
    def totalDeletions = fileChanges.values().sum { it.deletions } ?: 0
    md << "**Total Changes:** +${totalAdditions} additions, -${totalDeletions} deletions across ${fileChanges.size()} file(s)\n\n"
  } else {
    md << "_No file change statistics available (diff parsing may have failed)_\n\n"
  }
  
  md << "### Issues by File\n\n"
  
  byFile.take(10).each { file, items ->
    md << "#### `${file}`\n"
    items.take(MAX_ISSUES_PER_FILE).each { i ->
      def loc = (i.line ? "L${i.line}" : "")
      def sev = (i.severity ?: "medium").toUpperCase()
      def icon = ["critical": "🔴", "high": "🟠", "medium": "🟡", "low": "🔵"][i.severity] ?: "⚪"
      
      md << "- ${icon} **${sev}** ${loc} — *${i.type ?: 'issue'}*: ${i.summary}\n"
      if (i.details && i.details.length() < 200) {
        md << "  \n  ${i.details}\n"
      }
    }
    if (items.size() > MAX_ISSUES_PER_FILE) {
      md << "\n_...and ${items.size() - MAX_ISSUES_PER_FILE} more issue(s) in this file._\n"
    }
    md << "\n"
  }
  
  if (byFile.size() > 10) {
    md << "_...and issues in ${byFile.size() - 10} more file(s)._\n\n"
  }
  
  md << "---\n"
  md << "_Model: ${OLLAMA_MODEL} • Analysis time: ${elapsedTime}s_\n\n"
  
  if (criticalCount > 0 || highCount > 0) {
    md << "**🚫 PR Status:** Changes required before merge (${criticalCount} critical, ${highCount} high severity)\n\n"
  } else {
    md << "**✅ PR Status:** May merge after review (only medium/low issues)\n\n"
  }
  
  if (failedChunks > 0) {
    md << "⚠️ **Warning**: ${failedChunks} chunk(s) failed to analyze - some issues may be missing.\n\n"
  }
  
  if (wasTruncated) {
    md << "⚠️ **Note**: This PR is very large. Only the first ${MAX_CHUNKS} chunks were analyzed.\n\n"
  }
  
  md << "📝 **Detailed AI-generated explanations** will be posted as replies to this comment.\n\n"
  
  def issueMetadata = allIssues.collect { issue ->
    [
      path: issue.path,
      line: issue.line,
      severity: issue.severity,
      type: issue.type,
      summary: issue.summary
    ]
  }
  
  def metadataJson = JsonOutput.toJson([
    timestamp: System.currentTimeMillis(),
    commitId: pr.fromRef.latestCommit,
    issues: issueMetadata,
    chunks: chunks?.size() ?: 0,
    failedChunks: failedChunks,
    metrics: metrics.getMetrics()
  ])
  md << "\n\n<!-- AI_REVIEW_METADATA:${metadataJson}-->"
  
  return md.toString()
}

def updateSummaryWithReplyCount(summaryCommentId, commentsCreated, totalIssues, originalText) {
  if (!summaryCommentId || commentsCreated == 0) return
  
  try {
    log.warn("AI Review: Updating summary to show ${commentsCreated} replies")
    
    def commentUrl = "${baseUrl}/rest/api/1.0/projects/${project}/repos/${slug}/pull-requests/${prId}/comments/${summaryCommentId}"
    def response = httpRequest(commentUrl, 'GET', BB_BASIC_AUTH, null, READ_TIMEOUT, 1)
    def currentComment = jsonParser.parseText(response.body)
    def currentVersion = currentComment.version
    
    def updatedText = originalText.replace(
      "📝 **Detailed AI-generated explanations** will be posted as replies to this comment.",
      "📝 **${commentsCreated} detailed AI-generated explanation(s)** are posted as replies below."
    )
    
    if (totalIssues > MAX_ISSUE_COMMENTS) {
      updatedText = updatedText.replace(
        "<!-- AI_REVIEW_METADATA:",
        "\n\n⚠️ ${totalIssues - MAX_ISSUE_COMMENTS} additional issues don't have detailed replies.\n\n---\n_💡 Expand the replies below for detailed analysis._\n\n<!-- AI_REVIEW_METADATA:"
      )
    } else {
      updatedText = updatedText.replace(
        "<!-- AI_REVIEW_METADATA:",
        "\n\n---\n_💡 Expand the replies below for detailed analysis._\n\n<!-- AI_REVIEW_METADATA:"
      )
    }
    
    def updateBody = JsonOutput.toJson([text: updatedText, version: currentVersion])
    httpRequest(commentUrl, 'PUT', REVIEWER_BOT_AUTH, updateBody, READ_TIMEOUT, 2)
    
    log.warn("AI Review: ✅ Summary updated")
  } catch (Exception e) {
    log.warn("AI Review: Failed to update summary: ${e.message}")
  }
}

// [Keep all the original helper functions that weren't modified like httpRequest, addPRComment, etc.]
// Including just the key ones here for brevity:

def httpRequest(String url, String method, String auth, String body = null, int timeout = READ_TIMEOUT, int maxRetries = 1) {
  def lastException = null
  
  for (int attempt = 1; attempt <= maxRetries; attempt++) {
    try {
      def conn = new URL(url).openConnection()
      conn.setConnectTimeout(CONNECT_TIMEOUT)
      conn.setReadTimeout(timeout)
      conn.setRequestMethod(method)
      
      if (auth) {
        conn.setRequestProperty('Authorization', "Basic " + auth.bytes.encodeBase64().toString())
      }
      
      if (body) {
        conn.setDoOutput(true)
        conn.setRequestProperty('Content-Type', 'application/json')
        conn.outputStream.withWriter('UTF-8') { it << body }
      }
      
      if (conn.responseCode >= 400) {
        def error = conn.errorStream?.getText('UTF-8') ?: "Unknown error"
        throw new RuntimeException("HTTP ${conn.responseCode}: ${error}")
      }
      
      return [
        statusCode: conn.responseCode,
        body: conn.inputStream.getText('UTF-8')
      ]
    } catch (Exception e) {
      lastException = e
      if (attempt < maxRetries) {
        def delayMs = BASE_RETRY_DELAY_MS * Math.pow(2, attempt - 1)
        Thread.sleep((int)delayMs)
      }
    }
  }
  
  throw new RuntimeException("Request failed after ${maxRetries} attempts: ${lastException.message}", lastException)
}

def addPRComment(String text) {
  try {
    apiRateLimiter.acquire()
    def commentUrl = "${baseUrl}/rest/api/1.0/projects/${project}/repos/${slug}/pull-requests/${prId}/comments"
    def commentBody = JsonOutput.toJson([text: text])
    
    def response = httpRequest(commentUrl, 'POST', REVIEWER_BOT_AUTH, commentBody, READ_TIMEOUT, 2)
    def comment = jsonParser.parseText(response.body)
    
    log.warn("AI Review: Comment posted, ID: ${comment.id}, version: ${comment.version}")
    return comment
  } catch (Exception e) {
    log.error("AI Review: Failed to post comment: ${e.message}", e)
    throw e
  }
}

def replyToComment(Long parentCommentId, String text) {
  try {
    apiRateLimiter.acquire()
    log.warn("AI Review: 🔗🔗🔗 POSTING REPLY to parent ${parentCommentId}")
    def commentUrl = "${baseUrl}/rest/api/1.0/projects/${project}/repos/${slug}/pull-requests/${prId}/comments"
    def commentBody = JsonOutput.toJson([
      text: text,
      parent: [id: parentCommentId]
    ])
    
    def response = httpRequest(commentUrl, 'POST', REVIEWER_BOT_AUTH, commentBody, READ_TIMEOUT, 2)
    def comment = jsonParser.parseText(response.body)
    
    log.warn("AI Review: ✅✅✅ Reply posted! Parent: ${parentCommentId}, New ID: ${comment.id}")
    return comment
  } catch (Exception e) {
    log.error("AI Review: ❌❌❌ REPLY FAILED to parent ${parentCommentId}: ${e.message}", e)
    throw e
  }
}

def updatePRComment(Long commentId, String text, int version) {
  try {
    apiRateLimiter.acquire()
    def commentUrl = "${baseUrl}/rest/api/1.0/projects/${project}/repos/${slug}/pull-requests/${prId}/comments/${commentId}"
    def commentBody = JsonOutput.toJson([text: text, version: version])
    
    def response = httpRequest(commentUrl, 'PUT', REVIEWER_BOT_AUTH, commentBody, READ_TIMEOUT, 2)
    def updated = jsonParser.parseText(response.body)
    log.warn("AI Review: Updated comment ${commentId}, version: ${version} -> ${updated.version}")
    return updated.version
  } catch (Exception e) {
    log.warn("AI Review: Failed to update comment: ${e.message}")
    return version
  }
}

def validateOllama() {
  try {
    log.warn("AI Review: Validating Ollama")
    def response = httpRequest("${OLLAMA_URL}/api/tags", 'GET', null, null, 5000, 1)
    def tags = jsonParser.parseText(response.body)
    
    def modelFound = tags.models?.find { it.name.startsWith(OLLAMA_MODEL) }
    def fallbackFound = FALLBACK_MODEL ? tags.models?.find { it.name.startsWith(FALLBACK_MODEL) } : true
    
    if (!modelFound) {
      log.error("AI Review: Model ${OLLAMA_MODEL} not found")
      addPRComment("⚠️ AI Review: Model '${OLLAMA_MODEL}' not available.")
      return false
    }
    
    if (!fallbackFound) {
      log.warn("AI Review: Fallback model ${FALLBACK_MODEL} not found, continuing without fallback")
    }
    
    log.warn("AI Review: Ollama validation successful")
    return true
  } catch (Exception e) {
    log.error("AI Review: Cannot connect to Ollama: ${e.message}")
    addPRComment("⚠️ AI Review: Cannot connect to AI service at ${OLLAMA_URL}.")
    return false
  }
}

def approvePR() {
  try {
    def approveUrl = "${baseUrl}/rest/api/1.0/projects/${project}/repos/${slug}/pull-requests/${prId}/approve"
    httpRequest(approveUrl, 'POST', REVIEWER_BOT_AUTH, null, READ_TIMEOUT, 2)
    log.warn("AI Review: ✅ PR approved")
  } catch (Exception e) {
    log.warn("AI Review: Failed to approve: ${e.message}")
  }
}

def requestChanges() {
  try {
    def participantUrl = "${baseUrl}/rest/api/1.0/projects/${project}/repos/${slug}/pull-requests/${prId}/participants/${REVIEWER_BOT_USER}"
    def participantBody = JsonOutput.toJson([
      user: [name: REVIEWER_BOT_USER],
      status: "NEEDS_WORK"
    ])
    
    httpRequest(participantUrl, 'PUT', REVIEWER_BOT_AUTH, participantBody, READ_TIMEOUT, 2)
    log.warn("AI Review: ⚠️ Changes requested")
  } catch (Exception e) {
    log.warn("AI Review: Failed to request changes: ${e.message}")
  }
}

// [Include all other original helper functions like getPreviousIssues, generateIssueKey, 
// findResolvedIssues, findNewIssues, markIssueAsResolved, postResolvedComment, fetchDiff, 
// validatePRSize, analyzeDiffForSummary,
// getDetailedExplanation, addIssueCommentAsReply]
// These remain largely unchanged from your original implementation

def generateIssueKey(Map issue) {
  def path = issue.path ?: "unknown"
  def line = issue.line ?: 0
  def summary = issue.summary ?: ""
  return "${path}:${line}:${summary.take(50)}".toLowerCase().replaceAll(/[^a-z0-9:]/, '')
}

def findResolvedIssues(List oldIssues, List newIssues) {
  def oldKeys = oldIssues.collect { generateIssueKey(it) }.toSet()
  def newKeys = newIssues.collect { generateIssueKey(it) }.toSet()
  
  def resolvedKeys = oldKeys - newKeys
  def resolved = oldIssues.findAll { resolvedKeys.contains(generateIssueKey(it)) }
  
  log.warn("AI Review: Detected ${resolved.size()} resolved issue(s)")
  return resolved
}

def findNewIssues(List oldIssues, List newIssues) {
  def oldKeys = oldIssues.collect { generateIssueKey(it) }.toSet()
  def newlyFound = newIssues.findAll { !oldKeys.contains(generateIssueKey(it)) }
  
  log.warn("AI Review: Detected ${newlyFound.size()} new issue(s)")
  return newlyFound
}

def markIssueAsResolved(Map issue, List allComments) {
  try {
    def matchingComment = allComments.find { activity ->
      def commentText = activity.comment?.text ?: ""
      def path = issue.path ?: ""
      def summary = issue.summary ?: ""
      
      return commentText.contains(path) && commentText.contains(summary)
    }
    
    if (!matchingComment) {
      return false
    }
    
    def commentId = matchingComment.comment.id
    def commentVersion = matchingComment.comment.version
    def originalText = matchingComment.comment.text
    
    if (originalText.contains("✅ RESOLVED")) {
      return true
    }
    
    def resolvedBanner = """---
### ✅ RESOLVED
**This issue has been fixed!** 🎉

_Verified on ${new Date().format('yyyy-MM-dd HH:mm:ss')}_

---

"""
    def updatedText = resolvedBanner + originalText
    
    def commentUrl = "${baseUrl}/rest/api/1.0/projects/${project}/repos/${slug}/pull-requests/${prId}/comments/${commentId}"
    def commentBody = JsonOutput.toJson([text: updatedText, version: commentVersion])
    
    httpRequest(commentUrl, 'PUT', REVIEWER_BOT_AUTH, commentBody, READ_TIMEOUT, 2)
    log.warn("AI Review: ✅ Marked issue as RESOLVED, comment ${commentId}")
    return true
  } catch (Exception e) {
    log.warn("AI Review: Failed to mark issue as resolved: ${e.message}")
    return false
  }
}

def postResolvedComment(List resolvedIssues) {
  if (resolvedIssues.isEmpty()) return
  
  try {
    StringBuilder comment = new StringBuilder()
    comment << "✅ **Issues Resolved!**\n\n"
    comment << "The following ${resolvedIssues.size()} issue(s) have been fixed:\n\n"
    
    resolvedIssues.eachWithIndex { issue, idx ->
      def severityIcon = ["critical": "🔴", "high": "🟠", "medium": "🟡", "low": "🔵"][issue.severity] ?: "⚪"
      comment << "${idx + 1}. ${severityIcon} **${issue.severity?.toUpperCase()}** - "
      if (issue.path) {
        comment << "`${issue.path}`"
        if (issue.line) comment << " (L${issue.line})"
        comment << ": "
      }
      comment << "${issue.summary}\n"
    }
    
    comment << "\n---\n_🎉 Great work!_\n\n"
    comment << "_Original issue comments have been marked with ✅ RESOLVED._"
    
    addPRComment(comment.toString())
    log.warn("AI Review: ✅ Posted resolved issues comment")
  } catch (Exception e) {
    log.warn("AI Review: Failed to post resolved comment: ${e.message}")
  }
}

def analyzeDiffForSummaryUnified(String diffText) {
  def fileChanges = [:]
  
  try {
    def diffData = jsonParser.parseText(diffText)
    
    diffData.diffs?.each { diff ->
      def filePath = diff.destination?.toString ?: diff.source?.toString
      def additions = 0
      def deletions = 0
      
      diff.hunks?.each { hunk ->
        hunk.segments?.each { segment ->
          def lineCount = segment.lines?.size() ?: 0
          if (segment.type == 'ADDED') {
            additions += lineCount
          } else if (segment.type == 'REMOVED') {
            deletions += lineCount
          }
        }
      }
      
      if (filePath) {
        fileChanges[filePath] = [additions: additions, deletions: deletions]
      }
    }
  } catch (Exception e) {
    log.warn("AI Review: Failed to parse JSON diff: ${e.message}")
  }
  
  return fileChanges
}

def validatePRSize(String diff) {
  if (!diff) return [valid: true, message: null]
  
  def sizeBytes = diff.length()
  def lineCount = diff.count('\n')
  
  if (sizeBytes > MAX_DIFF_SIZE) {
    return [
      valid: false, 
      message: "PR diff is too large (${(sizeBytes/1024/1024).round(1)}MB). Maximum is ${(MAX_DIFF_SIZE/1024/1024)}MB."
    ]
  }
  
  if (lineCount > 10000) {
    log.warn("AI Review: Large PR (${lineCount} lines)")
  }
  
  return [valid: true, message: null, lines: lineCount, sizeMB: (sizeBytes/1024/1024).round(2)]
}

def analyzeDiffForSummary(String diffText) {
  def fileChanges = [:]
  
  try {
    def json = jsonParser.parseText(diffText)
    
    json.diffs?.each { diff ->
      def filePath = diff.source?.toString ?: diff.source?.components?.join('/') ?: 'unknown'
      def additions = 0
      def deletions = 0
      
      diff.hunks?.each { hunk ->
        hunk.segments?.each { segment ->
          def type = segment.type
          def lineCount = segment.lines?.size() ?: 0
          
          switch (type) {
            case "ADDED":
              additions += lineCount
              break
            case "REMOVED":
              deletions += lineCount
              break
          }
        }
      }
      
      fileChanges[filePath] = [additions: additions, deletions: deletions]
    }
    
    log.warn("AI Review: Parsed ${fileChanges.size()} file(s): ${fileChanges.keySet()}")
    if (fileChanges.isEmpty()) {
      log.warn("AI Review: No files parsed - diff format may be unexpected")
      log.warn("AI Review: First 500 chars of diff: ${diffText.take(500)}")
    }
  } catch (Exception e) {
    log.warn("AI Review: Failed to parse JSON diff: ${e.message}")
    // Fallback to unified diff parsing
    return analyzeDiffForSummaryUnified(diffText)
  }
  
  return fileChanges
}

def getDetailedExplanation(Map issue, String detailLevel, String diffText = null) {
  try {
    // Use AI-provided problematic code if available, otherwise get from context
    def codeContext = issue.problematicCode ?: (diffText && issue.path && issue.line ? getCodeContext(issue.path, issue.line, diffText) : null)
    
    String explanationPrompt
    
    if (detailLevel == "full") {
      explanationPrompt = """You are a senior code reviewer explaining a ${issue.severity?.toUpperCase()} severity issue.

Issue: ${issue.summary}
File: ${issue.path ?: 'N/A'}${issue.line ? ", Line: ${issue.line}" : ''}

${codeContext ? "ACTUAL PROBLEMATIC CODE:\n```\n${codeContext}\n```\n\n" : ""}
Provide comprehensive explanation with:
1. Root cause analysis (explain what's wrong with the ACTUAL code above)
2. Security/business impact
3. Best practices
4. Detailed solution steps
5. Code examples (show BEFORE/AFTER using the ACTUAL code above)
6. Testing recommendations

${codeContext ? "IMPORTANT: Base your code examples on the ACTUAL code shown above. Show the exact fix for that specific code." : ""}
Be thorough and actionable."""
    } else if (detailLevel == "quick") {
      explanationPrompt = """You are a code reviewer for a MEDIUM severity issue.

Issue: ${issue.summary}
File: ${issue.path ?: 'N/A'}

${codeContext ? "ACTUAL PROBLEMATIC CODE:\n```\n${codeContext}\n```\n\n" : ""}
Provide concise explanation with:
1. Quick summary (1 paragraph)
2. Why it matters (2-3 sentences)
3. Quick fix steps (3-5 steps)
4. Simple code example (show how to fix the ACTUAL code above)

${codeContext ? "IMPORTANT: Base your code example on the ACTUAL code shown above." : ""}
Keep it brief."""
    } else {
      return null
    }

    def requestBody = [
      model: OLLAMA_MODEL,
      stream: false,
      messages: [
        [role: "system", content: "You are a senior software engineer providing code review feedback."],
        [role: "user", content: explanationPrompt]
      ]
    ]

    def response = httpRequest(
      "${OLLAMA_URL}/api/chat",
      'POST',
      null,
      JsonOutput.toJson(requestBody),
      OLLAMA_TIMEOUT,
      2
    )
    
    def resp = jsonParser.parseText(response.body)
    def explanation = (resp?.message?.content ?: "").trim()
    
    return explanation ?: "⚠️ Could not generate AI explanation."
  } catch (Exception e) {
    log.warn("AI Review: Failed to get explanation: ${e.message}")
    return "⚠️ Could not generate AI explanation (error: ${e.message})."
  }
}

def getCodeContext(String filePath, Integer lineNumber, String diffText) {
  if (!filePath || !diffText) {
    log.warn("AI Review: [CODE CONTEXT] Missing parameters: filePath=${filePath}, diffText=${diffText ? 'present' : 'null'}")
    return null
  }
  
  if (!lineNumber) {
    log.warn("AI Review: [CODE CONTEXT] No line number provided, showing all changes for ${filePath}")
    return getFileChanges(filePath, diffText)
  }
  
  log.warn("AI Review: [CODE CONTEXT] Extracting context for ${filePath}:${lineNumber}")
  
  try {
    def diffData = jsonParser.parseText(diffText)
    def contextLines = []
    def problemLine = null
    
    diffData.diffs?.each { diff ->
      if (diff.source?.toString == filePath || diff.destination?.toString == filePath) {
        diff.hunks?.each { hunk ->
          hunk.segments?.each { segment ->
            segment.lines?.each { line ->
              def lineNum = line.destination ?: line.source
              
              // Collect context around the problem line (±3 lines)
              if (Math.abs(lineNum - lineNumber) <= 3) {
                def type = segment.type == 'ADDED' ? 'added' : 
                          segment.type == 'REMOVED' ? 'removed' : 'context'
                
                def isProblematicLine = (lineNum == lineNumber)
                contextLines << [
                  lineNum: lineNum, 
                  content: line.line, 
                  type: type,
                  isProblem: isProblematicLine
                ]
                
                if (isProblematicLine) {
                  problemLine = line.line
                }
              }
            }
          }
        }
      }
    }
    
    if (contextLines.isEmpty()) {
      log.warn("AI Review: [CODE CONTEXT] No context lines found for ${filePath}:${lineNumber}")
      return null
    }
    
    log.warn("AI Review: [CODE CONTEXT] Found ${contextLines.size()} context lines for ${filePath}:${lineNumber}")
    
    def result = new StringBuilder()
    
    // Add header showing the problematic line
    if (problemLine) {
      result << "❌ Issue at line ${lineNumber}:\n"
    }
    
    contextLines.sort { it.lineNum }.each { ctx ->
      def prefix = ctx.type == 'added' ? '+' : (ctx.type == 'removed' ? '-' : ' ')
      def marker = ctx.isProblem ? ' ← ❌ ISSUE HERE' : ''
      def linePrefix = ctx.isProblem ? '>>> ' : '    '
      
      result << "${linePrefix}${ctx.lineNum}: ${prefix}${ctx.content}${marker}\n"
    }
    
    def finalResult = result.toString().trim()
    log.warn("AI Review: [CODE CONTEXT] Generated context (${finalResult.length()} chars) for ${filePath}:${lineNumber}")
    return finalResult
  } catch (Exception e) {
    log.warn("AI Review: [CODE CONTEXT] Failed to extract code context for ${filePath}:${lineNumber ?: 'no-line'}: ${e.message}")
    return null
  }
}

def getFileChanges(String filePath, String diffText) {
  try {
    def diffData = jsonParser.parseText(diffText)
    def changes = []
    
    diffData.diffs?.each { diff ->
      if (diff.source?.toString == filePath || diff.destination?.toString == filePath) {
        diff.hunks?.each { hunk ->
          hunk.segments?.each { segment ->
            if (segment.type == 'ADDED' || segment.type == 'REMOVED') {
              segment.lines?.each { line ->
                def lineNum = line.destination ?: line.source
                def type = segment.type == 'ADDED' ? 'added' : 'removed'
                def prefix = type == 'added' ? '+' : '-'
                changes << "${lineNum}: ${prefix}${line.line}"
              }
            }
          }
        }
      }
    }
    
    if (changes.isEmpty()) {
      return "No changes found in ${filePath}"
    }
    
    def result = new StringBuilder()
    result << "📝 All changes in ${filePath}:\n"
    changes.take(10).each { change ->
      result << "${change}\n"
    }
    
    if (changes.size() > 10) {
      result << "... and ${changes.size() - 10} more changes\n"
    }
    
    return result.toString().trim()
  } catch (Exception e) {
    log.warn("AI Review: Failed to get file changes for ${filePath}: ${e.message}")
    return null
  }
}

def addIssueCommentAsReply(Map issue, int issueNumber, int totalIssues, Long parentCommentId, String diffText = null) {
  try {
    log.warn("AI Review: 🔹 Creating issue ${issueNumber} (parent: ${parentCommentId})")
    
    def severity = issue.severity?.toLowerCase() ?: "medium"
    def severityIcon = ["critical": "🔴", "high": "🟠", "medium": "🟡", "low": "🔵"][severity] ?: "⚪"
    def severityUpper = severity.toUpperCase()
    
    String detailLevel = null
    if (severity == "critical" || severity == "high") {
      detailLevel = "full"
    } else if (severity == "medium") {
      detailLevel = "quick"
    }
    
    StringBuilder issueComment = new StringBuilder()
    issueComment << "${severityIcon} **Issue #${issueNumber}/${totalIssues}: ${severityUpper}**\n\n"
    
    if (issue.path) {
      issueComment << "**📁 File:** `${issue.path}`"
      if (issue.line) {
        issueComment << " **(Line ${issue.line})**"
      }
      issueComment << "\n\n"
    }
    
    if (issue.type) {
      issueComment << "**🏷️ Category:** ${issue.type}\n\n"
    }
    
    issueComment << "**📋 Summary:** ${issue.summary}\n\n"
    
    // Use AI-provided problematic code if available, otherwise fallback to getCodeContext
    def codeToShow = issue.problematicCode ?: getCodeContext(issue.path, issue.line, diffText)
    if (codeToShow) {
      def codeType = issue.problematicCode ? "java" : "diff"
      issueComment << "**📝 Problematic Code:**\n```${codeType}\n${codeToShow}\n```\n\n"
    }
    
    if (detailLevel) {
      def detailedExplanation = getDetailedExplanation(issue, detailLevel, diffText)
      
      if (detailedExplanation) {
        issueComment << "---\n\n"
        if (detailLevel == "full") {
          issueComment << "### 🔍 Detailed Analysis\n\n"
        } else {
          issueComment << "### ⚡ Quick Solution\n\n"
        }
        issueComment << "${detailedExplanation}\n\n"
      } else if (issue.details && issue.details.trim()) {
        issueComment << "**Details:** ${issue.details}\n\n"
      }
    } else {
      if (issue.details && issue.details.trim()) {
        issueComment << "**Details:** ${issue.details}\n\n"
      }
    }
    
    if (issue.fix && issue.fix.trim()) {
      issueComment << "---\n\n"
      issueComment << "### 💡 Suggested Fix\n\n"
      issueComment << "```diff\n${issue.fix.trim()}\n```\n\n"
    }
    
    issueComment << "---\n"
    if (severity == "low") {
      issueComment << "_🔵 Low Priority Issue_"
    } else {
      issueComment << "_🤖 AI Code Review powered by ${OLLAMA_MODEL}_"
    }
    
    if (parentCommentId != null && parentCommentId > 0) {
      replyToComment(parentCommentId, issueComment.toString())
      log.warn("AI Review: ✅ Issue ${issueNumber} posted as REPLY to ${parentCommentId}")
    } else {
      log.error("AI Review: ❌ No parent ID for issue ${issueNumber}! Posting separately")
      addPRComment(issueComment.toString())
    }
    
    return [success: true, message: "Created"]
  } catch (Exception e) {
    log.error("AI Review: ❌ Failed issue ${issueNumber}: ${e.message}", e)
    return [success: false, message: e.message]
  }
}

def getPreviousIssues() {
  try {
    def commentsUrl = "${baseUrl}/rest/api/1.0/projects/${project}/repos/${slug}/pull-requests/${prId}/activities?limit=1000"
    def response = httpRequest(commentsUrl, 'GET', BB_BASIC_AUTH, null, READ_TIMEOUT, 2)
    def activities = jsonParser.parseText(response.body)
    
    def previousIssues = []
    def allComments = []
    
    activities.values?.each { activity ->
      if (activity.action == 'COMMENTED' && activity.comment?.text) {
        allComments << activity
        def commentText = activity.comment.text
        
        // Look for AI review metadata
        def metadataMatch = commentText =~ /<!-- AI_REVIEW_METADATA:(.+?)-->/
        if (metadataMatch) {
          try {
            def metadata = jsonParser.parseText(metadataMatch[0][1])
            if (metadata.issues) {
              previousIssues.addAll(metadata.issues)
            }
          } catch (Exception e) {
            log.warn("AI Review: Failed to parse metadata: ${e.message}")
          }
        }
      }
    }
    
    log.warn("AI Review: Found ${previousIssues.size()} previous issues from ${allComments.size()} comments")
    return [issues: previousIssues, allComments: allComments]
  } catch (Exception e) {
    log.warn("AI Review: Failed to get previous issues: ${e.message}")
    return [issues: [], allComments: []]
  }
}

def fetchDiff(Map ctx) {
  final long currentPrId = ctx.prId as long
  final String url = "${ctx.baseUrl}/rest/api/1.0/projects/${ctx.project}/repos/${ctx.slug}" +
                     "/pull-requests/${currentPrId}/diff?withComments=false&whitespace=ignore-all"

  try {
    def resp = httpRequest(url, 'GET', BB_BASIC_AUTH, null, READ_TIMEOUT, 2)
    return resp.body
  } catch(RuntimeException e) {
    // Optional: spot obvious PR-id mismatch in server message for easier diagnosis
    def m = (e.message ?: '') =~ /Pull Request (\d+) doest not exist/
    if (m.find() && (m.group(1) as long) != currentPrId) {
      log.warn("AI Review: PR-id mismatch detected. Event=${currentPrId}, API complained about ${m.group(1)}.")
    }
    throw e
  }
}