# Guava Dependency Removal - Fix Summary

## Problem

After implementing the configuration service (Phase 1), the plugin failed to install in Bitbucket Data Center 8.9.0 with the following error:

```
Package com.google.common.base is internal and is not available for export to plugin com.example.bitbucket.ai-code-reviewer
Unable to resolve com.example.bitbucket.ai-code-reviewer: missing requirement osgi.wiring.package;
(&(osgi.wiring.package=com.google.common.base)(version>=33.2.1.jre)(version<=33.2.1.jre))
```

**Root Cause:** The plugin imported Guava libraries with specific version constraints (33.2.1.jre), but Bitbucket's OSGi DMZ Resolver Hook prevents exporting internal packages with version constraints.

## Solution

Removed all Guava dependencies and replaced them with standard Java equivalents.

### Files Modified

#### 1. AIReviewerConfigServiceImpl.java

**Removed imports:**
```java
import com.google.common.collect.ImmutableMap;
import static com.google.common.base.Preconditions.checkNotNull;
```

**Added imports:**
```java
import java.util.Collections;
import java.util.Objects;
```

**Replaced code patterns:**

| Guava Pattern | Standard Java Replacement |
|--------------|---------------------------|
| `checkNotNull(obj, "message")` | `Objects.requireNonNull(obj, "message")` |
| `ImmutableMap.builder().put(k, v).build()` | `Collections.unmodifiableMap(new HashMap<>())` |

**Specific changes:**
- Line ~60: `this.ao = Objects.requireNonNull(ao, "activeObjects cannot be null");`
- Line ~280-310: Replaced ImmutableMap builder pattern with:
  ```java
  Map<String, Object> defaults = new HashMap<>();
  defaults.put("key1", value1);
  defaults.put("key2", value2);
  // ... more puts
  return Collections.unmodifiableMap(defaults);
  ```

#### 2. AdminConfigServlet.java

**Removed imports:**
```java
import com.google.common.collect.ImmutableMap;
```

**Note:** This file only had the import but didn't actually use ImmutableMap, so only the import removal was needed.

## Verification

### Build Success
```
[INFO] BUILD SUCCESS
[INFO] Compiling 6 source files
[INFO] Processed 4 annotated classes
```

### Manifest Verification
Checked the OSGi manifest in the generated JAR:
```bash
unzip -p target/ai-code-reviewer-1.0.0-SNAPSHOT.jar META-INF/MANIFEST.MF
```

**Import-Package section (no com.google.common):**
```
Import-Package: com.atlassian.activeobjects.external,
  com.atlassian.bitbucket.permission,
  com.atlassian.plugin.spring.scanner.annotation.export,
  com.atlassian.plugin.spring.scanner.annotation.imports,
  com.atlassian.sal.api.auth,
  com.atlassian.sal.api.transaction,
  com.atlassian.sal.api.user,
  com.atlassian.templaterenderer,
  javax.annotation,
  javax.inject,
  javax.servlet,
  javax.servlet.http,
  javax.ws.rs,
  javax.ws.rs.core,
  net.java.ao,
  net.java.ao.schema,
  org.slf4j
```

✅ No Guava dependencies in the manifest!

### Code Search Verification
```bash
grep -r "com.google.common" src/main/java/
```
Result: No matches found ✅

## Testing

### Next Steps
1. Install the rebuilt plugin (`target/ai-code-reviewer-1.0.0-SNAPSHOT.jar`) in Bitbucket Data Center
2. Verify all plugin modules enable successfully
3. Test the admin configuration UI at: `http://[bitbucket-url]/plugins/servlet/ai-reviewer/admin`
4. Test the REST API endpoints at: `http://[bitbucket-url]/rest/ai-reviewer/1.0/config`

### Expected Result
- Plugin should install without OSGi resolution errors
- All modules should be enabled
- Admin UI should be accessible to system administrators
- REST API endpoints should respond correctly

## Impact

**Files Changed:** 2
- `src/main/java/com/example/bitbucket/aireviewer/service/AIReviewerConfigServiceImpl.java`
- `src/main/java/com/example/bitbucket/aireviewer/servlet/AdminConfigServlet.java`

**Lines Changed:** ~10 lines total

**Dependencies Removed:**
- com.google.guava:guava (was causing OSGi conflicts)

**Dependencies Retained:**
- com.google.code.gson:gson-2.8.9 (bundled in plugin, not a provided dependency, so no conflicts)

## Lessons Learned

1. **Use Standard Java when possible:** Prefer `java.util.Collections` and `java.util.Objects` over third-party libraries like Guava for simple use cases
2. **OSGi Package Constraints:** Bitbucket's OSGi environment restricts internal packages with version constraints
3. **Provided vs Bundled Dependencies:** Provided dependencies (like Guava from Bitbucket's platform) can cause version conflicts when your plugin expects a specific version
4. **Manifest Inspection:** Always check the OSGi manifest (`Import-Package` section) when troubleshooting plugin installation issues

## Status

✅ **FIXED** - Plugin now builds successfully without Guava dependencies and should install correctly in Bitbucket Data Center.

---
**Date Fixed:** 2025-10-18
**Fixed By:** AI Assistant
**Build Version:** 1.0.0-SNAPSHOT
**Bitbucket Version:** 8.9.0
