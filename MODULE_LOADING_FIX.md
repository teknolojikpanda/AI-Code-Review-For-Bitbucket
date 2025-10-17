# Module Loading Fix - ClassNotFoundException

## Problem

After installing the plugin in Bitbucket, it failed to enable with:

```
java.lang.RuntimeException: Unable to enable web fragment
...
Caused by: java.lang.ClassNotFoundException:
com.atlassian.bitbucket.web.conditions.IsAdminCondition not found
```

## Root Cause

The `atlassian-plugin.xml` was declaring modules (servlets, REST endpoints, web items) that referenced classes which don't exist yet:

1. **Web Item** - Referenced `com.atlassian.bitbucket.web.conditions.IsAdminCondition` (condition class)
2. **Servlet** - Referenced `com.example.bitbucket.aireviewer.servlet.AdminConfigServlet` (not implemented)
3. **REST API** - Referenced package `com.example.bitbucket.aireviewer.rest` (empty package)
4. **Web Resources** - Referenced CSS/JS files that don't exist

## Solution

Commented out all UI modules until the implementation is complete. The plugin now only declares:

1. ✅ **Plugin metadata** - Basic plugin information
2. ✅ **i18n resources** - Internationalization properties
3. ✅ **Active Objects entities** - Database entities (already implemented)

### Changes Made

**Updated atlassian-plugin.xml:**

```xml
<!-- Active Objects Entity Definitions -->
<ao key="ao-module">
    <description>Active Objects module for AI Code Reviewer</description>
    <entity>com.example.bitbucket.aireviewer.ao.AIReviewConfiguration</entity>
    <entity>com.example.bitbucket.aireviewer.ao.AIReviewHistory</entity>
</ao>

<!--
    UI MODULES COMMENTED OUT UNTIL IMPLEMENTATION IS COMPLETE

    Uncomment these modules after implementing:
    - REST resources in com.example.bitbucket.aireviewer.rest
    - AdminConfigServlet in com.example.bitbucket.aireviewer.servlet
    - Admin UI resources (CSS, JS, templates)
-->

<!-- All servlet, web-item, rest, and web-resource modules commented out -->
```

## What This Means

### Current Plugin Capabilities

After installation, the plugin will:
- ✅ Install successfully in Bitbucket
- ✅ Show up in the plugin list
- ✅ Create Active Objects database tables
- ❌ Have no UI (no admin page, no menu items)
- ❌ Have no REST API endpoints
- ❌ Have no event listeners (no PR reviews yet)

### When to Uncomment Modules

Uncomment each module **after** implementing its required classes:

#### 1. REST API Module
Uncomment after creating REST resources:
- `ConfigResource.java`
- `HistoryResource.java`

```xml
<rest key="aiReviewerRestEndpoints" path="/ai-reviewer" version="1.0">
    <description>REST API for AI Code Reviewer configuration</description>
    <package>com.example.bitbucket.aireviewer.rest</package>
</rest>
```

#### 2. Admin Servlet
Uncomment after creating:
- `AdminConfigServlet.java`

```xml
<servlet key="ai-reviewer-admin-servlet"
         name="AI Reviewer Admin Servlet"
         class="com.example.bitbucket.aireviewer.servlet.AdminConfigServlet">
    <description>Admin configuration page for AI Code Reviewer</description>
    <url-pattern>/ai-reviewer/admin</url-pattern>
</servlet>
```

#### 3. Admin Menu Link
Uncomment after servlet is working:

```xml
<web-item key="ai-reviewer-admin-link"
          name="AI Code Reviewer"
          section="atl.admin/admin-plugins-section"
          weight="200">
    <description>Link to AI Code Reviewer configuration</description>
    <label key="ai.reviewer.admin.link"/>
    <link>/plugins/servlet/ai-reviewer/admin</link>
    <condition class="com.atlassian.bitbucket.web.conditions.IsAdminCondition"/>
</web-item>
```

