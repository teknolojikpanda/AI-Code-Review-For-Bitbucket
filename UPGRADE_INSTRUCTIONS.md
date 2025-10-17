# Upgrade Instructions - Servlet Fix Applied

## What Was Fixed

**Issue:** "Could not find servlet" error when accessing Admin page
**Fix:** Added `@Named` annotation to AdminConfigServlet
**Status:** ✅ Ready to install

## Quick Upgrade Steps

### 1. Uninstall Previous Version (if installed)

1. Go to **Bitbucket Administration** (gear icon)
2. Click **"Manage apps"** in left sidebar
3. Find **"AI Code Reviewer for Bitbucket"**
4. Click **"Uninstall"**
5. Confirm uninstallation
6. Wait for confirmation message

### 2. Install New Version

1. Still in **"Manage apps"** page
2. Click **"Upload app"** button
3. Click **"Choose file"**
4. Navigate to: `/home/cducak/Downloads/ai_code_review/target/`
5. Select: `ai-code-reviewer-1.0.0-SNAPSHOT.jar` (256 KB, built Oct 17 16:12)
6. Click **"Upload"**
7. Wait for installation to complete (30-60 seconds)

### 3. Verify Installation

Check these indicators:

✅ **Plugin Status:**
- Plugin shows as **"Enabled"** in Manage apps
- No error icons or messages

✅ **Database Tables:**
- Active Objects tables should exist:
  - `AO_XXXXXX_AI_REVIEW_CONFIG`
  - `AO_XXXXXX_AI_REVIEW_HISTORY`

✅ **No Errors in Logs:**
```bash
tail -n 100 /path/to/bitbucket/logs/atlassian-bitbucket.log
```

Look for successful installation messages:
```
[INFO] Successfully installed plugin: com.example.bitbucket.ai-code-reviewer
[INFO] Plugin enabled: com.example.bitbucket.ai-code-reviewer
```

### 4. Access Admin Configuration Page

**Method A - Via Menu:**
1. Go to **Administration** (gear icon)
2. Scroll to **"Add-ons"** section in left sidebar
3. Look for **"AI Code Reviewer"** menu item
4. Click it

**Method B - Direct URL:**
```
https://your-bitbucket-url/plugins/servlet/ai-reviewer/admin
```

### 5. Expected Result

✅ **Page loads successfully** (no "Could not find servlet" error)
✅ **Configuration form displays** with all sections visible
✅ **Default values populated** in all fields
✅ **All buttons work** (Test Connection, Save, Reset)

## What You Should See

### Admin Page Sections

The configuration page should display these sections:

1. **Ollama Configuration**
   - Ollama URL
   - Primary AI Model
   - Fallback AI Model
   - Test Connection button

2. **Processing Configuration**
   - Max Characters Per Chunk
   - Max Files Per Chunk
   - Max Total Chunks
   - Parallel Processing Threads

3. **Timeout Configuration**
   - Connection Timeout (ms)
   - Read Timeout (ms)
   - Ollama Analysis Timeout (ms)

4. **Review Configuration**
   - Max Issues Per File
   - Max Issue Comments
   - Max Diff Size (bytes)

5. **Retry Configuration**
   - Max Retries
   - Base Retry Delay (ms)
   - API Delay Between Calls (ms)

6. **Review Profile**
   - Minimum Severity
   - Require Approval For

7. **File Filtering**
   - Review File Extensions
   - Ignore Patterns
   - Ignore Paths

8. **Feature Flags**
   - Enable AI Code Review
   - Review Draft PRs
   - Skip Generated Files
   - Skip Test Files

### Default Configuration

All fields should be pre-populated with these defaults:

```yaml
Ollama:
  URL: http://10.152.98.37:11434
  Model: qwen3-coder:30b
  Fallback: qwen3-coder:7b

Processing:
  Max Chars/Chunk: 60000
  Max Files/Chunk: 3
  Max Chunks: 20
  Threads: 4

Timeouts:
  Connection: 10000ms
  Read: 30000ms
  Analysis: 300000ms

Review:
  Max Issues/File: 50
  Max Comments: 30
  Max Diff Size: 10000000

Retry:
  Max Retries: 3
  Base Delay: 1000ms
  API Delay: 100ms

Profile:
  Min Severity: medium
  Require Approval: critical,high

Filtering:
  Extensions: java,groovy,js,ts,tsx,jsx,py,go,rs,cpp,c,cs,php,rb,kt,swift,scala
  Ignore Patterns: *.min.js,*.generated.*,package-lock.json,yarn.lock,*.map
  Ignore Paths: node_modules/,vendor/,build/,dist/,.git/

Flags:
  Enabled: ✓
  Draft PRs: ☐
  Skip Generated: ✓
  Skip Tests: ☐
```

## Troubleshooting

### If Plugin Won't Install

**Symptom:** Upload fails or shows error

**Solutions:**
1. Check JAR file size is ~256 KB
2. Verify JAR isn't corrupted:
   ```bash
   unzip -t /home/cducak/Downloads/ai_code_review/target/ai-code-reviewer-1.0.0-SNAPSHOT.jar
   ```
3. Check Bitbucket version is 8.9.0 or higher
4. Check logs for specific error:
   ```bash
   tail -f /path/to/bitbucket/logs/atlassian-bitbucket.log
   ```

### If Plugin Won't Enable

**Symptom:** Plugin installs but status shows "Disabled" or error

