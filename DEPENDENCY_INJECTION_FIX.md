# Dependency Injection Fix - Component Import Error

## Problem

When accessing the "AI Code Reviewer" admin page, Bitbucket returned an error:

```
Error creating bean with name 'com.example.bitbucket.aireviewer.servlet.AdminConfigServlet':
Unsatisfied dependency expressed through constructor parameter 0;
nested exception is org.springframework.beans.factory.NoSuchBeanDefinitionException:
No qualifying bean of type 'com.atlassian.sal.api.user.UserManager' available:
expected at least 1 bean which qualifies as autowire candidate.
Dependency annotations: {@com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport("")}
```

## Root Cause

The issue had **two parts**:

### Part 1: Missing @Named Annotation (Already Fixed)
The servlet class was missing `@Named` annotation, which is required for Spring Scanner to discover the component.

### Part 2: Component Imports Not Declared (New Issue)
Even with `@Named`, the `@ComponentImport` annotations weren't working because:

1. We had `<Atlassian-Plugin-Key>` in the OSGi manifest instructions
2. When this header is set, `<component-import>` declarations **are required** in `atlassian-plugin.xml`
3. The error message even mentioned this: "This usually means that you haven't created a <component-import> for the interface you're trying to use"

## Solution

Applied **two fixes**:

### Fix 1: Add Component Import Declarations

Added explicit `<component-import>` declarations in `atlassian-plugin.xml` for all dependencies used by the servlet and REST resource:

```xml
<!-- Component imports for dependency injection -->
<component-import key="userManager" interface="com.atlassian.sal.api.user.UserManager"/>
<component-import key="loginUriProvider" interface="com.atlassian.sal.api.auth.LoginUriProvider"/>
<component-import key="templateRenderer" interface="com.atlassian.templaterenderer.TemplateRenderer"/>
<component-import key="permissionService" interface="com.atlassian.bitbucket.permission.PermissionService"/>
<component-import key="activeObjects" interface="com.atlassian.activeobjects.external.ActiveObjects"/>
<component-import key="pluginAccessor" interface="com.atlassian.plugin.PluginAccessor"/>
```

### Fix 2: Remove Atlassian-Plugin-Key from Manifest

Removed the `<Atlassian-Plugin-Key>` instruction from `pom.xml` because:
- It conflicts with `<component-import>` declarations
- The plugin key is already defined in `atlassian-plugin.xml`
- OSGi will use `Bundle-SymbolicName` instead

**Before (pom.xml):**
```xml
<instructions>
    <Atlassian-Plugin-Key>${project.groupId}.${project.artifactId}</Atlassian-Plugin-Key>
    <Export-Package/>
    <Import-Package>...</Import-Package>
    <Spring-Context>*</Spring-Context>
</instructions>
```

**After (pom.xml):**
```xml
<instructions>
    <Export-Package/>
    <Import-Package>...</Import-Package>
    <Spring-Context>*</Spring-Context>
</instructions>
```

## Technical Details

### Why Component Imports Are Needed

When using Atlassian Plugin SDK with Spring Scanner 2.x:

1. **Without `Atlassian-Plugin-Key` in manifest:**
   - Spring Scanner auto-generates component imports
   - `@ComponentImport` works automatically
   - No manual `<component-import>` declarations needed

2. **With `Atlassian-Plugin-Key` in manifest:**
   - Auto-generation is disabled
   - Manual `<component-import>` declarations are **required**
   - `@ComponentImport` annotations alone don't work

We initially added `Atlassian-Plugin-Key` to fix a plugin key resolution error (see [PLUGIN_KEY_FIX.md](PLUGIN_KEY_FIX.md)), but this created the component import issue.

### The Solution Strategy

Rather than removing the plugin key entirely, we:
1. Removed it from the OSGi manifest instructions
2. Kept it in `atlassian-plugin.xml` (where it belongs)
3. Added explicit component-import declarations
4. Let OSGi use `Bundle-SymbolicName` for the key

This approach satisfies both requirements:
- Plugin key is correctly defined ✅
- Component imports work properly ✅

## Verification

### Build Output

