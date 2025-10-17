# AI Code Reviewer Plugin - Installation Guide

## Quick Installation

### Prerequisites

- Bitbucket Data Center 8.9.0 or higher
- Administrator access to Bitbucket
- Ollama service running (for actual code reviews)

### Step 1: Build the Plugin (if not already built)

```bash
cd /home/cducak/Downloads/ai_code_review
mvn clean package -DskipTests
```

**Expected output:**
- `BUILD SUCCESS`
- JAR created at: `target/ai-code-reviewer-1.0.0-SNAPSHOT.jar`
- File size: ~257 KB

### Step 2: Upload to Bitbucket

1. **Access Admin Panel:**
   - Log in to Bitbucket as an administrator
   - Navigate to **Administration** (gear icon in top right)
   - Click **Manage apps** in the left sidebar

2. **Upload Plugin:**
   - Click **"Upload app"** button
   - Select file: `target/ai-code-reviewer-1.0.0-SNAPSHOT.jar`
   - Click **"Upload"**

3. **Wait for Installation:**
   - Bitbucket will upload and install the plugin
   - Progress bar will show installation status
   - This may take 30-60 seconds

### Step 3: Verify Installation

Check the following:

1. **Plugin Status:**
   - Go to **Administration** → **Manage apps**
   - Find "AI Code Reviewer for Bitbucket" in the list
   - Status should show: **"Enabled"** ✅

2. **Check Logs (optional):**
   ```bash
   tail -f /path/to/bitbucket/logs/atlassian-bitbucket.log
   ```
   Look for:
   ```
   [INFO] Successfully installed plugin: com.example.bitbucket.ai-code-reviewer
   ```

3. **Verify Database Tables:**
   - Active Objects tables should be created automatically:
     - `AO_XXXXXX_AI_REVIEW_CONFIG`
     - `AO_XXXXXX_AI_REVIEW_HISTORY`

   Check with SQL (if you have database access):
   ```sql
   SELECT table_name FROM information_schema.tables
   WHERE table_name LIKE 'AO_%AI_REVIEW%';
   ```

### Step 4: Access Configuration Page

1. **Navigate to Admin Menu:**
   - Go to **Administration** → **Add-ons** section
   - Look for **"AI Code Reviewer"** menu item
   - Click to open configuration page

2. **Configuration Page URL:**
   ```
   https://your-bitbucket-url/plugins/servlet/ai-reviewer/admin
   ```

3. **Expected Result:**
   - Admin configuration page loads
   - Form displays with default values
   - All sections are visible

### Step 5: Configure Ollama Connection

1. **Ollama Configuration:**
   - **Ollama URL:** Enter your Ollama service URL
     - Example: `http://10.152.98.37:11434`
     - Or: `http://ollama.internal:11434`

   - **Primary AI Model:** Enter your main model
     - Example: `qwen3-coder:30b`
     - Or: `codellama:13b`

   - **Fallback AI Model:** (Optional) Backup model
     - Example: `qwen3-coder:7b`

2. **Test Connection:**
   - Click **"Test Connection"** button
   - Currently validates URL format only
   - Green checkmark ✓ means URL is valid

3. **Review Settings (Optional):**
   - Adjust processing limits if needed
   - Configure file filtering patterns
   - Set review severity thresholds

4. **Save Configuration:**
   - Click **"Save Configuration"** button
   - Success message should appear
   - Configuration will be saved (to database once service layer is implemented)

## Configuration Reference

### Default Values

The plugin comes pre-configured with sensible defaults:

```yaml
Ollama Configuration:
  URL: http://10.152.98.37:11434
  Primary Model: qwen3-coder:30b
  Fallback Model: qwen3-coder:7b

Processing:
  Max Chars Per Chunk: 60000
  Max Files Per Chunk: 3
  Max Chunks: 20
  Parallel Threads: 4

Timeouts (milliseconds):
  Connection: 10000
  Read: 30000
  Ollama Analysis: 300000

Review:
  Max Issues Per File: 50
  Max Issue Comments: 30
  Max Diff Size: 10000000

Retry:
  Max Retries: 3
  Base Retry Delay: 1000ms
  API Delay: 100ms

Review Profile:
  Minimum Severity: medium
  Require Approval For: critical,high

File Filtering:
  Extensions: java,groovy,js,ts,tsx,jsx,py,go,rs,cpp,c,cs,php,rb,kt,swift,scala
  Ignore Patterns: *.min.js,*.generated.*,package-lock.json,yarn.lock,*.map
  Ignore Paths: node_modules/,vendor/,build/,dist/,.git/

Feature Flags:
  Enabled: true
  Review Draft PRs: false
  Skip Generated Files: true
  Skip Tests: false
```

### Adjusting Configuration

**For Large Codebases:**
```yaml
Max Chunks: 50          # Increase from 20
Parallel Threads: 8     # Increase from 4
Max Diff Size: 20000000 # Increase from 10MB
```

**For Faster Models (smaller models):**
```yaml
Ollama Timeout: 120000  # Reduce from 300 seconds to 120
Max Chars Per Chunk: 80000  # Increase chunk size
```

**For More Thorough Reviews:**
```yaml
Min Severity: low       # Show all issues
Max Issues Per File: 100  # Show more issues
```

**For Less Noise:**
```yaml
Min Severity: high      # Only critical/high issues
Max Issue Comments: 15  # Fewer comments
```

## Troubleshooting

### Plugin Won't Install

**Symptom:** Upload fails or plugin shows error status

**Solutions:**
1. Check Bitbucket version compatibility (requires 8.9.0+)
2. Check logs for specific error:
   ```bash
   tail -f /path/to/bitbucket/logs/atlassian-bitbucket.log
   ```
