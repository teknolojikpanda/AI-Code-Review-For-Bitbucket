# Multiline Commenting Support for Bitbucket 9.6.5

This document describes the multiline commenting feature implemented for the AI Code Reviewer plugin, compatible with Bitbucket Data Center 9.6.5.

## Overview

The AI Code Reviewer now supports both single-line and multiline comments using Bitbucket 9.6.5's enhanced commenting API. This allows the AI to create comments that span multiple lines of code when issues affect code blocks, method definitions, or other multi-line constructs.

## Key Features

### 1. LineNumberRange Support
- Uses `LineNumberRange` class for multiline comments
- Supports commenting on consecutive lines (e.g., lines 42-47)
- Backward compatible with single-line comments

### 2. CommentThreadDiffAnchor Enhancement
- Utilizes `CommentThreadDiffAnchor` with `LineNumberRange` parameter
- Supports `isMultilineAnchor()` method for validation
- Proper anchoring to diff sections

### 3. ReviewIssue Class Updates
- `lineStart` and `lineEnd` fields for line range specification
- `getLineRangeDisplay()` method shows "42-47" for multiline or "42" for single line
- Backward compatibility with deprecated `line` field

## Implementation Details

### Creating Multiline Comments

```java
// Single line comment
ReviewIssue singleLineIssue = ReviewIssue.builder()
    .path("src/main/java/Example.java")
    .lineStart(42)
    .severity(ReviewIssue.Severity.HIGH)
    .summary("SQL injection vulnerability")
    .build();

// Multiline comment
ReviewIssue multilineIssue = ReviewIssue.builder()
    .path("src/main/java/Example.java")
    .lineStart(42)
    .lineEnd(47)
    .severity(ReviewIssue.Severity.MEDIUM)
    .summary("Complex method needs refactoring")
    .build();
```

### Bitbucket API Integration

The `createMultilineCommentRequest()` method automatically detects whether to create single-line or multiline comments:

```java
private AddLineCommentRequest createMultilineCommentRequest(
        PullRequest pullRequest,
        String commentText,
        String filePath,
        ReviewIssue issue,
        CommentSeverity commentSeverity) {
    
    if (issue.getLineEnd() != null && !issue.getLineEnd().equals(issue.getLineStart())) {
        // Create multiline comment with LineNumberRange
        LineNumberRange lineRange = new LineNumberRange(issue.getLineStart(), issue.getLineEnd());
        CommentThreadDiffAnchor anchor = new CommentThreadDiffAnchor(
            CommentThreadDiffAnchorType.EFFECTIVE,
            filePath,
            DiffFileType.TO,
            lineRange,
            DiffSegmentType.ADDED
        );
    } else {
        // Create single line comment
        CommentThreadDiffAnchor anchor = new CommentThreadDiffAnchor(
            CommentThreadDiffAnchorType.EFFECTIVE,
            filePath,
            DiffFileType.TO,
            issue.getLineStart(),
            DiffSegmentType.ADDED
        );
    }
}
```

## AI Prompt Enhancement

The AI is now instructed to use multiline comments when appropriate:

- **Method/function definitions** spanning multiple lines
- **Complex expressions** or statements across multiple lines  
- **Code blocks** with related issues (try-catch, if-else blocks)
- **Class or interface definitions**

## Usage Examples

### When AI Detects Multiline Issues

The AI will automatically create multiline comments for:

1. **Security vulnerabilities** in multi-line code blocks
2. **Performance issues** in complex loops or algorithms
3. **Code quality issues** in large methods or classes
4. **Bug patterns** that span multiple lines

### Comment Display

- **Single line**: `L42 — security: SQL injection vulnerability`
- **Multiline**: `L42-47 (multiline) — code-quality: Complex method needs refactoring`

## Backward Compatibility

The implementation maintains full backward compatibility:

- Existing single-line comments continue to work
- Deprecated `line` field is still supported
- Falls back gracefully if multiline features are unavailable

## Configuration

No additional configuration is required. The feature automatically detects Bitbucket 9.6.5 capabilities and uses appropriate APIs.

## Logging and Monitoring

Enhanced logging provides visibility into multiline comment usage:

```
INFO: Starting to post 15 issue comments (12 single-line, 3 multiline) limited from 20 total issues
INFO: Created multiline comment anchor for lines 42-47 in src/main/java/Example.java
INFO: ✓ Posted multiline comment 3 at Example.java:42-47 with ID 12345 (severity: MEDIUM)
```

## Testing

Run the multiline comment tests:

```bash
mvn test -Dtest=MultilineCommentTest
```

## API Compatibility

- **Minimum Bitbucket version**: 9.6.5
- **Required APIs**: 
  - `LineNumberRange`
  - `CommentThreadDiffAnchor` with range support
  - `AddLineCommentRequest.Builder` with anchor parameter

## Troubleshooting

### Common Issues

1. **Comments not anchoring correctly**: Ensure line numbers exist in the diff
2. **Multiline comments appearing as single-line**: Check that `lineEnd` is properly set and different from `lineStart`
3. **API compatibility errors**: Verify Bitbucket version is 9.6.5 or higher

### Debug Logging

Enable debug logging to see multiline comment creation:

```
log.debug("Creating comment request for {}:{}-{}", filePath, lineStart, lineEnd);
```

## Future Enhancements

Potential future improvements:

- Support for non-consecutive line ranges
- Enhanced diff anchor types
- Integration with Bitbucket's suggestion API
- Batch multiline comment operations