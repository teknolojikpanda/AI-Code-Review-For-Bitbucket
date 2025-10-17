# AI Code Reviewer for Bitbucket Data Center

A comprehensive Bitbucket Data Center plugin that provides automated AI-powered code review using Ollama or compatible AI services.

## Features

- ✅ Automatic code review on Pull Request creation and updates
- ✅ Configurable review profiles with severity filtering
- ✅ Parallel chunk processing for large PRs
- ✅ Circuit breaker and rate limiting for API stability
- ✅ Comprehensive metrics and history tracking
- ✅ Administrative configuration UI
- ✅ Active Objects database persistence
- ✅ Support for multiple AI models with fallback
- ✅ File type filtering and ignore patterns
- ✅ Intelligent diff chunking
- ✅ Issue tracking across PR updates

## Requirements

- Bitbucket Data Center 8.9.0 or higher
- Java 8 or higher
- Maven 3.6 or higher
- Atlassian Plugin SDK (AMPS)
- Ollama or compatible AI service

## Project Structure

```
ai-code-reviewer/
├── pom.xml                                    # Maven project configuration
├── src/
│   ├── main/
│   │   ├── java/com/example/bitbucket/aireviewer/
│   │   │   ├── ao/                           # Active Objects entities
│   │   │   │   ├── AIReviewConfiguration.java
│   │   │   │   └── AIReviewHistory.java
│   │   │   ├── listener/                     # Event listeners
│   │   │   │   └── PullRequestAIReviewListener.java
│   │   │   ├── service/                      # Business logic services
│   │   │   │   ├── AIReviewerConfigService.java
│   │   │   │   ├── AIReviewerConfigServiceImpl.java
│   │   │   │   ├── AIReviewService.java
│   │   │   │   └── AIReviewServiceImpl.java
│   │   │   ├── rest/                         # REST API endpoints
│   │   │   │   ├── ConfigResource.java
│   │   │   │   └── HistoryResource.java
│   │   │   ├── servlet/                      # Admin UI servlet
│   │   │   │   └── AdminConfigServlet.java
│   │   │   └── util/                         # Utility classes
│   │   │       ├── CircuitBreaker.java
│   │   │       ├── RateLimiter.java
│   │   │       ├── MetricsCollector.java
│   │   │       └── DiffChunker.java
│   │   └── resources/
│   │       ├── atlassian-plugin.xml          # Plugin descriptor
│   │       ├── i18n/
│   │       │   └── ai-code-reviewer.properties
│   │       ├── templates/
│   │       │   └── admin-config.vm           # Velocity template for admin UI
│   │       ├── css/
│   │       │   └── ai-reviewer-admin.css
│   │       └── js/
│   │           └── ai-reviewer-admin.js
│   └── test/
│       └── java/com/example/bitbucket/aireviewer/
│           └── ...                           # Unit tests
└── README.md
```

## Building the Plugin

### Prerequisites

1. Install Atlassian Plugin SDK:
   ```bash
   # For macOS using Homebrew
   brew tap atlassian/tap
   brew install atlassian-plugin-sdk

   # For Linux/Windows, download from:
   # https://developer.atlassian.com/server/framework/atlassian-sdk/downloads/
   ```

2. Verify installation:
   ```bash
   atlas-version
   ```

### Build Commands

```bash
# Clean and compile
mvn clean compile

# Run tests
mvn test

# Package the plugin (creates target/*.jar)
mvn package

# Run Bitbucket locally for testing (includes hot-reloading)
atlas-run

# Run with debugging enabled
atlas-debug

# Install plugin to local Bitbucket instance
atlas-install-plugin
```

### Development Workflow

1. Start local Bitbucket instance:
   ```bash
   atlas-run
   ```

2. Access Bitbucket at `http://localhost:7990/bitbucket`
   - Default credentials: admin/admin

3. Make code changes and use QuickReload:
   ```bash
   atlas-mvn package
   # Then click "QuickReload" in Bitbucket admin plugins page
   ```

## Installation

### From Built JAR

1. Build the plugin:
   ```bash
   mvn clean package
   ```

2. The JAR file will be in `target/ai-code-reviewer-1.0.0-SNAPSHOT.jar`

3. Install in Bitbucket:
   - Go to **Administration** → **Manage apps**
   - Click **Upload app**
   - Upload the JAR file
   - Click **Upload**

### From Atlassian Marketplace (when published)

1. Go to **Administration** → **Find new apps**
2. Search for "AI Code Reviewer"
3. Click **Install**

## Configuration

### Initial Setup

1. Navigate to **Administration** → **AI Code Reviewer**

2. Configure Ollama connection:
   - **Ollama URL**: e.g., `http://your-ollama-server:11434`
   - **Primary Model**: e.g., `qwen3-coder:30b`
   - **Fallback Model**: e.g., `qwen3-coder:7b`

3. Configure review settings:
   - **Max Chars Per Chunk**: 60000 (recommended)
   - **Max Files Per Chunk**: 3
   - **Parallel Threads**: 4
   - **Min Severity**: Choose minimum severity level to report