```bash
$ mvn clean package -DskipTests
[INFO] BUILD SUCCESS
[INFO] Total time: 5.622 s
```

### Spring Scanner Output

```
[INFO] Encountered 4 total classes
[INFO] Processed 2 annotated classes
```

The servlet is being discovered (count = 2, includes servlet + REST resource).

### Plugin Manifest

```bash
$ unzip -c target/*.jar META-INF/MANIFEST.MF | grep Bundle-SymbolicName
Bundle-SymbolicName: com.example.bitbucket.ai-code-reviewer
```

The plugin key is correctly set via `Bundle-SymbolicName`.

### Component Imports in JAR

```bash
$ unzip -c target/*.jar atlassian-plugin.xml | grep component-import
<component-import key="userManager" interface="com.atlassian.sal.api.user.UserManager"/>
<component-import key="loginUriProvider" interface="com.atlassian.sal.api.auth.LoginUriProvider"/>
<component-import key="templateRenderer" interface="com.atlassian.templaterenderer.TemplateRenderer"/>
<component-import key="permissionService" interface="com.atlassian.bitbucket.permission.PermissionService"/>
<component-import key="activeObjects" interface="com.atlassian.activeobjects.external.ActiveObjects"/>
<component-import key="pluginAccessor" interface="com.atlassian.plugin.PluginAccessor"/>
```

All component imports are present in the packaged JAR.

## Dependencies and Their Uses

### UserManager (com.atlassian.sal.api.user.UserManager)
- **Used by:** AdminConfigServlet, ConfigResource
- **Purpose:** Authentication check, get current user
- **From:** SAL API (provided by Bitbucket)

### LoginUriProvider (com.atlassian.sal.api.auth.LoginUriProvider)
- **Used by:** AdminConfigServlet
- **Purpose:** Redirect unauthenticated users to login page
- **From:** SAL API (provided by Bitbucket)

### TemplateRenderer (com.atlassian.templaterenderer.TemplateRenderer)
- **Used by:** AdminConfigServlet
- **Purpose:** Render Velocity templates (admin-config.vm)
- **From:** Template Renderer API (provided by Bitbucket)

### PermissionService (com.atlassian.bitbucket.permission.PermissionService)
- **Used by:** AdminConfigServlet, ConfigResource
- **Purpose:** Check if user has admin permissions
- **From:** Bitbucket API (provided by Bitbucket)

### ActiveObjects (com.atlassian.activeobjects.external.ActiveObjects)
- **Used by:** Future AIReviewerConfigService implementation
- **Purpose:** Database persistence for configuration
- **From:** Active Objects Plugin (provided by Bitbucket)

### PluginAccessor (com.atlassian.plugin.PluginAccessor)
- **Used by:** Future plugin management features
- **Purpose:** Access plugin metadata and resources
- **From:** Atlassian Plugin Framework (provided by Bitbucket)

## Installation Instructions

### 1. Uninstall Previous Version

1. Go to **Bitbucket Administration** → **Manage apps**
2. Find **"AI Code Reviewer for Bitbucket"**
3. Click **"Uninstall"**
4. Wait for confirmation

### 2. Install New Version

1. Click **"Upload app"**
2. Select: `/home/cducak/Downloads/ai_code_review/target/ai-code-reviewer-1.0.0-SNAPSHOT.jar`
3. Click **"Upload"**
4. Wait for installation (30-60 seconds)

### 3. Verify Installation

**Check plugin status:**
- Status shows **"Enabled"** ✅
- No error icons

**Check logs:**
```bash
tail -n 50 /path/to/bitbucket/logs/atlassian-bitbucket.log
```

Look for:
```
[INFO] Successfully installed plugin: com.example.bitbucket.ai-code-reviewer
[INFO] Plugin enabled: com.example.bitbucket.ai-code-reviewer
```

**No component import errors:**
```
# Should NOT see:
[ERROR] NoSuchBeanDefinitionException: No qualifying bean of type 'com.atlassian.sal.api.user.UserManager'
```

### 4. Access Admin Page

**Via Menu:**
1. Go to **Administration** → **Add-ons**
2. Click **"AI Code Reviewer"**