**Note:** The condition class `com.atlassian.bitbucket.web.conditions.IsAdminCondition` should work once you have proper imports. If it still doesn't work, you can:
- Remove the `<condition>` element entirely
- Or use a custom condition class that you implement

#### 4. Web Resources
Uncomment after creating:
- `src/main/resources/css/ai-reviewer-admin.css`
- `src/main/resources/js/ai-reviewer-admin.js`

```xml
<web-resource key="ai-reviewer-admin-resources" name="AI Reviewer Admin Resources">
    <description>Resources for the admin configuration page</description>
    <!-- Dependencies and resources -->
</web-resource>
```

## Installation Now

With the minimal plugin descriptor:

1. **Rebuild:**
   ```bash
   mvn clean package -DskipTests
   ```

2. **Install in Bitbucket:**
   - Go to **Administration** → **Manage apps**
   - Upload `target/ai-code-reviewer-1.0.0-SNAPSHOT.jar`
   - Should install successfully ✅

3. **Verify:**
   - Plugin shows in the list
   - No errors in logs
   - Active Objects tables created in database

## Database Tables Created

After installation, check that these tables exist:

```sql
-- Active Objects will create tables with a prefix like AO_<random>_

-- Configuration table
SELECT * FROM AO_XXXXXX_AI_REVIEW_CONFIG;

-- History table
SELECT * FROM AO_XXXXXX_AI_REVIEW_HISTORY;
```

## Next Steps - Implementation Order

### Phase 1: Services (Core Functionality)
1. Implement `AIReviewerConfigServiceImpl`
2. Implement `AIReviewServiceImpl`
3. Implement `PullRequestAIReviewListener`
4. Test with PR events

### Phase 2: REST API
1. Create `ConfigResource.java`
2. Create `HistoryResource.java`
3. Uncomment REST module in atlassian-plugin.xml
4. Rebuild and test endpoints

### Phase 3: Admin UI
1. Create `AdminConfigServlet.java`
2. Create Velocity template
3. Create CSS and JavaScript
4. Uncomment servlet and web-item modules
5. Rebuild and test admin page

## Troubleshooting

### Plugin Still Won't Install

Check for:
- **Compilation errors** - Run `mvn compile` to verify
- **Missing dependencies** - Review pom.xml
- **Plugin key mismatch** - Verify manifest and XML match

### Tables Not Created

Active Objects tables are created when:
- Plugin installs successfully
- `<ao>` module is declared in atlassian-plugin.xml ✅
- Entity classes exist and are valid ✅

Check Bitbucket logs for Active Objects migration errors.

### Want to Test Something Specific

You can selectively uncomment modules for testing:

```xml
<!-- Test REST API only -->
<rest key="aiReviewerRestEndpoints" ... />

<!-- Keep servlet, web-item commented out -->
```

## Benefits of This Approach

✅ **Iterative Development** - Add features incrementally
✅ **No Installation Errors** - Plugin always installs cleanly
✅ **Easy Testing** - Test each component individually
✅ **Clean Rollback** - Comment out broken modules
✅ **Database Ready** - Active Objects tables created from day one

## Related Files

- [atlassian-plugin.xml](src/main/resources/atlassian-plugin.xml) - Minimal plugin descriptor
- [BUILD_FIX.md](BUILD_FIX.md) - Dependency fixes
- [PLUGIN_KEY_FIX.md](PLUGIN_KEY_FIX.md) - Plugin key fix
- [IMPLEMENTATION_CHECKLIST.md](IMPLEMENTATION_CHECKLIST.md) - Implementation tasks

## Success! 🎉

With this fix:
- ✅ Plugin builds successfully
- ✅ Plugin installs in Bitbucket
- ✅ Active Objects tables created
- ✅ Ready for incremental implementation

Start implementing the service layer, then progressively uncomment UI modules as you build them!
