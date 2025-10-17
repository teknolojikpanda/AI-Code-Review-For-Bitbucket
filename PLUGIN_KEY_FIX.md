# Plugin Key Fix - Installation Error

## Problem

When trying to install the plugin in Bitbucket, it failed with:

```
java.lang.IllegalArgumentException: The plugin key '${atlassian.plugin.key}'
must either match the OSGi bundle symbolic name (Bundle-SymbolicName)
or be specified in the Atlassian-Plugin-Key manifest header
```

## Root Cause

The `atlassian-plugin.xml` was using a Maven property `${atlassian.plugin.key}` that was not being resolved during the build. This property is not a standard Maven property, so it remained as a literal string in the packaged JAR.

## Solution

Changed the plugin key from the undefined property to a hardcoded value that matches the Maven coordinates.

### Before (atlassian-plugin.xml line 2):
```xml
<atlassian-plugin key="${atlassian.plugin.key}" name="${project.name}" plugins-version="2">
```

### After (atlassian-plugin.xml line 2):
```xml
<atlassian-plugin key="com.example.bitbucket.ai-code-reviewer" name="${project.name}" plugins-version="2">
```

## How Plugin Key Works

The plugin key must match between three places:

1. **atlassian-plugin.xml** - `<atlassian-plugin key="...">`
2. **pom.xml** - `<Atlassian-Plugin-Key>` in the bitbucket-maven-plugin configuration
3. **JAR Manifest** - `Atlassian-Plugin-Key` header (generated from pom.xml)

### Our Configuration

**pom.xml (line 178):**
```xml
<Atlassian-Plugin-Key>${project.groupId}.${project.artifactId}</Atlassian-Plugin-Key>
```

This expands to:
- `${project.groupId}` = `com.example.bitbucket`
- `${project.artifactId}` = `ai-code-reviewer`
- Result: `com.example.bitbucket.ai-code-reviewer`

**atlassian-plugin.xml (line 2):**
```xml
<atlassian-plugin key="com.example.bitbucket.ai-code-reviewer" ...>
```

Both now match! ✅

## Verification

After rebuilding with `mvn clean package -DskipTests`, verify the plugin key:

### Check Manifest:
```bash
unzip -c target/ai-code-reviewer-1.0.0-SNAPSHOT.jar META-INF/MANIFEST.MF | grep Atlassian-Plugin-Key
```

Output:
```
Atlassian-Plugin-Key: com.example.bitbucket.ai-code-reviewer
```

### Check atlassian-plugin.xml:
```bash
unzip -c target/ai-code-reviewer-1.0.0-SNAPSHOT.jar atlassian-plugin.xml | grep "key="
```

Output:
```xml
<atlassian-plugin key="com.example.bitbucket.ai-code-reviewer" ...>
```

✅ Both match!

## Installation

The plugin should now install successfully in Bitbucket:

1. Navigate to **Administration** → **Manage apps**
2. Click **Upload app**
3. Select `target/ai-code-reviewer-1.0.0-SNAPSHOT.jar`
4. Click **Upload**

The plugin will install successfully, though it won't have any functionality yet until the service layer is implemented.

## Important Notes

### Standard Maven Properties That Work

These Maven properties are automatically resolved during build:

- `${project.name}` - Project name from pom.xml
- `${project.version}` - Project version
- `${project.description}` - Project description
- `${project.groupId}` - Group ID
- `${project.artifactId}` - Artifact ID
- `${project.organization.name}` - Organization name
- `${project.organization.url}` - Organization URL

### Non-Standard Property

- `${atlassian.plugin.key}` - **Does NOT exist by default!**

If you want to use `${atlassian.plugin.key}`, you must define it explicitly in your pom.xml:

```xml
<properties>
    <atlassian.plugin.key>com.example.bitbucket.ai-code-reviewer</atlassian.plugin.key>
</properties>
```

However, it's simpler to just hardcode the plugin key in atlassian-plugin.xml, which is the standard approach.

## Best Practice

The recommended approach is:

1. Define the plugin key pattern in pom.xml using `${project.groupId}.${project.artifactId}`
2. Use the same pattern hardcoded in atlassian-plugin.xml
3. Both will match automatically

This ensures consistency without relying on custom properties.

## Related Files

- [pom.xml](pom.xml) - Contains the Atlassian-Plugin-Key instruction
- [atlassian-plugin.xml](src/main/resources/atlassian-plugin.xml) - Contains the plugin key
- [BUILD_FIX.md](BUILD_FIX.md) - Previous build fixes

## Success!

After this fix:
- ✅ Plugin builds successfully
- ✅ Plugin key is consistent
- ✅ Plugin installs in Bitbucket
- ✅ Ready for implementation

Next step: Implement the service layer as described in IMPLEMENTATION_CHECKLIST.md