**Direct URL:**
```
https://your-bitbucket-url/plugins/servlet/ai-reviewer/admin
```

### 5. Expected Result

✅ Page loads successfully (no errors)
✅ Configuration form displays with all sections
✅ All fields have default values
✅ All buttons work (Test Connection, Save, Reset)

## Troubleshooting

### If You Still See Component Import Errors

**Check 1: Verify component-import declarations are in JAR**

```bash
unzip -l target/ai-code-reviewer-1.0.0-SNAPSHOT.jar atlassian-plugin.xml
unzip -c target/ai-code-reviewer-1.0.0-SNAPSHOT.jar atlassian-plugin.xml | grep component-import
```

Should show 6 component-import declarations.

**Check 2: Verify plugin key in manifest**

```bash
unzip -c target/ai-code-reviewer-1.0.0-SNAPSHOT.jar META-INF/MANIFEST.MF | grep Bundle-SymbolicName
```

Should show: `Bundle-SymbolicName: com.example.bitbucket.ai-code-reviewer`

**Check 3: Check for Atlassian-Plugin-Key in manifest**

```bash
unzip -c target/ai-code-reviewer-1.0.0-SNAPSHOT.jar META-INF/MANIFEST.MF | grep Atlassian-Plugin-Key
```

Should return **nothing** (key should NOT be in manifest).

**Check 4: Verify @Named annotation**

```bash
grep "@Named" src/main/java/com/example/bitbucket/aireviewer/servlet/AdminConfigServlet.java
```

Should show: `@Named` on line 29.

### If Page Shows Different Error

**"Could not find servlet":**
- See [SERVLET_NOT_FOUND_FIX.md](SERVLET_NOT_FOUND_FIX.md)
- Verify `@Named` annotation is present

**"Access denied":**
- Ensure you're logged in as Bitbucket system admin
- Regular admins can't access system settings

**"404 Not Found":**
- Verify URL is correct: `/plugins/servlet/ai-reviewer/admin`
- Check plugin is enabled in Manage apps

### Checking Logs for Specific Errors

```bash
# Watch logs in real-time
tail -f /path/to/bitbucket/logs/atlassian-bitbucket.log | grep -i "aireviewer\|component\|bean"

# Check for successful component loading
grep "component-import" /path/to/bitbucket/logs/atlassian-bitbucket.log | tail -20

# Check for dependency injection errors
grep "NoSuchBeanDefinitionException" /path/to/bitbucket/logs/atlassian-bitbucket.log | tail -10
```

## Related Documentation

- [SERVLET_NOT_FOUND_FIX.md](SERVLET_NOT_FOUND_FIX.md) - Previous servlet discovery issue
- [PLUGIN_KEY_FIX.md](PLUGIN_KEY_FIX.md) - Plugin key resolution (led to this issue)
- [JDK_COMPATIBILITY_FIX.md](JDK_COMPATIBILITY_FIX.md) - JDK 21 REST docs fix
- [BUILD_STATUS.md](BUILD_STATUS.md) - Overall project status

## Summary of All Fixes Applied

1. ✅ **JDK 21 Compatibility** - Added `<skipRestDocGeneration>true</skipRestDocGeneration>`
2. ✅ **Servlet Discovery** - Added `@Named` annotation to AdminConfigServlet
3. ✅ **Dependency Injection** - Added `<component-import>` declarations
4. ✅ **Plugin Key** - Removed from manifest, kept in atlassian-plugin.xml

## Current Status

**Build:** 🟢 Success (no errors)
**JAR:** 🟢 Ready at `target/ai-code-reviewer-1.0.0-SNAPSHOT.jar`
**Plugin Key:** 🟢 Correctly set via Bundle-SymbolicName
**Servlet:** 🟢 Discovered by Spring Scanner (@Named)
**Dependencies:** 🟢 Component imports declared
**Expected:** 🟢 Admin page should load without errors

---

**Build Date:** Oct 17, 2025 16:18
**JAR Size:** 256 KB
**Plugin Key:** com.example.bitbucket.ai-code-reviewer
**Version:** 1.0.0-SNAPSHOT

The plugin is ready for installation. Install the new JAR and the dependency injection errors should be resolved.