3. Verify JAR isn't corrupted:
   ```bash
   unzip -t target/ai-code-reviewer-1.0.0-SNAPSHOT.jar
   ```
4. Try rebuilding:
   ```bash
   mvn clean package -DskipTests
   ```

### Plugin Installs But Won't Enable

**Symptom:** Plugin status shows "Disabled" or error icon

**Solutions:**
1. Check for dependency conflicts in logs
2. Verify Active Objects tables can be created
3. Check database connectivity
4. Try disabling and re-enabling:
   - Go to **Manage apps**
   - Find plugin
   - Click **"Disable"** then **"Enable"**

### Admin Menu Not Visible

**Symptom:** No "AI Code Reviewer" item in Administration menu

**Solutions:**
1. Verify you're logged in as administrator
2. Check plugin is enabled
3. Try accessing URL directly:
   ```
   https://your-bitbucket-url/plugins/servlet/ai-reviewer/admin
   ```
4. Check logs for servlet initialization errors

### Configuration Page Shows Errors

**Symptom:** Page loads but shows error messages

**Solutions:**
1. Check browser console for JavaScript errors (F12)
2. Verify REST API is accessible:
   ```bash
   curl -u admin:password https://your-bitbucket-url/rest/ai-reviewer/1.0/config
   ```
3. Check network tab in browser dev tools
4. Clear browser cache and reload

### Test Connection Fails

**Symptom:** "Test Connection" shows error

**Current Behavior:** Only validates URL format (full connection test not yet implemented)

**Solutions:**
1. Ensure URL is well-formed (http:// or https://)
2. Example valid URLs:
   - `http://10.152.98.37:11434`
   - `https://ollama.internal:11434`
3. Invalid URLs will fail validation:
   - `ollama:11434` (missing protocol)
   - `ftp://ollama:11434` (wrong protocol)

### Configuration Won't Save

**Symptom:** Success message appears but settings don't persist

**Current Status:** Configuration service not yet implemented

**Workaround:** Configuration will be saved once the service layer is implemented (see ADMIN_UI_IMPLEMENTATION.md for details)

## Uninstalling

To remove the plugin:

1. **Via UI:**
   - Go to **Administration** → **Manage apps**
   - Find "AI Code Reviewer for Bitbucket"
   - Click **"Uninstall"**
   - Confirm uninstallation

2. **Database Cleanup (optional):**

   Active Objects tables will remain after uninstall. To remove them:

   ```sql
   -- Find the table names (XXXXXX is a unique ID)
   SELECT table_name FROM information_schema.tables
   WHERE table_name LIKE 'AO_%AI_REVIEW%';

   -- Drop the tables
   DROP TABLE AO_XXXXXX_AI_REVIEW_CONFIG;
   DROP TABLE AO_XXXXXX_AI_REVIEW_HISTORY;
   ```

3. **Reinstalling:**

   If you want to reinstall after uninstalling:
   - Just upload the JAR again
   - Database tables will be recreated automatically
   - Previous configuration data will be lost

## Upgrading

To upgrade to a newer version:

1. **Build New Version:**
   ```bash
   cd /home/cducak/Downloads/ai_code_review
   git pull  # If using version control
   mvn clean package -DskipTests
   ```

2. **Upload New JAR:**
   - Go to **Administration** → **Manage apps**
   - Click **"Upload app"**
   - Select the new JAR file
   - Bitbucket will handle the upgrade automatically

3. **Verify Upgrade:**
   - Check plugin version in **Manage apps**
   - Review logs for any migration messages
   - Test configuration page still works

**Note:** Active Objects will automatically handle schema migrations if entity definitions change.

## REST API Reference

For programmatic access to configuration:

### Get Configuration
```bash
curl -X GET \
  https://your-bitbucket-url/rest/ai-reviewer/1.0/config \
  -u admin:password \
  -H "Accept: application/json"
```

### Update Configuration
```bash
curl -X PUT \
  https://your-bitbucket-url/rest/ai-reviewer/1.0/config \
  -u admin:password \
  -H "Content-Type: application/json" \
  -d '{
    "ollamaUrl": "http://ollama:11434",
    "ollamaModel": "qwen3-coder:30b",
    "enabled": true,
    "maxCharsPerChunk": 60000
  }'
```

### Test Connection
```bash
curl -X POST \
  https://your-bitbucket-url/rest/ai-reviewer/1.0/config/test-connection \
  -u admin:password \
  -H "Content-Type: application/json" \
  -d '{"ollamaUrl": "http://ollama:11434"}'
```

**Response Format:**
```json
{
  "success": true,
  "message": "Configuration updated successfully"
}
```

**Error Response:**
```json
{
  "error": "Access denied. Administrator privileges required."
}
```

## Support

For issues or questions:

1. **Check Documentation:**
   - ADMIN_UI_IMPLEMENTATION.md - Implementation details
   - README.md - General plugin information
   - QUICK_START_GUIDE.md - Developer guide

2. **Check Logs:**
   - Bitbucket logs: `/path/to/bitbucket/logs/atlassian-bitbucket.log`
   - Look for: `com.example.bitbucket.aireviewer`

3. **Report Issues:**
   - Include plugin version
   - Include Bitbucket version
   - Include relevant log excerpts
   - Include steps to reproduce

## Next Steps

After installation:

1. ✅ Verify plugin is enabled
2. ✅ Access configuration page
3. ✅ Configure Ollama connection
4. ⏳ Wait for service layer implementation (config persistence)
5. ⏳ Wait for event listener implementation (actual PR reviews)
6. ⏳ Test with a sample pull request

**Current Status:** The admin UI is complete and functional. The service layer implementation is the next priority to enable actual code review functionality.
