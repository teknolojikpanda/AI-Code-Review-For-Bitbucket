# Build Fix - Multiple Dependency and Configuration Issues

## Problems Encountered

### Problem 1: Active Objects Dependency

When running `mvn clean compile`, the build was failing with:

```
Could not resolve dependencies for project com.example.bitbucket:ai-code-reviewer:
The following artifacts could not be resolved:
com.atlassian.activeobjects:activeobjects-plugin:jar:3.1.8 (absent)
```

**Root Cause:** The Active Objects dependency was incorrectly configured with wrong artifact ID and missing core API.

### Problem 2: Banned Dependencies

When running `mvn clean package`, the build was failing with:

```
Found Banned Dependency: org.apache.httpcomponents:httpclient:jar:4.5.13
Found Banned Dependency: org.apache.httpcomponents:httpcore:jar:4.4.13
```

**Root Cause:** Apache HTTP Client was not marked as `provided` scope. Bitbucket provides this library.

### Problem 3: Component Import Error

When running `mvn clean package`, the build was failing with:

```
atlassian-plugin.xml contains a definition of component-import.
This is not allowed when Atlassian-Plugin-Key is set.
```

**Root Cause:** When using Spring Scanner, component-import declarations in XML are not allowed. Component scanning is handled via annotations.

## Solutions

### Solution 1: Fixed Active Objects Dependencies

Added TWO Active Objects dependencies:

1. **com.atlassian.activeobjects:activeobjects-plugin** - The Atlassian wrapper plugin
2. **net.java.dev.activeobjects:activeobjects:0.9.2** - The core Active Objects API

**Updated pom.xml (lines 74-93):**

```xml
<!-- SAL API -->
<dependency>
    <groupId>com.atlassian.sal</groupId>
    <artifactId>sal-api</artifactId>
    <scope>provided</scope>
</dependency>

<!-- Active Objects for database persistence -->
<dependency>
    <groupId>com.atlassian.activeobjects</groupId>
    <artifactId>activeobjects-plugin</artifactId>
    <scope>provided</scope>
</dependency>

<dependency>
    <groupId>net.java.dev.activeobjects</groupId>
    <artifactId>activeobjects</artifactId>
    <version>0.9.2</version>
    <scope>provided</scope>
</dependency>
```

### Solution 2: Marked HTTP Client as Provided

Changed Apache HTTP Client dependency scope from `compile` to `provided`:

**Updated pom.xml (lines 141-146):**

```xml
<!-- HTTP Client for Ollama API calls -->
<dependency>
    <groupId>org.apache.httpcomponents</groupId>
    <artifactId>httpclient</artifactId>
    <scope>provided</scope>
</dependency>
```

### Solution 3: Removed Component Declarations from XML

Removed all `<component-import>` and `<component>` declarations from atlassian-plugin.xml. These are now handled automatically by Spring Scanner via Java annotations.

**Updated atlassian-plugin.xml:**

```xml
<!-- Active Objects Entity Definitions -->
<ao key="ao-module">
    <description>Active Objects module for AI Code Reviewer</description>
    <entity>com.example.bitbucket.aireviewer.ao.AIReviewConfiguration</entity>
    <entity>com.example.bitbucket.aireviewer.ao.AIReviewHistory</entity>
</ao>

<!--
    Component scanning is enabled via atlassian-spring-scanner-maven-plugin
    All @Component, @Named, and @Scanned classes will be automatically discovered
    Component imports are handled via @ComponentImport annotation in Java code
-->
```

## Verification

After all fixes, the build completes successfully:

```bash
$ mvn clean package -DskipTests

[INFO] Building jar: /home/cducak/Downloads/ai_code_review/target/ai-code-reviewer-1.0.0-SNAPSHOT.jar
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  4.733 s
[INFO] Finished at: 2025-10-17T15:40:45+03:00
[INFO] ------------------------------------------------------------------------
```

**Generated Artifact:**
- `target/ai-code-reviewer-1.0.0-SNAPSHOT.jar` (234 KB)

## What This Means

✅ **Build now works** - You can compile and package the project
✅ **Active Objects API available** - Entity classes compile correctly
✅ **JAR created** - Plugin is ready for installation
✅ **Ready for implementation** - Can start coding service layer
✅ **Can run atlas-run** - Ready for local development

## Next Steps

Now that the build works, you can:

1. **Run the full build:**
   ```bash
   mvn clean package
   ```

2. **Start local Bitbucket for development:**
   ```bash
   atlas-run
   ```

3. **Begin implementing the service layer** as described in IMPLEMENTATION_CHECKLIST.md

## Dependencies Resolved

The following Active Objects related JARs are now available:

- `activeobjects-plugin-5.2.0.jar` (1.7 MB)
- `activeobjects-dbex-5.2.0.jar` (106 KB)
- `activeobjects-0.9.2.jar` (360 KB)
- `lucene-core-2.2.0.jar` (539 KB) - dependency
- `lucene-queries-2.2.0.jar` (29 KB) - dependency

All dependencies are being pulled from:
- https://packages.atlassian.com/maven-external (Atlassian repository)
- https://repo.maven.apache.org/maven2 (Maven Central)

## Common Active Objects Issues

### Issue: Entity annotations not recognized
**Solution:** Ensured `net.java.dev.activeobjects:activeobjects` is included

### Issue: Wrong Active Objects version
**Solution:** Let bitbucket-parent manage the `activeobjects-plugin` version via dependencyManagement

### Issue: Missing SAL API
**Solution:** Added explicit `sal-api` dependency

## Reference

- Active Objects Documentation: https://developer.atlassian.com/server/framework/atlassian-sdk/active-objects/
- Bitbucket Plugin Development: https://developer.atlassian.com/server/bitbucket/reference/
- Maven Central activeobjects: https://central.sonatype.com/artifact/net.java.dev.activeobjects/activeobjects/0.9.2
