# Servlet Not Found Error - Fixed

## Problem

When accessing the "AI Code Reviewer" menu in Bitbucket Administration, the following error occurred:

```
Could not find servlet.
```

## Root Cause

The `AdminConfigServlet` class was missing the `@Named` annotation, which is required for Spring Scanner to discover and register the servlet component.

When servlets use constructor injection with `@Inject` and `@ComponentImport`, they must be annotated with `@Named` so that:
1. Spring Scanner can discover the class during build
2. The dependency injection framework can instantiate it
3. Bitbucket can properly bind the servlet to its URL pattern

### Why This Happened

The servlet was correctly declared in `atlassian-plugin.xml`:

```xml
<servlet key="ai-reviewer-admin-servlet"
         class="com.example.bitbucket.aireviewer.servlet.AdminConfigServlet">
    <url-pattern>/ai-reviewer/admin</url-pattern>
</servlet>
```

However, without the `@Named` annotation, Spring Scanner didn't process the class, so:
- Constructor injection didn't work
- Dependencies (UserManager, TemplateRenderer, etc.) couldn't be injected
- Servlet couldn't be instantiated
- Bitbucket couldn't find the servlet at the URL pattern

## Solution

Added `@Named` annotation to the servlet class.

### Code Change

**File:** `src/main/java/com/example/bitbucket/aireviewer/servlet/AdminConfigServlet.java`

**Before:**
```java
import javax.inject.Inject;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
// ... other imports

public class AdminConfigServlet extends HttpServlet {

    @Inject
    public AdminConfigServlet(
            @ComponentImport UserManager userManager,
            @ComponentImport LoginUriProvider loginUriProvider,
            @ComponentImport TemplateRenderer templateRenderer,
            @ComponentImport PermissionService permissionService) {
        // ...
    }
}
```

**After:**
```java
import javax.inject.Inject;
import javax.inject.Named;  // ← Added import
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
// ... other imports

@Named  // ← Added annotation
public class AdminConfigServlet extends HttpServlet {

    @Inject
    public AdminConfigServlet(
            @ComponentImport UserManager userManager,
            @ComponentImport LoginUriProvider loginUriProvider,
            @ComponentImport TemplateRenderer templateRenderer,
            @ComponentImport PermissionService permissionService) {
        // ...
    }
}
```

## Verification

### Spring Scanner Output

The build output confirms the servlet is now being discovered:

**Before:**
```
[INFO] Analysis ran in 82 ms.
[INFO] Encountered 4 total classes
[INFO] Processed 1 annotated classes
```

**After:**
```
[INFO] Analysis ran in 82 ms.
[INFO] Encountered 4 total classes
[INFO] Processed 2 annotated classes  ← Increased from 1 to 2
```

### Build Success

```bash
$ mvn clean package -DskipTests
[INFO] BUILD SUCCESS
[INFO] Total time: 5.619 s

$ ls -lh target/*.jar
-rw-r--r-- 1 cducak admin 256K Oct 17 16:12 target/ai-code-reviewer-1.0.0-SNAPSHOT.jar
```

## Testing the Fix

### Step 1: Upload New JAR

1. Go to **Bitbucket Administration** → **Manage apps**
2. Find "AI Code Reviewer for Bitbucket"
3. Click **"Uninstall"** (if previously installed)
4. Click **"Upload app"**
5. Select the newly built JAR: `target/ai-code-reviewer-1.0.0-SNAPSHOT.jar`
6. Click **"Upload"**

### Step 2: Verify Installation

1. Check plugin status shows **"Enabled"**
2. No errors in Bitbucket logs
3. Active Objects tables are present

### Step 3: Access Admin Page

**Option A - Via Menu:**
1. Go to **Administration** → **Add-ons** section
2. Look for **"AI Code Reviewer"** menu item
3. Click to open configuration page

**Option B - Direct URL:**
```
https://your-bitbucket-url/plugins/servlet/ai-reviewer/admin
```

### Expected Result

✅ Configuration page loads successfully
✅ Form displays with all fields
✅ No "Could not find servlet" error

## Related Components

The same `@Named` annotation pattern is used in the REST API resource:

**ConfigResource.java:**
```java
@Path("/config")
@Named  // ← Already has @Named annotation
public class ConfigResource {
    // ...
}
```

This is why the REST API endpoints work, but the servlet didn't.

