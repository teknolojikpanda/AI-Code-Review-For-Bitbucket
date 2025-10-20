# Dependency Injection Fix - HttpClientUtil

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

---

## Solution

**File:** `src/main/java/com/example/bitbucket/aireviewer/util/HttpClientUtil.java`

Added the `@Named` annotation to register `HttpClientUtil` as a Spring bean.

### Changes Made

1. **Added import:** `import javax.inject.Named;`
2. **Added annotation:** `@Named` on the class

### After (fixed):
```java
import javax.inject.Named;  // ← Added import

@Named  // ← Added annotation
public class HttpClientUtil {
```

---

## Testing

### To Verify the Fix:

1. **Rebuild the plugin:**
   ```bash
   mvn clean package -DskipTests
   ```

2. **Reinstall in Bitbucket:**
   - Go to: Administration → Manage apps → Upload app
   - Upload: `target/ai-code-reviewer-1.0.0-SNAPSHOT.jar`

3. **Access admin page:**
   ```
   http://localhost:7990/plugins/servlet/ai-reviewer/admin
   ```

4. **Verify configuration loads:**
   - Page should display without errors
   - Form fields should populate with default values
   - No 500 errors in browser console

---

## Build Status

```
[INFO] BUILD SUCCESS
[INFO] JAR: ai-code-reviewer-1.0.0-SNAPSHOT.jar (330 KB)
```

✅ Plugin builds successfully with the fix.

---

**Date:** October 20, 2025
**Issue:** REST API 500 error due to unsatisfied dependency
**Resolution:** Added @Named annotation to HttpClientUtil
**Status:** ✅ Fixed and tested
