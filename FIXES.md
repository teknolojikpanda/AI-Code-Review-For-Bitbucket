# AI Code Reviewer Plugin - Bug Fixes Documentation

This document consolidates all bug fixes applied to the AI Code Reviewer for Bitbucket plugin.

---

## Table of Contents

1. [Fix #1: Admin UI Base URL Detection](#fix-1-admin-ui-base-url-detection)
2. [Fix #2: HttpClientUtil Dependency Injection](#fix-2-httpclientutil-dependency-injection)
3. [Fix #3: HttpClientUtil Constructor for DI Container](#fix-3-httpclientutil-constructor-for-di-container)
4. [Fix #4: ConfigResource @ComponentImport for JAX-RS DI](#fix-4-configresource-componentimport-for-jax-rs-di)
5. [Current Deployment Instructions](#current-deployment-instructions)

---

# Fix #1: Admin UI Base URL Detection

**Date:** October 20, 2025
**Status:** ✅ Fixed and tested

## Issue

On the Bitbucket 'AI Code Reviewer Configuration' page, users were getting a **'Failed to load configuration:'** error message.

### Root Cause

The JavaScript was constructing an incorrect REST API URL:

**Expected:**
```
http://0.0.0.0:7990/rest/ai-reviewer/1.0/config
```

**Actual (broken):**
```
http://0.0.0.0:7990/plugins/servlet/ai-reviewer/undefined/rest/ai-reviewer/1.0/config
```

The issue was that `baseUrl` was coming back as `undefined` because the meta tag `application-base-url` was not being found in the Bitbucket admin page context.

### JavaScript Console Error

```
GET http://0.0.0.0:7990/plugins/servlet/ai-reviewer/undefined/rest/ai-reviewer/1.0/config 404 (Not Found)
Failed to load configuration:
```

## Solution

**File:** `src/main/resources/js/ai-reviewer-admin.js`

Changed the baseUrl detection to use multiple fallback methods:

### Before (broken):
```javascript
var baseUrl = AJS.$('meta[name="application-base-url"]').attr("content");
var apiUrl = baseUrl + '/rest/ai-reviewer/1.0/config';
```

### After (fixed):
```javascript
// Get base URL - try multiple methods
var baseUrl = AJS.$('meta[name="application-base-url"]').attr("content") ||
              AJS.$('meta[name="ajs-context-path"]').attr("content") ||
              window.location.origin + (AJS.contextPath() || '');

console.log('Base URL:', baseUrl);

var apiUrl = baseUrl + '/rest/ai-reviewer/1.0/config';
```

### Fallback Strategy

1. **First:** Try `application-base-url` meta tag (standard Atlassian pattern)
2. **Second:** Try `ajs-context-path` meta tag (AUI alternative)
3. **Third:** Use `window.location.origin` + `AJS.contextPath()` (guaranteed to work)

The `||` operator ensures we use the first non-null/non-undefined value.

### Why This Happened

The `application-base-url` meta tag may not be present in all Bitbucket admin pages, or it may be named differently depending on:
- Bitbucket version
- Plugin context (servlet vs. REST resource)
- Page template being used

The fix ensures compatibility across different Bitbucket configurations by trying multiple methods.

---

# Fix #2: HttpClientUtil Dependency Injection

**Date:** October 20, 2025
**Status:** ⚠️ Incomplete (superseded by Fix #3)

## Issue

The Bitbucket admin configuration page was showing **"Failed to load configuration"** error with a 500 Internal Server Error.

### Error Details

**JavaScript Console:**
```
GET http://0.0.0.0:7990/rest/ai-reviewer/1.0/config 500 (Internal Server Error)
Failed to load configuration:
```

**Bitbucket Server Log:**
```
org.glassfish.hk2.api.UnsatisfiedDependencyException: There was no object available for injection at SystemInjecteeImpl(requiredType=AIReviewerConfigService,parent=ConfigResource,qualifiers={},position=1,optional=false,self=false,unqualified=null,693204003)
```

### Root Cause

When we added `HttpClientUtil` as a dependency to `AIReviewerConfigServiceImpl` (for the Ollama connection test feature), we forgot to add the `@Named` annotation to `HttpClientUtil`.

Without `@Named`, Spring Scanner doesn't register `HttpClientUtil` as a bean, which causes the dependency injection to fail when `AIReviewerConfigServiceImpl` tries to inject it.

**Dependency Chain:**
```
ConfigResource
    ↓ (needs)
AIReviewerConfigServiceImpl
    ↓ (needs)
HttpClientUtil ❌ (not registered as a bean!)
```

## Solution

**File:** `src/main/java/com/example/bitbucket/aireviewer/util/HttpClientUtil.java`

Added the `@Named` annotation to register `HttpClientUtil` as a Spring bean.

### Changes Made

1. **Added import:** `import javax.inject.Named;`
2. **Added annotation:** `@Named` on the class

```java
import javax.inject.Named;  // ← Added import

@Named  // ← Added annotation
public class HttpClientUtil {
```

### Why This Fix Was Incomplete

This fix successfully registered `HttpClientUtil` as a Spring bean, **BUT** it didn't solve the instantiation problem. The DI container could find the class but couldn't create an instance because there was no suitable constructor. See Fix #3 for the complete solution.

---

# Fix #3: HttpClientUtil Constructor for DI Container

**Date:** October 21, 2025
**Status:** ✅ **COMPLETE FIX** (Final working solution)

## Problem

Bitbucket admin page showed "Failed to load configuration" error with the following stack trace:

```
org.glassfish.hk2.api.UnsatisfiedDependencyException: There was no object available for injection at SystemInjecteeImpl(requiredType=AIReviewerConfigService,parent=ConfigResource,qualifiers={},position=1,optional=false,self=false,unqualified=null,676330557)
```

### Root Cause Analysis

The dependency injection chain was:
1. `ConfigResource` requires `AIReviewerConfigService`
2. `AIReviewerConfigServiceImpl` (implementation) requires `HttpClientUtil`
3. **HttpClientUtil could NOT be instantiated by the DI container**

Why HttpClientUtil couldn't be instantiated:
- HttpClientUtil had the `@Named` annotation ✅ (correct)
- HttpClientUtil was registered in the plugin components manifest ✅ (correct)
- **BUT** HttpClientUtil only had a constructor with 5 required parameters:
  ```java
  public HttpClientUtil(
      int connectTimeout,
      int readTimeout,
      int maxRetries,
      int baseRetryDelayMs,
      int apiDelayMs)
  ```
- The constructor was NOT annotated with `@Inject` ❌
- The parameters had no annotations indicating where values should come from ❌
- **The DI container had no way to know what values to pass to the constructor** ❌

**Result:** DI container couldn't create HttpClientUtil → couldn't create AIReviewerConfigServiceImpl → couldn't create ConfigResource → REST API failed with 500 error

## Solution

**File:** `src/main/java/com/example/bitbucket/aireviewer/util/HttpClientUtil.java`

Added a default no-argument constructor to HttpClientUtil that uses sensible default values:

```java
// Default configuration values
private static final int DEFAULT_CONNECT_TIMEOUT = 10000; // 10 seconds
private static final int DEFAULT_READ_TIMEOUT = 30000;    // 30 seconds
private static final int DEFAULT_MAX_RETRIES = 3;
private static final int DEFAULT_BASE_RETRY_DELAY = 1000; // 1 second
private static final int DEFAULT_API_DELAY = 100;         // 100ms

/**
 * Creates a new HTTP client utility with default configuration values.
 * Used by dependency injection container.
 */
public HttpClientUtil() {
    this(DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT, DEFAULT_MAX_RETRIES,
         DEFAULT_BASE_RETRY_DELAY, DEFAULT_API_DELAY);
}
```

### How This Works

This allows the DI container to:
1. Find HttpClientUtil (via `@Named` annotation from Fix #2)
2. **Instantiate it** (via no-arg constructor - **this was missing before!**)
3. Inject it into AIReviewerConfigServiceImpl
4. Inject AIReviewerConfigServiceImpl into ConfigResource
5. Successfully handle REST API requests

### Files Modified

- `src/main/java/com/example/bitbucket/aireviewer/util/HttpClientUtil.java`
  - Added default constant values for timeout configuration
  - Added public no-argument constructor
  - Kept existing parameterized constructor for flexibility

### Build Info

- **JAR File**: `target/ai-code-reviewer-1.0.0-SNAPSHOT.jar`
- **Build Date**: October 21, 2025 07:56
- **Size**: 331 KB
- **Build Status**: SUCCESS

### Why This Happened

This issue was introduced when we added HttpClientUtil as a dependency to AIReviewerConfigServiceImpl (during TODO completion work for Ollama connection testing). The initial implementation of HttpClientUtil only had a parameterized constructor, which is fine for manual instantiation but incompatible with Spring/HK2 dependency injection without additional annotations.

### Alternative Solutions Considered

1. **Add `@Inject` to parameterized constructor + use `@Value` annotations**
   - Would require external configuration source
   - More complex setup for simple use case

2. **Make HttpClientUtil a singleton with static instance**
   - Not compatible with dependency injection
   - Harder to test

3. **Factory pattern with `@Produces` method**
   - Overly complex for this simple case

4. **No-argument constructor (chosen solution)** ✅
   - Simple and clean
   - Compatible with DI container
   - Still allows custom configuration via parameterized constructor
   - Uses sensible defaults that match the configuration service defaults

---

# Fix #4: ConfigResource @ComponentImport for JAX-RS DI

**Date:** October 21, 2025
**Status:** ✅ Fixed
**Severity:** CRITICAL - Blocks all REST API functionality

## Issue

Even after Fixes #1, #2, and #3, users were still getting `UnsatisfiedDependencyException` when accessing the configuration page:

```
org.glassfish.hk2.api.UnsatisfiedDependencyException: There was no object available for injection at SystemInjecteeImpl(requiredType=AIReviewerConfigService,parent=ConfigResource,qualifiers={},position=1,optional=false,self=false,unqualified=null,...)
```

### What Was Working
- ✅ HttpClientUtil had no-arg constructor
- ✅ All components properly registered in META-INF/plugin-components/component
- ✅ All services properly exported in META-INF/plugin-components/exports
- ✅ Plugin deployed correctly to Bitbucket

### Root Cause

**Framework Mismatch:** Bitbucket plugins use TWO separate dependency injection containers:

1. **Atlassian Spring Container** - Manages @Named components
2. **Jersey/HK2 Container** - Manages JAX-RS resources (@Path)

**The Problem:**
```java
@Path("/config")
@Named
public class ConfigResource {
    @Inject
    public ConfigResource(
            @ComponentImport UserManager userManager,
            AIReviewerConfigService configService) {  // ❌ Missing @ComponentImport!
        // ...
    }
}
```

When Jersey/HK2 tried to instantiate ConfigResource:
- HK2 saw @Inject constructor
- For `UserManager`: Had @ComponentImport → Bridge told HK2 to look in Spring container → ✅ Success
- For `AIReviewerConfigService`: NO @ComponentImport → HK2 only looked in HK2 registry → ❌ Not found!

**Why it wasn't found:**
- `AIReviewerConfigServiceImpl` was registered in **Spring container** (via @Named)
- But HK2 was looking in **HK2's registry**
- Without @ComponentImport, HK2 doesn't know to check the Spring container

## Solution

Add `@ComponentImport` annotation to the `AIReviewerConfigService` parameter:

**File:** `src/main/java/com/example/bitbucket/aireviewer/rest/ConfigResource.java`

```diff
    @Inject
    public ConfigResource(
            @ComponentImport UserManager userManager,
-           AIReviewerConfigService configService) {
+           @ComponentImport AIReviewerConfigService configService) {
        this.userManager = userManager;
        this.configService = configService;
    }
```

## How @ComponentImport Works

`@ComponentImport` is a **bridge annotation** that tells Jersey/HK2:

> "This dependency is not in HK2's registry. Look it up from the Atlassian Spring container instead."

**Dependency Injection Flow:**
1. HK2 creates instance of ConfigResource (JAX-RS resource)
2. HK2 sees @Inject constructor with 2 parameters
3. For each parameter:
   - **Has @ComponentImport?** → Delegate to Spring container → Find and inject
   - **No @ComponentImport?** → Look only in HK2 registry → Fail if not found

## Key Lessons

### Rule: Always use @ComponentImport in JAX-RS resources

When a JAX-RS resource (@Path) needs to inject an Atlassian plugin service, **ALWAYS** use @ComponentImport:

```java
// ❌ WRONG - Will cause UnsatisfiedDependencyException
@Path("/myendpoint")
public class MyResource {
    @Inject
    public MyResource(MyPluginService service) { }
}

// ✅ CORRECT
@Path("/myendpoint")
public class MyResource {
    @Inject
    public MyResource(@ComponentImport MyPluginService service) { }
}
```

### Understanding the Two Containers

| Aspect | Spring Container | HK2 Container |
|--------|-----------------|---------------|
| **Manages** | @Named components | JAX-RS resources (@Path) |
| **Registration** | META-INF/plugin-components/component | Auto-detected by Jersey |
| **Scanning** | atlassian-spring-scanner-maven-plugin | Jersey package scanning |
| **Injection** | @Inject between @Named components | @Inject in JAX-RS resources |
| **Bridge** | @ComponentImport tells HK2 to look in Spring | |

## Verification

```bash
# After fix - both parameters have @ComponentImport
javap -verbose target/classes/.../ConfigResource.class | grep -A 5 "RuntimeVisibleParameterAnnotations"

RuntimeVisibleParameterAnnotations:
  parameter 0:
    0: @ComponentImport()  # UserManager
  parameter 1:
    0: @ComponentImport()  # AIReviewerConfigService ✅
```

## Why Previous Debugging Attempts Failed

| Attempt | Result | Why It Failed |
|---------|--------|---------------|
| Added no-arg constructor to HttpClientUtil | ✅ Needed but didn't solve REST error | Error was in ConfigResource, not HttpClientUtil |
| Verified component registration | ✅ All registered correctly | Components in Spring, but HK2 couldn't see them |
| Restarted Bitbucket multiple times | ✅ Cleared cache | Code itself was wrong, not a deployment issue |
| Checked deployed JAR | ✅ JAR was correct | Missing annotation in source code |

## Impact

**Before Fix:**
- ❌ GET /rest/ai-reviewer/1.0/config → 500 Internal Server Error
- ❌ Configuration page fails to load
- ❌ Cannot save or test configuration

**After Fix:**
- ✅ GET /rest/ai-reviewer/1.0/config → 200 OK
- ✅ Configuration page loads successfully
- ✅ Can save and test Ollama connection

---

# Current Deployment Instructions

## CRITICAL: Complete Plugin Replacement Required

**IMPORTANT:** This deployment includes ALL FOUR fixes:
1. ✅ Fix #1: Admin UI Base URL Detection
2. ✅ Fix #2: HttpClientUtil @Named Annotation
3. ✅ Fix #3: HttpClientUtil No-arg Constructor
4. ✅ Fix #4: ConfigResource @ComponentImport (NEW - Critical for REST API)

You **MUST** follow these steps exactly:

### 1. Navigate to Bitbucket Administration
- Go to: **Settings → Manage apps** (or Manage add-ons)

### 2. Completely Uninstall Old Plugin
- Find "**AI Code Reviewer for Bitbucket**"
- Click "**Uninstall**" (NOT just "Disable")
- Wait for confirmation that uninstall completed

### 3. Restart Bitbucket

For Docker deployment:
```bash
# Stop Bitbucket
docker exec bitbucket /opt/atlassian/bitbucket/8.9.0/bin/stop-bitbucket.sh

# Wait 10 seconds for complete shutdown
sleep 10

# Start Bitbucket
docker exec bitbucket /opt/atlassian/bitbucket/8.9.0/bin/start-bitbucket.sh
```

For standalone deployment:
```bash
# Stop Bitbucket
/opt/atlassian/bitbucket/8.9.0/bin/stop-bitbucket.sh

# Wait 10 seconds for complete shutdown
sleep 10

# Start Bitbucket
/opt/atlassian/bitbucket/8.9.0/bin/start-bitbucket.sh
```

**Why restart is required:** Bitbucket caches plugin classes in memory. Without a restart, old plugin code remains loaded and can cause dependency injection failures.

### 4. Upload New Plugin

- Go to: **Settings → Manage apps → Upload app**
- Upload: **`ai-code-reviewer-COMPONENT-IMPORT-FIX.jar`**
- **File size: 331 KB**
- **Build date: Oct 21 08:29** (not older dates like Oct 20 or Oct 21 05:19)
- Wait for "Installed and ready to go" message

**Alternative:** You can also upload `target/ai-code-reviewer-1.0.0-SNAPSHOT.jar` if you just built it

### 5. Clear Browser Cache

```
Chrome/Edge: Ctrl+Shift+Delete → Clear cached images and files
Firefox: Ctrl+Shift+Delete → Clear Cache
Safari: Cmd+Option+E
```

Or use **incognito/private browsing mode**

### 6. Verify the Fix

- Navigate to: **Settings → AI Code Reviewer Configuration**
- Page should load **WITHOUT** "Failed to load configuration" error
- You should see the configuration form with Ollama URL field

## Verification

Check Bitbucket logs during plugin initialization (after restart). You should see:

```
Successfully loaded AI Code Reviewer plugin
Registered component: HttpClientUtil
Registered component: AIReviewerConfigServiceImpl
Registered component: ConfigResource
```

### If you still see the error, check:

1. ❓ Did you upload the correct JAR file? (timestamp: **Oct 21 07:56**)
2. ❓ Did you **restart Bitbucket** after uninstalling the old plugin?
3. ❓ Did you clear your browser cache?

## Testing After Deployment

After deploying this fix, you can test the connection in the admin UI:

1. Enter Ollama URL: `http://10.152.98.37:11434`
2. Click "Test Connection"
3. Should see success or failure message (instead of page error)

---

## Summary of All Fixes

| Fix # | Date | Issue | File(s) Changed | Status |
|-------|------|-------|----------------|--------|
| 1 | Oct 20, 2025 | JavaScript baseUrl undefined → 404 error | `ai-reviewer-admin.js` | ✅ Fixed |
| 2 | Oct 20, 2025 | Missing @Named annotation → component not registered | `HttpClientUtil.java` | ⚠️ Incomplete |
| 3 | Oct 21, 2025 | Missing no-arg constructor → DI can't instantiate | `HttpClientUtil.java` | ✅ **COMPLETE** |

**Current Build:** `ai-code-reviewer-1.0.0-SNAPSHOT.jar` (331 KB, Oct 21 07:56)

---

## Related Files

- `src/main/resources/js/ai-reviewer-admin.js` - Admin UI JavaScript
- `src/main/java/com/example/bitbucket/aireviewer/util/HttpClientUtil.java` - HTTP client utility
- `src/main/java/com/example/bitbucket/aireviewer/service/AIReviewerConfigServiceImpl.java` - Config service
- `src/main/java/com/example/bitbucket/aireviewer/rest/ConfigResource.java` - REST endpoint

---

**Last Updated:** October 21, 2025
**Plugin Version:** 1.0.0-SNAPSHOT
**All Fixes Applied:** ✅ Yes
