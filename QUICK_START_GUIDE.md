# Quick Start Guide - AI Code Reviewer Plugin

## Immediate Next Steps

Your plugin structure is now set up! Here's what you need to do to complete the implementation:

## 1. Install Atlassian SDK

```bash
# macOS
brew tap atlassian/tap
brew install atlassian-plugin-sdk

# Verify
atlas-version
```

## 2. Complete Implementation Files

I've created the foundation. You now need to implement these remaining files:

### Priority 1: Core Service Layer

```
src/main/java/com/example/bitbucket/aireviewer/service/
├── AIReviewerConfigService.java          # Interface (create this)
├── AIReviewerConfigServiceImpl.java      # Implementation (create this)
├── AIReviewService.java                  # Interface (create this)
└── AIReviewServiceImpl.java              # Core logic from Groovy script (create this)
```

**AIReviewServiceImpl.java** - This is where you port the main logic from `pr_listener_script.groovy`

### Priority 2: Event Listener

```
src/main/java/com/example/bitbucket/aireviewer/listener/
└── PullRequestAIReviewListener.java      # Event handler (create this)
```

This replaces the ScriptRunner event handling.

### Priority 3: Utility Classes

```
src/main/java/com/example/bitbucket/aireviewer/util/
├── CircuitBreaker.java                    # Port from Groovy
├── RateLimiter.java                       # Port from Groovy
├── MetricsCollector.java                  # Port from Groovy
└── DiffChunker.java                       # Extract chunking logic
```

### Priority 4: REST API

```
src/main/java/com/example/bitbucket/aireviewer/rest/
├── ConfigResource.java                    # Configuration REST endpoints
└── HistoryResource.java                   # History REST endpoints
```

### Priority 5: Admin UI

```
src/main/java/com/example/bitbucket/aireviewer/servlet/
└── AdminConfigServlet.java                # Admin page servlet

src/main/resources/
├── templates/admin-config.vm              # Velocity template
├── css/ai-reviewer-admin.css              # Styles
└── js/ai-reviewer-admin.js                # JavaScript
```

### Priority 6: Internationalization

```
src/main/resources/i18n/
└── ai-code-reviewer.properties            # Translation strings
```

## 3. Build and Test

```bash
# Build the plugin
mvn clean package

# Run local Bitbucket instance
atlas-run

# Access at http://localhost:7990/bitbucket
# Default credentials: admin/admin
```

## 4. Key Conversion Tips

### From Groovy Script to Java Plugin

1. **@Field variables** → **Class fields with @Inject or configuration service**
2. **ScriptRunner @PluginModule** → **@Inject in constructor**
3. **event variable** → **@EventListener method parameter**
4. **Groovy closures** → **Java lambdas or anonymous classes**
5. **def keyword** → **Proper Java types**

### Example Conversion:

**Groovy (ScriptRunner):**
```groovy
@PluginModule PullRequestService prService
@Field String OLLAMA_URL = System.getenv('OLLAMA_URL') ?: 'http://...'

if (event instanceof PullRequestOpenedEvent) {
    def pr = event.pullRequest
    // ...
}
```

**Java (Plugin):**
```java
@Component
public class PullRequestAIReviewListener implements EventListener {
    private final PullRequestService prService;
    private final AIReviewerConfigService configService;

    @Inject
    public PullRequestAIReviewListener(
        PullRequestService prService,
        AIReviewerConfigService configService,
        EventPublisher eventPublisher) {
        this.prService = prService;
        this.configService = configService;
        eventPublisher.register(this);
    }

    @EventListener
    public void onPullRequestOpened(PullRequestOpenedEvent event) {
        PullRequest pr = event.getPullRequest();
        // ...
    }
}
```

## 5. Template Implementation Code

### AIReviewerConfigService.java (Interface)

```java
package com.example.bitbucket.aireviewer.service;

public interface AIReviewerConfigService {
    AIReviewConfiguration getGlobalConfiguration();
    void updateConfiguration(ConfigDTO config);
    boolean isEnabled();
    String getOllamaUrl();
    String getOllamaModel();
    // ... other getters
}
```

### AIReviewerConfigServiceImpl.java (Implementation Skeleton)