## Key Learnings

### When to Use @Named

Use `@Named` annotation on any class that:

1. **Uses constructor injection** with `@Inject`
2. **Is referenced in atlassian-plugin.xml** (servlets, components)
3. **Needs dependency injection** of `@ComponentImport` services

### Classes That Need @Named

✅ Servlets with constructor injection
✅ REST resources (JAX-RS classes with `@Path`)
✅ Event listeners with dependencies
✅ Service implementations with dependencies

### Classes That Don't Need @Named

❌ Active Objects entities (interfaces)
❌ POJOs with no dependencies
❌ Static utility classes
❌ Classes with no-arg constructors

## Common Patterns

### Pattern 1: Servlet with Dependencies
```java
@Named
public class MyServlet extends HttpServlet {
    @Inject
    public MyServlet(
            @ComponentImport UserManager userManager,
            @ComponentImport TemplateRenderer templateRenderer) {
        // ...
    }
}
```

### Pattern 2: REST Resource with Dependencies
```java
@Path("/my-endpoint")
@Named
public class MyResource {
    @Inject
    public MyResource(
            @ComponentImport ActiveObjects ao,
            @ComponentImport PermissionService permissionService) {
        // ...
    }
}
```

### Pattern 3: Event Listener with Dependencies
```java
@Named
public class MyListener {
    @Inject
    public MyListener(
            @ComponentImport EventPublisher eventPublisher,
            @ComponentImport PullRequestService pullRequestService) {
        // Register for events
    }

    @EventListener
    public void onPullRequestOpened(PullRequestOpenedEvent event) {
        // Handle event
    }
}
```

## Troubleshooting Other Servlet Issues

If you still see "Could not find servlet" after applying this fix:

### Check 1: Spring Scanner Processing

Look for this in build output:
```
[INFO] Processed 2 annotated classes
```

If it still shows `1 annotated classes`, the servlet isn't being scanned.

**Solutions:**
- Verify `@Named` annotation is present
- Check class is in the right package (`com.example.bitbucket.aireviewer.*`)
- Ensure class is not in `test` source directory

### Check 2: Plugin Descriptor

Verify servlet declaration in `atlassian-plugin.xml`:

```xml
<servlet key="ai-reviewer-admin-servlet"
         class="com.example.bitbucket.aireviewer.servlet.AdminConfigServlet">
    <url-pattern>/ai-reviewer/admin</url-pattern>
</servlet>
```

**Check:**
- ✅ `class` attribute matches fully qualified class name
- ✅ `url-pattern` starts with `/`
- ✅ `key` is unique

### Check 3: URL Pattern

Verify the web-item link matches the servlet URL pattern:

**Web Item:**
```xml
<link>/plugins/servlet/ai-reviewer/admin</link>
```

**Servlet:**
```xml
<url-pattern>/ai-reviewer/admin</url-pattern>
```

The link should be: `/plugins/servlet` + `{url-pattern}`

### Check 4: Dependencies

Verify all `@ComponentImport` dependencies are available:

```java
@Inject
public AdminConfigServlet(
        @ComponentImport UserManager userManager,           // From SAL API
        @ComponentImport LoginUriProvider loginUriProvider, // From SAL API
        @ComponentImport TemplateRenderer templateRenderer, // From Template Renderer API
        @ComponentImport PermissionService permissionService) { // From Bitbucket API
    // ...
}
```

**Check pom.xml has:**
- `sal-api` (provided)
- `atlassian-template-renderer-api` (provided)
- `bitbucket-api` (provided)

### Check 5: Logs

Check Bitbucket logs for more details:

```bash
tail -f /path/to/bitbucket/logs/atlassian-bitbucket.log | grep -i "servlet\|aireviewer"
```

Look for:
- Servlet initialization errors
- Dependency injection failures
- ClassNotFoundException
- NoSuchMethodException

## Summary

✅ **Problem:** "Could not find servlet" error
✅ **Cause:** Missing `@Named` annotation on servlet class
✅ **Solution:** Added `@Named` annotation
✅ **Result:** Spring Scanner now processes the servlet (2 annotated classes instead of 1)
✅ **Status:** Ready for testing - reinstall plugin to verify fix

The new JAR file at `target/ai-code-reviewer-1.0.0-SNAPSHOT.jar` should now work correctly when installed in Bitbucket.