**Solutions:**
1. Try manually enabling:
   - Go to **Manage apps**
   - Find plugin
   - Click **"Enable"**
2. Check database connectivity (Active Objects needs to create tables)
3. Check for dependency conflicts in logs
4. Restart Bitbucket if needed

### If Menu Doesn't Appear

**Symptom:** No "AI Code Reviewer" in Administration menu

**Solutions:**
1. **Verify you're an admin:**
   - Non-admin users won't see the menu
   - Check your user has System Admin permission

2. **Try direct URL access:**
   ```
   https://your-bitbucket-url/plugins/servlet/ai-reviewer/admin
   ```

3. **Clear browser cache:**
   - Hard refresh: Ctrl+Shift+R (Windows/Linux) or Cmd+Shift+R (Mac)
   - Or clear cache completely

4. **Check web-item is loaded:**
   - Go to **Manage apps**
   - Find plugin
   - Click plugin name to expand details
   - Look for "Web Items" section
   - Should show: `ai-reviewer-admin-link`

### If Page Shows "Could not find servlet" (Still)

This shouldn't happen with the new build, but if it does:

**Verify Spring Scanner processed the servlet:**
1. Check build output showed:
   ```
   [INFO] Processed 2 annotated classes
   ```
   (Not 1, must be 2)

2. Rebuild if needed:
   ```bash
   cd /home/cducak/Downloads/ai_code_review
   mvn clean package -DskipTests
   ```

3. Verify `@Named` annotation in servlet:
   ```bash
   grep -n "@Named" src/main/java/com/example/bitbucket/aireviewer/servlet/AdminConfigServlet.java
   ```
   Should show line 29: `@Named`

**Check servlet is in JAR:**
```bash
unzip -l target/ai-code-reviewer-1.0.0-SNAPSHOT.jar | grep AdminConfigServlet
```

Should show:
```
5728  com/example/bitbucket/aireviewer/servlet/AdminConfigServlet.class
```

### If Configuration Won't Save

**Current Expected Behavior:** Configuration will show success message but won't persist yet

**Reason:** Service layer not implemented yet (see TODO in code)

**Workaround:** This will be fixed when configuration service is implemented

## Verification Checklist

After installation, verify these items:

- [ ] Plugin shows as "Enabled" in Manage apps
- [ ] No errors in Bitbucket logs
- [ ] "AI Code Reviewer" menu appears in Administration
- [ ] Configuration page loads (no servlet error)
- [ ] All form sections are visible
- [ ] All fields have default values
- [ ] "Test Connection" button works (validates URL)
- [ ] "Reset to Defaults" button works
- [ ] "Save Configuration" button shows success message
- [ ] REST API is accessible: `curl -u admin:pass http://bitbucket/rest/ai-reviewer/1.0/config`

## Next Steps After Upgrade

### Current Capabilities (What Works Now)

✅ Plugin installs and enables
✅ Admin UI is accessible
✅ Configuration form displays
✅ Form validation works
✅ REST API endpoints respond
✅ Database tables created

### Pending Implementation (What Doesn't Work Yet)

⏳ **Configuration Persistence**
- Settings don't save to database yet
- REST API returns hardcoded defaults
- Needs: AIReviewerConfigService implementation

⏳ **Ollama Connection Test**
- Only validates URL format
- Doesn't actually connect to Ollama
- Needs: OllamaClient HTTP implementation

⏳ **Automatic PR Reviews**
- No event listener yet
- No automatic code analysis
- Needs: PullRequestAIReviewListener + AIReviewService

### Recommended Next Steps

1. **Test the admin UI** - Verify all sections load correctly
2. **Configure Ollama settings** - Enter your Ollama URL and model
3. **Test REST API** - Verify endpoints are accessible
4. **Wait for service layer** - Core functionality coming in next phase

## Support

If you encounter issues:

1. **Check documentation:**
   - [SERVLET_NOT_FOUND_FIX.md](SERVLET_NOT_FOUND_FIX.md) - Details on the servlet fix
   - [INSTALLATION_GUIDE.md](INSTALLATION_GUIDE.md) - Complete installation guide
   - [BUILD_STATUS.md](BUILD_STATUS.md) - Current project status

2. **Check logs:**
   ```bash
   tail -f /path/to/bitbucket/logs/atlassian-bitbucket.log | grep -i "aireviewer\|servlet"
   ```

3. **Common log patterns to look for:**
   - `Successfully installed plugin: com.example.bitbucket.ai-code-reviewer` ✅
   - `Plugin enabled: com.example.bitbucket.ai-code-reviewer` ✅
   - `ClassNotFoundException` ❌ (report this)
   - `Could not instantiate` ❌ (report this)

## Summary

✅ **Fixed:** Servlet now properly registered with `@Named` annotation
✅ **Built:** New JAR at `target/ai-code-reviewer-1.0.0-SNAPSHOT.jar`
✅ **Verified:** Spring Scanner processes 2 annotated classes (was 1)
✅ **Ready:** Plugin ready to install and test

**Installation time:** ~2 minutes
**Expected result:** Admin page loads successfully without "Could not find servlet" error

---

**JAR Details:**
- Location: `/home/cducak/Downloads/ai_code_review/target/ai-code-reviewer-1.0.0-SNAPSHOT.jar`
- Size: 256 KB
- Build date: Oct 17 16:12
- Plugin key: `com.example.bitbucket.ai-code-reviewer`
- Version: `1.0.0-SNAPSHOT`