```java
package com.example.bitbucket.aireviewer.service.impl;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.plugin.spring.scanner.annotation.component.Scanned;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.example.bitbucket.aireviewer.ao.AIReviewConfiguration;
import com.example.bitbucket.aireviewer.service.AIReviewerConfigService;
import javax.inject.Inject;
import javax.inject.Named;

@Scanned
@Named
public class AIReviewerConfigServiceImpl implements AIReviewerConfigService {

    private final ActiveObjects ao;

    @Inject
    public AIReviewerConfigServiceImpl(@ComponentImport ActiveObjects ao) {
        this.ao = ao;
    }

    @Override
    public AIReviewConfiguration getGlobalConfiguration() {
        AIReviewConfiguration[] configs = ao.find(AIReviewConfiguration.class,
            "GLOBAL_DEFAULT = ?", true);

        if (configs.length > 0) {
            return configs[0];
        }

        // Create default config
        return createDefaultConfiguration();
    }

    private AIReviewConfiguration createDefaultConfiguration() {
        return ao.create(AIReviewConfiguration.class, params -> {
            params.setOllamaUrl("http://10.152.98.37:11434");
            params.setOllamaModel("qwen3-coder:30b");
            params.setFallbackModel("qwen3-coder:7b");
            params.setMaxCharsPerChunk(60000);
            params.setMaxFilesPerChunk(3);
            params.setParallelChunkThreads(4);
            params.setEnabled(true);
            params.setGlobalDefault(true);
            params.setCreatedDate(System.currentTimeMillis());
            params.setModifiedDate(System.currentTimeMillis());
        });
    }

    // Implement other methods...
}
```

### PullRequestAIReviewListener.java (Skeleton)

```java
package com.example.bitbucket.aireviewer.listener;

import com.atlassian.bitbucket.event.pull.*;
import com.atlassian.event.api.EventListener;
import com.atlassian.event.api.EventPublisher;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.example.bitbucket.aireviewer.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.inject.Inject;
import javax.inject.Named;

@Named
public class PullRequestAIReviewListener {

    private static final Logger log = LoggerFactory.getLogger(PullRequestAIReviewListener.class);

    private final AIReviewService reviewService;
    private final AIReviewerConfigService configService;

    @Inject
    public PullRequestAIReviewListener(
            AIReviewService reviewService,
            AIReviewerConfigService configService,
            @ComponentImport EventPublisher eventPublisher) {
        this.reviewService = reviewService;
        this.configService = configService;
        eventPublisher.register(this);
    }

    @EventListener
    public void onPullRequestOpened(PullRequestOpenedEvent event) {
        if (!configService.isEnabled()) {
            return;
        }

        log.info("PR #{} opened, triggering AI review", event.getPullRequest().getId());
        reviewService.reviewPullRequest(event.getPullRequest(), false);
    }

    @EventListener
    public void onPullRequestRescoped(PullRequestRescopedEvent event) {
        if (!configService.isEnabled()) {
            return;
        }

        log.info("PR #{} updated, re-triggering AI review", event.getPullRequest().getId());
        reviewService.reviewPullRequest(event.getPullRequest(), true);
    }
}
```

## 6. Testing Your Plugin

1. Start Bitbucket:
   ```bash
   atlas-run
   ```

2. Access http://localhost:7990/bitbucket

3. Login with admin/admin

4. Navigate to **Administration** → **Manage apps**

5. Verify "AI Code Reviewer" is installed and enabled

6. Navigate to **Administration** → **AI Code Reviewer** to configure

7. Create a test PR and verify the review runs

## 7. Debugging

Enable debug logging in `atlas-run`:

```bash
atlas-run --jvmargs "-Dplugin.debugging=true"
```

View logs in real-time:
```bash
tail -f target/bitbucket/home/log/atlassian-bitbucket.log
```

## 8. Common Issues and Solutions

### Issue: Plugin doesn't start
**Solution**: Check `atlassian-bitbucket.log` for errors. Usually related to missing dependencies or annotation errors.

### Issue: Active Objects tables not created
**Solution**: Ensure `<ao>` module is properly configured in atlassian-plugin.xml

### Issue: Event listener not firing
**Solution**: Verify EventPublisher.register() is called in constructor

### Issue: REST endpoints not accessible
**Solution**: Check `<rest>` configuration and ensure package scanning is correct

## 9. Next Steps After Basic Implementation

1. ✅ Implement core service layer
2. ✅ Port all Groovy logic to Java
3. ✅ Create admin UI
4. ✅ Add comprehensive tests
5. ✅ Performance tuning
6. ✅ Security review
7. ✅ Documentation
8. ✅ Package for distribution

## 10. Resources

- [Atlassian SDK Documentation](https://developer.atlassian.com/server/framework/atlassian-sdk/)
- [Bitbucket Plugin Guide](https://developer.atlassian.com/server/bitbucket/reference/)
- [Active Objects Guide](https://developer.atlassian.com/server/framework/atlassian-sdk/active-objects/)
- [REST API Development](https://developer.atlassian.com/server/framework/atlassian-sdk/rest-api-development/)

## Need Help?

The structure is in place. The key conversion work is porting the Groovy logic from `pr_listener_script.groovy` into the Java service classes, particularly `AIReviewServiceImpl.java`.

Focus on these three files first:
1. `AIReviewServiceImpl.java` - Main review logic
2. `AIReviewerConfigServiceImpl.java` - Configuration management
3. `PullRequestAIReviewListener.java` - Event handling

Good luck! 🚀
