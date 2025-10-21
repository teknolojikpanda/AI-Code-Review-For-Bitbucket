# CRITICAL FIX: @ComponentImport for JAX-RS Dependency Injection

**Date:** October 21, 2025
**Issue:** UnsatisfiedDependencyException when accessing REST endpoint
**Root Cause:** Framework mismatch between Jersey/HK2 and Atlassian Spring containers

## Problem Description

### Error Message
```
org.glassfish.hk2.api.UnsatisfiedDependencyException: There was no object available for injection at SystemInjecteeImpl(requiredType=AIReviewerConfigService,parent=ConfigResource,qualifiers={},position=1,optional=false,self=false,unqualified=null,...)
```

### What Was Happening

The plugin had the following dependency chain:
1. **ConfigResource** (JAX-RS REST endpoint) needs **AIReviewerConfigService**
2. **AIReviewerConfigServiceImpl** (@Named component) needs **HttpClientUtil**
3. **HttpClientUtil** (@Named component) has proper constructors

All components were:
- ✅ Properly annotated with @Named
- ✅ Registered in META-INF/plugin-components/component
- ✅ Exported in META-INF/plugin-components/exports
- ✅ Had correct constructors

**BUT the error still occurred!**

## Root Cause Analysis

### The Framework Mismatch

Bitbucket plugins use **TWO separate dependency injection containers**:

1. **Atlassian Spring Container** (via Spring Scanner)
   - Manages @Named components
   - Scanned by atlassian-spring-scanner-maven-plugin
   - Registered in META-INF/plugin-components/component

2. **Jersey/HK2 Container** (for JAX-RS REST resources)
   - Manages JAX-RS resources (@Path)
   - Uses @Inject for constructor injection
   - Separate component registry from Spring

### The Problem

**ConfigResource.java** before fix:
```java
@Path("/config")
@Named  // Registers with Spring container
public class ConfigResource {

    @Inject
    public ConfigResource(
            @ComponentImport UserManager userManager,
            AIReviewerConfigService configService) {  // ❌ NO @ComponentImport!
        // ...
    }
}
```

**What happened:**
1. Jersey/HK2 detected ConfigResource as a JAX-RS resource (@Path)
2. HK2 saw @Inject constructor and tried to inject dependencies
3. HK2 looked for `AIReviewerConfigService` in **HK2's registry**
4. But `AIReviewerConfigService` was only in **Spring's registry**!
5. HK2 couldn't find it → UnsatisfiedDependencyException

**Why UserManager worked but AIReviewerConfigService didn't:**
- UserManager had `@ComponentImport` → Bridge told HK2 to look in Spring container
- AIReviewerConfigService had NO `@ComponentImport` → HK2 only looked in HK2 registry

## The Fix

Add `@ComponentImport` annotation to the AIReviewerConfigService parameter:

**ConfigResource.java** after fix:
```java
@Path("/config")
@Named
public class ConfigResource {

    @Inject
    public ConfigResource(
            @ComponentImport UserManager userManager,
            @ComponentImport AIReviewerConfigService configService) {  // ✅ Added @ComponentImport
        this.userManager = userManager;
        this.configService = configService;
    }
}
```

## Why This Fix Works

`@ComponentImport` is a bridge annotation that tells Jersey/HK2:

> "This dependency is not in HK2's registry. Look it up from the Atlassian Spring container instead."

With this annotation:
1. HK2 sees @ComponentImport on AIReviewerConfigService parameter
2. HK2 delegates lookup to the Spring container bridge
3. Bridge finds AIReviewerConfigServiceImpl in Spring registry
4. Dependency injection succeeds!

## Files Changed

### src/main/java/com/example/bitbucket/aireviewer/rest/ConfigResource.java
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

## Verification

### Before Fix
```bash
$ javap -verbose target/classes/.../ConfigResource.class | grep -A 5 "RuntimeVisibleParameterAnnotations"
RuntimeVisibleParameterAnnotations:
  parameter 0:
    0: @ComponentImport()
  parameter 1:
    # NO ANNOTATION!
```

### After Fix
```bash
$ javap -verbose target/classes/.../ConfigResource.class | grep -A 5 "RuntimeVisibleParameterAnnotations"
RuntimeVisibleParameterAnnotations:
  parameter 0:
    0: @ComponentImport()
  parameter 1:
    0: @ComponentImport()  # ✅ Fixed!
```

## Deployment Instructions

### CRITICAL: This is a NEW fix, separate from the HttpClientUtil constructor fix!

**Previous JAR (with only constructor fix):** 338 KB, Oct 21 05:19
**New JAR (with @ComponentImport fix):** 331 KB, Oct 21 08:29

### Steps to Deploy

