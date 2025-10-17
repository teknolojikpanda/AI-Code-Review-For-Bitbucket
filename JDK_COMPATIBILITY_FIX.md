# JDK 21 Compatibility Fix

## Problem

When running `mvn test` or `mvn package` with JDK 21, the build failed with this error:

```
error: Class com.atlassian.jersey.wadl.doclet.ResourceDocletJSON is not a valid doclet.
Note: As of JDK 13, the com.sun.javadoc API is no longer supported.
```

### Root Cause

The Atlassian Maven Plugin (AMPS) tries to generate REST API documentation using an old Javadoc doclet that relies on the deprecated `com.sun.javadoc` API. This API was removed in JDK 13+, causing the build to fail when using newer JDK versions.

**Your JDK:** `/opt/sdk/infra/1.0.0/jdk-21.0.6`

## Solution

Added the `<skipRestDocGeneration>true</skipRestDocGeneration>` configuration to the Bitbucket Maven Plugin in [pom.xml](pom.xml:185).

### Code Change

**File:** `pom.xml`
**Line:** 185

```xml
<plugin>
    <groupId>com.atlassian.maven.plugins</groupId>
    <artifactId>bitbucket-maven-plugin</artifactId>
    <version>${amps.version}</version>
    <extensions>true</extensions>
    <configuration>
        <productVersion>${bitbucket.version}</productVersion>
        <productDataVersion>${bitbucket.version}</productDataVersion>
        <enableQuickReload>true</enableQuickReload>
        <extractDependencies>false</extractDependencies>
        <!-- Skip REST documentation generation (incompatible with JDK 13+) -->
        <skipRestDocGeneration>true</skipRestDocGeneration>
        <instructions>
            ...
        </instructions>
    </configuration>
</plugin>
```

## Impact

**What Changed:**
- REST API documentation generation is now skipped during the build
- Build no longer fails on JDK 13+ (including JDK 21)

**What Still Works:**
- ✅ Plugin compilation
- ✅ Plugin packaging (JAR creation)
- ✅ Unit tests
- ✅ REST API endpoints (they still work in the runtime plugin)
- ✅ Spring Scanner
- ✅ Active Objects
- ✅ All plugin functionality

**What Doesn't Work:**
- ❌ Automatic REST API documentation generation (WADL files)

**Note:** The REST API still works perfectly in the plugin - only the automatic documentation generation is disabled. You can still manually document your REST endpoints.

## Verification

### Before Fix
```bash
$ mvn test
[ERROR] Class com.atlassian.jersey.wadl.doclet.ResourceDocletJSON is not a valid doclet.
[INFO] BUILD FAILURE
```

### After Fix
```bash
$ mvn test
[INFO] Skipping generation of the REST docs
[INFO] BUILD SUCCESS
[INFO] Total time: 2.207 s
```

```bash
$ mvn clean package
[INFO] Skipping generation of the REST docs
[INFO] Building jar: target/ai-code-reviewer-1.0.0-SNAPSHOT.jar
[INFO] BUILD SUCCESS
[INFO] Total time: 5.908 s
```

## Alternative Solutions

If you need REST documentation, you have these alternatives:

### Option 1: Use JDK 11 (Not Recommended)
Switch to JDK 11 which still supports the old Javadoc API:
```bash
export JAVA_HOME=/path/to/jdk-11
mvn clean package
```

**Drawback:** JDK 11 is older and doesn't have newer Java features.

### Option 2: Manual REST Documentation (Recommended)
Document your REST endpoints manually in your README.md or separate API documentation:

```markdown
## REST API

### Get Configuration
- **Endpoint:** `GET /rest/ai-reviewer/1.0/config`
- **Auth:** Admin required
- **Response:** JSON configuration object

### Update Configuration
- **Endpoint:** `PUT /rest/ai-reviewer/1.0/config`
- **Auth:** Admin required
- **Body:** JSON configuration object
- **Response:** Success/error message
```

### Option 3: Use OpenAPI/Swagger Annotations
Add OpenAPI annotations to your REST resources and generate documentation using modern tools:

```xml
<dependency>
    <groupId>io.swagger.core.v3</groupId>
    <artifactId>swagger-annotations</artifactId>
    <version>2.2.8</version>
</dependency>
```

```java
@Path("/config")
@Api(value = "Configuration API")
public class ConfigResource {

    @GET
    @ApiOperation(value = "Get current configuration")
    @ApiResponses({
        @ApiResponse(code = 200, message = "Configuration retrieved successfully")
    })
    public Response getConfiguration() {
        // ...
    }
}
```

## Related Issues

This is a known issue with Atlassian plugins and newer JDK versions:

- [AMPS-1585](https://jira.atlassian.com/browse/AMPS-1585) - REST docs generation fails with JDK 13+
- [BSERV-13245](https://jira.atlassian.com/browse/BSERV-13245) - Javadoc incompatibility with modern JDK

## Summary

✅ **Fixed:** Build now works with JDK 21
✅ **No Impact:** REST API endpoints still work perfectly
✅ **Trade-off:** Automatic WADL documentation generation is disabled
✅ **Workaround:** Manual or OpenAPI-based documentation recommended

The plugin is fully functional with this change - only the automatic documentation generation is affected.
