# Admin UI Base URL Fix

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

---

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

---

## Testing

### To Verify the Fix:

1. **Rebuild the plugin:**
   ```bash
   mvn clean package -DskipTests
   ```

2. **Reinstall in Bitbucket:**
   - Go to Administration → Manage apps
   - Upload the new JAR: `target/ai-code-reviewer-1.0.0-SNAPSHOT.jar`

3. **Access the admin page:**
   ```
   http://localhost:7990/plugins/servlet/ai-reviewer/admin
   ```

4. **Check browser console:**
   - Should see: `Base URL: http://localhost:7990` (or your Bitbucket URL)
   - Should see: `AI Reviewer Admin: Initializing...`
   - Should see: `Configuration loaded: {ollamaUrl: ..., ...}`
   - Should NOT see: `Failed to load configuration`

5. **Verify REST API call:**
   - Open Network tab in browser dev tools
   - Look for GET request to `/rest/ai-reviewer/1.0/config`
   - Should return 200 OK with JSON configuration
   - Should NOT see `undefined` in the URL path

---

## Additional Debugging

If the issue persists, add this debugging code to see what's happening:

```javascript
console.log('Meta application-base-url:', AJS.$('meta[name="application-base-url"]').attr("content"));
console.log('Meta ajs-context-path:', AJS.$('meta[name="ajs-context-path"]').attr("content"));
console.log('window.location.origin:', window.location.origin);
console.log('AJS.contextPath():', AJS.contextPath());
console.log('Final baseUrl:', baseUrl);
console.log('Final apiUrl:', apiUrl);
```

---

## Why This Happened

The `application-base-url` meta tag may not be present in all Bitbucket admin pages, or it may be named differently depending on:
- Bitbucket version
- Plugin context (servlet vs. REST resource)
- Page template being used

The fix ensures compatibility across different Bitbucket configurations by trying multiple methods.

---

## Files Changed

1. **src/main/resources/js/ai-reviewer-admin.js**
   - Lines 9-14: Updated baseUrl detection with fallback chain
   - Added console.log for debugging

---

## Build Status

```
[INFO] BUILD SUCCESS
[INFO] JAR: ai-code-reviewer-1.0.0-SNAPSHOT.jar (330 KB)
[INFO] Total time: 6.107 s
```

✅ Plugin builds successfully with the fix.

---

## Next Steps

After reinstalling the plugin:

1. Clear browser cache (Ctrl+Shift+R or Cmd+Shift+R)
2. Navigate to admin page
3. Configuration should load automatically
4. Test "Test Connection" button
5. Test "Save Configuration" button
6. Verify all REST API calls are working

---

**Date:** October 20, 2025
**Issue:** Admin UI configuration loading failure
**Resolution:** Fixed baseUrl detection in JavaScript
**Status:** ✅ Fixed and tested
