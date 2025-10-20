# Installation Instructions for Fixed Plugin

## Current Status

✅ The plugin has been **successfully fixed** and built.
✅ HttpClientUtil now has `@Named` annotation and is registered as a Spring bean.
✅ JAR file is ready: `target/ai-code-reviewer-1.0.0-SNAPSHOT.jar` (330 KB, built Oct 20 14:12:19)

## The Issue

You're still seeing the error because Bitbucket is running the **old version** of the plugin that doesn't have the fix.

## Step-by-Step Fix

### Step 1: Verify You Have the Latest JAR

```bash
ls -lh target/ai-code-reviewer-1.0.0-SNAPSHOT.jar
```

**Expected output:**
```
-rw-r--r-- 1 cducak admin 330K Oct 20 14:12 target/ai-code-reviewer-1.0.0-SNAPSHOT.jar
```

⚠️ **Important:** The timestamp should be **14:12** (today). If it's older, rebuild:
```bash
mvn clean package -DskipTests
```

### Step 2: Completely Remove Old Plugin from Bitbucket

1. **Go to Bitbucket Administration:**
   ```
   http://0.0.0.0:7990/admin/plugins
   ```

2. **Find "AI Code Reviewer for Bitbucket"** in the list

3. **Click on the plugin name** to expand details

4. **Click "Uninstall"** (NOT just "Disable")

5. **Confirm the uninstallation**

6. **Wait for confirmation** that plugin is uninstalled

### Step 3: Restart Bitbucket (IMPORTANT!)

This ensures all old classes are cleared from memory.

**If using atlas-run:**
```bash
# Stop atlas-run (Ctrl+C)
# Then restart:
atlas-run
```

**If using Docker:**
```bash
docker-compose restart bitbucket
```

**If using systemd:**
```bash
sudo systemctl restart bitbucket
```

### Step 4: Install the New Plugin

1. **Wait for Bitbucket to fully start** (check http://0.0.0.0:7990)

2. **Go to plugin management:**
   ```
   http://0.0.0.0:7990/admin/plugins
   ```

3. **Click "Upload app"**

4. **Select file:**
   ```
   /home/cducak/Downloads/ai_code_review/target/ai-code-reviewer-1.0.0-SNAPSHOT.jar
   ```

5. **Click "Upload"**

6. **Wait for "Installed and ready to go" message**

### Step 5: Verify Plugin is Enabled

1. **Check plugin status** in plugin list:
   - Should show as "Enabled"
   - All modules should be enabled

2. **Check Bitbucket logs** for successful registration:
   ```bash
   tail -f <bitbucket-home>/log/atlassian-bitbucket.log | grep -E "HttpClientUtil|AIReviewerConfigService|ConfigResource"
   ```

   **Expected log entries:**
   ```
   INFO  - Registered component: HttpClientUtil
   INFO  - Registered component: AIReviewerConfigServiceImpl
   INFO  - REST resource registered: ConfigResource
   ```

### Step 6: Test the Admin Page

1. **Clear browser cache** (very important!):
   - Chrome/Edge: Ctrl+Shift+Delete → Clear cached images and files
   - Or use Incognito/Private window

2. **Navigate to admin page:**
   ```
   http://0.0.0.0:7990/plugins/servlet/ai-reviewer/admin
   ```

3. **Open browser console** (F12 → Console tab)

4. **Expected console output:**
   ```
   Base URL: http://0.0.0.0:7990
   AI Reviewer Admin: Initializing...
   AI Reviewer Admin: Initialized
   Configuration loaded: {ollamaUrl: "http://10.152.98.37:11434", ...}
   ```

5. **Expected page behavior:**
   - ✅ No error messages
   - ✅ Form loads with default values
   - ✅ All fields are populated

### Step 7: Verify REST API

1. **Test the REST endpoint directly** (in new browser tab):
   ```
   http://0.0.0.0:7990/rest/ai-reviewer/1.0/config
   ```

2. **Expected response** (HTTP 200 OK):
   ```json
   {
     "ollamaUrl": "http://10.152.98.37:11434",
     "ollamaModel": "qwen3-coder:30b",
     "fallbackModel": "qwen3-coder:7b",
     ...
   }
   ```

3. **If you get 500 error:**
   - Check Bitbucket logs for detailed error
   - Verify plugin is fully loaded
   - Try restarting Bitbucket again

---

## Troubleshooting

### Issue: Still getting 500 error

**Possible causes:**

1. **Old JAR uploaded:**
   - Solution: Verify JAR timestamp is Oct 20 14:12
   - Rebuild if needed: `mvn clean package -DskipTests`

2. **Bitbucket cache not cleared:**
   - Solution: Restart Bitbucket completely
   - For atlas-run: Stop and restart
   - Clear Bitbucket's plugin cache: delete `<bitbucket-home>/plugins/.bundled-plugins`

3. **Multiple versions of plugin:**
   - Solution: Uninstall ALL versions, restart, install fresh

4. **Browser cache:**
   - Solution: Hard refresh (Ctrl+Shift+R) or use Incognito

### Issue: Can't see "AI Code Reviewer" in plugin list

**Solution:**
- The plugin key might have changed
- Search for "com.example.bitbucket.ai-code-reviewer"
- Or search for "AI Code Reviewer"

### Issue: Plugin won't uninstall

**Solution:**
```bash
# Stop Bitbucket
# Delete plugin manually:
rm <bitbucket-home>/plugins/installed-plugins/ai-code-reviewer*.jar
# Start Bitbucket
```

---

## Verification Checklist

Before testing, verify:

- [ ] JAR file timestamp is Oct 20 14:12 or later
- [ ] Old plugin completely uninstalled from Bitbucket
- [ ] Bitbucket restarted after uninstall
- [ ] New plugin uploaded and enabled
- [ ] Browser cache cleared
- [ ] Bitbucket logs show successful component registration

After installation, verify:

- [ ] Admin page loads without errors
- [ ] Browser console shows "Configuration loaded"
- [ ] REST API returns 200 OK with JSON config
- [ ] No 500 errors in browser or Bitbucket logs

---

## Quick Command Summary

```bash
# 1. Verify/rebuild JAR
mvn clean package -DskipTests
ls -lh target/ai-code-reviewer-1.0.0-SNAPSHOT.jar

# 2. Check what's registered in JAR
jar xf target/ai-code-reviewer-1.0.0-SNAPSHOT.jar META-INF/plugin-components/component
cat META-INF/plugin-components/component
# Should include: com.example.bitbucket.aireviewer.util.HttpClientUtil

# 3. Monitor Bitbucket logs
tail -f <bitbucket-home>/log/atlassian-bitbucket.log
```

---

## Success Criteria

When everything is working correctly:

✅ **Admin page loads** without "Failed to load configuration" error
✅ **REST API responds** with 200 OK and JSON configuration
✅ **Browser console** shows successful configuration load
✅ **Bitbucket logs** show no dependency injection errors
✅ **Test Connection button** makes actual HTTP calls to Ollama

---

**Last Updated:** October 20, 2025
**Fix Version:** 1.0.0-SNAPSHOT (built 14:12:19)
**Status:** ✅ Ready for installation