1. **STOP Bitbucket**
   ```bash
   docker exec bitbucket /opt/atlassian/bitbucket/8.9.0/bin/stop-bitbucket.sh
   # Wait 10 seconds
   ```

2. **UNINSTALL old plugin**
   - Go to: Bitbucket → Settings → Manage apps
   - Find: 'AI Code Reviewer for Bitbucket'
   - Click: 'Uninstall' (NOT 'Disable')

3. **START Bitbucket**
   ```bash
   docker exec bitbucket /opt/atlassian/bitbucket/8.9.0/bin/start-bitbucket.sh
   # Wait for full startup (check logs)
   ```

4. **UPLOAD new plugin**
   - Go to: Settings → Manage apps → Upload app
   - Upload: **ai-code-reviewer-COMPONENT-IMPORT-FIX.jar** (331 KB, Oct 21 08:29)
   - Wait for: 'Installed and ready to go'

5. **VERIFY fix works**
   - Go to: http://0.0.0.0:7990/plugins/servlet/ai-reviewer/admin
   - Expected: Configuration form loads without errors
   - Expected: Can see Ollama URL field and Save button
   - Check browser console: Should see successful GET request to `/rest/ai-reviewer/1.0/config`

## Why Previous Attempts Failed

### Attempt 1: Added no-arg constructor to HttpClientUtil
- ✅ This was needed and is correct
- ❌ But didn't solve the REST endpoint error
- **Reason:** The error was in ConfigResource, not HttpClientUtil

### Attempt 2: Verified component registration
- ✅ All components were properly registered
- ❌ But error persisted
- **Reason:** Components registered in Spring, but HK2 couldn't see them

### Attempt 3: Restarted Bitbucket multiple times
- ✅ Cleared plugin cache
- ❌ But error persisted
- **Reason:** The code itself was wrong, not a deployment issue

## Key Lessons

### When to use @ComponentImport

**Rule:** When a JAX-RS resource (@Path) needs to inject an Atlassian plugin service, ALWAYS use @ComponentImport:

```java
// ❌ WRONG - Will cause UnsatisfiedDependencyException
@Path("/myendpoint")
public class MyResource {
    @Inject
    public MyResource(MyPluginService service) { }  // Missing @ComponentImport!
}

// ✅ CORRECT - Works properly
@Path("/myendpoint")
public class MyResource {
    @Inject
    public MyResource(@ComponentImport MyPluginService service) { }  // Has @ComponentImport!
}
```

**When you DON'T need @ComponentImport:**
- Between @Named components in the same Spring container
- For Atlassian platform services (UserManager, ActiveObjects, etc.) - though it doesn't hurt to use it

### Understanding the Two Containers

| Aspect | Spring Container | HK2 Container |
|--------|-----------------|---------------|
| **Manages** | @Named components | JAX-RS resources (@Path) |
| **Registration** | META-INF/plugin-components/component | Auto-detected by Jersey |
| **Scanning** | atlassian-spring-scanner-maven-plugin | Jersey package scanning |
| **Injection** | @Inject between @Named components | @Inject in JAX-RS resources |
| **Bridge** | @ComponentImport tells HK2 to look in Spring | |

## Technical Details

### Why Both @Named and @Path on ConfigResource?

```java
@Path("/config")  // Tells Jersey: "This is a REST resource"
@Named            // Tells Spring: "This is a component (for exports)"
public class ConfigResource { }
```

- **@Path**: Required for Jersey to detect and manage the resource
- **@Named**: Optional, but useful for:
  - Registering in plugin-components/component
  - Making the resource injectable in other components (if needed)
  - Consistency with other plugin components

### How the Bridge Works

1. Jersey/HK2 creates instance of ConfigResource
2. HK2 sees @Inject constructor with parameters
3. For each parameter:
   - **Has @ComponentImport?** → Ask Spring container for this dependency
   - **No @ComponentImport?** → Look in HK2 registry only
4. If dependency not found → UnsatisfiedDependencyException

## Conclusion

This fix resolves the `UnsatisfiedDependencyException` by properly bridging the Jersey/HK2 and Atlassian Spring dependency injection containers.

**Critical Point:** The plugin JAR had all the right components and registrations. The problem was that the JAX-RS resource wasn't telling HK2 where to find those components.

**File to Deploy:** `ai-code-reviewer-COMPONENT-IMPORT-FIX.jar` (331 KB, Oct 21 08:29)

---

**Previous fixes included in this JAR:**
1. ✅ Admin UI base URL detection fix
2. ✅ HttpClientUtil @Named annotation
3. ✅ HttpClientUtil no-arg constructor for DI
4. ✅ ConfigResource @ComponentImport for AIReviewerConfigService (THIS FIX)
