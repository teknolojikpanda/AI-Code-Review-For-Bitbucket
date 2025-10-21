# DEPLOY THIS JAR NOW - Fix #4 Applied

## Critical Fix Applied

**Fix #4: @ComponentImport for JAX-RS Dependency Injection**

This fix resolves the `UnsatisfiedDependencyException` error you've been seeing.

## Root Cause (What Was Wrong)

The `ConfigResource` JAX-RS endpoint was missing `@ComponentImport` annotation on the `AIReviewerConfigService` parameter. This caused Jersey/HK2 (the JAX-RS container) to look for the service in the wrong dependency injection container.

**Before:**
```java
@Inject
public ConfigResource(
        @ComponentImport UserManager userManager,
        AIReviewerConfigService configService) {  // ❌ Missing @ComponentImport
```

**After:**
```java
@Inject
public ConfigResource(
        @ComponentImport UserManager userManager,
        @ComponentImport AIReviewerConfigService configService) {  // ✅ Fixed!
```

## File to Deploy

**JAR File:** `ai-code-reviewer-COMPONENT-IMPORT-FIX.jar`
- **Location:** `/home/cducak/Downloads/ai_code_review/ai-code-reviewer-COMPONENT-IMPORT-FIX.jar`
- **Size:** 331 KB
- **Build Date:** Oct 21 08:29

## Deployment Steps (Docker)

### 1. Stop Bitbucket
```bash
docker exec bitbucket /opt/atlassian/bitbucket/8.9.0/bin/stop-bitbucket.sh
sleep 10
```

### 2. Start Bitbucket
```bash
docker exec bitbucket /opt/atlassian/bitbucket/8.9.0/bin/start-bitbucket.sh
# Wait for Bitbucket to fully start (check logs)
```

### 3. Uninstall Old Plugin
- Open browser: http://0.0.0.0:7990
- Go to: **Settings → Manage apps**
- Find: **AI Code Reviewer for Bitbucket**
- Click: **Uninstall** (NOT Disable)
- Wait for confirmation

### 4. Upload New Plugin
- Go to: **Settings → Manage apps → Upload app**
- Upload: **`ai-code-reviewer-COMPONENT-IMPORT-FIX.jar`**
- Verify file size: **331 KB**
- Wait for: **"Installed and ready to go"**

### 5. Test the Fix
- Open: http://0.0.0.0:7990/plugins/servlet/ai-reviewer/admin
- **Expected:** Configuration form loads WITHOUT errors
- **Expected:** You can see Ollama URL field and Save button
- **Expected:** Browser console shows: `GET /rest/ai-reviewer/1.0/config 200 OK`

## What This Fix Includes

This JAR includes ALL FOUR fixes:
1. ✅ Fix #1: Admin UI Base URL Detection
2. ✅ Fix #2: HttpClientUtil @Named Annotation
3. ✅ Fix #3: HttpClientUtil No-arg Constructor
4. ✅ Fix #4: ConfigResource @ComponentImport (**NEW - Critical**)

## Verification After Deployment

### Success Indicators
✅ Admin page loads without "Failed to load configuration" error
✅ Browser console: `GET http://0.0.0.0:7990/rest/ai-reviewer/1.0/config 200 (OK)`
✅ Can see configuration form with Ollama URL field
✅ Can save configuration
✅ Can test Ollama connection

### If Still Failing
Check Bitbucket logs:
```bash
docker exec bitbucket tail -100 /var/atlassian/application-data/bitbucket/log/atlassian-bitbucket.log
```

If you see the SAME `UnsatisfiedDependencyException` error:
1. Verify the uploaded JAR file size is **331 KB** (Oct 21 08:29)
2. Verify only ONE "AI Code Reviewer" plugin is installed
3. Check you uploaded the **correct JAR** (ai-code-reviewer-COMPONENT-IMPORT-FIX.jar)

## Why Previous JARs Didn't Work

| JAR Date | Size | Issue |
|----------|------|-------|
| Oct 20 | 338 KB | Missing Fix #3 (no-arg constructor) |
| Oct 21 05:19 | 338 KB | Missing Fix #4 (@ComponentImport) ← You deployed this |
| Oct 21 08:29 | 331 KB | **All fixes included** ← Deploy this one! |

## Technical Details

See [CRITICAL_COMPONENT_IMPORT_FIX.md](CRITICAL_COMPONENT_IMPORT_FIX.md) for detailed technical explanation of the framework mismatch between Jersey/HK2 and Atlassian Spring containers.