4. Configure file filtering:
   - **Review Extensions**: Comma-separated list (e.g., `java,groovy,js,ts`)
   - **Ignore Patterns**: Patterns to skip (e.g., `*.min.js,*.generated.*`)
   - **Ignore Paths**: Paths to skip (e.g., `node_modules/,build/`)

5. Click **Save Configuration**

### Per-Repository Settings

Repository-specific settings can be configured via REST API or in future versions through the UI.

## Usage

Once configured, the plugin automatically:

1. **On PR Creation**: Analyzes the PR and posts a summary comment with findings
2. **On PR Update**: Re-analyzes changed files and tracks resolved/new issues
3. **Approval/Changes Required**: Automatically approves or requests changes based on severity

### Review Summary

The plugin posts a comprehensive summary including:
- Total issues by severity (Critical, High, Medium, Low)
- File-level change statistics
- Detailed issue descriptions with suggested fixes
- Analysis metrics

### Issue Comments

Each issue includes:
- Severity level and category
- File path and line number
- Problematic code snippet
- Detailed explanation
- Suggested fix

## REST API

### Configuration Endpoints

**Get Configuration**
```bash
GET /rest/ai-reviewer/1.0/config
```

**Update Configuration**
```bash
PUT /rest/ai-reviewer/1.0/config
Content-Type: application/json

{
  "ollamaUrl": "http://ollama:11434",
  "ollamaModel": "qwen3-coder:30b",
  "enabled": true,
  ...
}
```

**Test Connection**
```bash
POST /rest/ai-reviewer/1.0/config/test-connection
Content-Type: application/json

{
  "ollamaUrl": "http://ollama:11434"
}
```

### History Endpoints

**Get Review History**
```bash
GET /rest/ai-reviewer/1.0/history?limit=50&offset=0
```

**Get PR Review History**
```bash
GET /rest/ai-reviewer/1.0/history/pr/{prId}
```

**Get Repository Review History**
```bash
GET /rest/ai-reviewer/1.0/history/repository/{projectKey}/{repoSlug}?limit=50
```

## Troubleshooting

### Plugin Not Starting

1. Check Bitbucket logs: `<bitbucket-home>/log/atlassian-bitbucket.log`
2. Verify Active Objects tables were created:
   ```sql
   SELECT * FROM AO_*_AI_REVIEW_CONFIG;
   SELECT * FROM AO_*_AI_REVIEW_HISTORY;
   ```

### Reviews Not Triggering

1. Verify plugin is enabled in configuration
2. Check that PR is not a draft (unless configured to review drafts)
3. Check Bitbucket logs for errors
4. Verify Ollama is accessible from Bitbucket server

### Ollama Connection Issues

1. Test Ollama connectivity:
   ```bash
   curl http://your-ollama-server:11434/api/tags
   ```

2. Verify firewall rules allow Bitbucket → Ollama
3. Check Ollama is running: `docker ps` or `systemctl status ollama`

### Performance Issues

1. Reduce `parallelChunkThreads` if system is overloaded
2. Increase `maxCharsPerChunk` to reduce chunk count
3. Enable circuit breaker to protect Ollama
4. Check Ollama has sufficient resources (CPU/GPU/RAM)

## Advanced Configuration

### Environment Variables (for Docker/K8s deployments)

```properties
OLLAMA_URL=http://ollama:11434
OLLAMA_MODEL=qwen3-coder:30b
FALLBACK_MODEL=qwen3-coder:7b
REVIEW_CHUNK=60000
FILES_PER_CHUNK=3
REVIEWER_ENABLED=true
```

### Database Configuration

The plugin uses Active Objects (Bitbucket's ORM). Tables are automatically created and migrated.

### Logging Configuration

Add to `<bitbucket-home>/shared/bitbucket.properties`:
```properties
logging.logger.com.example.bitbucket.aireviewer=DEBUG
```

## Development

### Running Tests

```bash
# Run unit tests
mvn test

# Run integration tests
mvn integration-test

# Run with coverage
mvn clean test jacoco:report
```

### Adding Custom Review Rules

Extend `AIReviewServiceImpl` to add custom analysis logic:

```java
@Component
public class CustomReviewRules {
    public List<Issue> analyzeCode(String diff) {
        // Your custom logic
    }
}
```

### Customizing UI

1. Edit Velocity templates in `src/main/resources/templates/`
2. Modify CSS in `src/main/resources/css/`
3. Update JavaScript in `src/main/resources/js/`

## Migration from ScriptRunner

This plugin replaces the ScriptRunner Groovy script. To migrate:

1. Export your current ScriptRunner configuration
2. Install this plugin
3. Configure equivalent settings in the admin UI
4. Disable the ScriptRunner listener
5. Test with a new PR

## Contributing

Contributions are welcome! Please:

1. Fork the repository
2. Create a feature branch
3. Add tests for new functionality
4. Submit a pull request

## License

Copyright © 2025 Example Organization

## Support

- Issues: https://github.com/your-org/ai-code-reviewer/issues
- Documentation: https://github.com/your-org/ai-code-reviewer/wiki
- Email: support@example.com

## Changelog

### Version 1.0.0 (2025-01-17)
- Initial release
- Automated PR review with Ollama integration
- Admin configuration UI
- Active Objects persistence
- REST API for configuration and history
- Parallel chunk processing
- Circuit breaker and rate limiting
