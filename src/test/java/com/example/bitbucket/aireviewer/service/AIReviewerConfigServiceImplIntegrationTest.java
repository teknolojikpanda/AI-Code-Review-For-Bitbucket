package com.example.bitbucket.aireviewer.service;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.activeobjects.internal.EntityManagedActiveObjects;
import com.atlassian.activeobjects.internal.TransactionManager;
import com.atlassian.activeobjects.spi.DatabaseType;
import com.atlassian.sal.api.transaction.TransactionCallback;
import net.java.ao.EntityManager;
import net.java.ao.test.jdbc.H2Memory;
import net.java.ao.test.jdbc.Jdbc;
import net.java.ao.test.junit.ActiveObjectsJUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.example.bitbucket.aireviewer.ao.AIReviewConfiguration;
import com.example.bitbucket.aireviewer.ao.AIReviewRepoConfiguration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(ActiveObjectsJUnitRunner.class)
@Jdbc(H2Memory.class)
public class AIReviewerConfigServiceImplIntegrationTest {

    private EntityManager entityManager;
    private ActiveObjects activeObjects;
    private AIReviewerConfigServiceImpl service;

    @Before
    public void setUp() {
        activeObjects = new TestActiveObjects(entityManager);
        service = new AIReviewerConfigServiceImpl(activeObjects);
        activeObjects.migrate(AIReviewConfiguration.class, AIReviewRepoConfiguration.class);
    }

    @Test
    public void repositoryOverridesMergeWithGlobalConfiguration() {
        String projectKey = "PROJ";
        String repoSlug = "repo";

        Map<String, Object> defaults = service.getDefaultConfiguration();
        assertEquals(defaults.get("ollamaModel"), service.getEffectiveConfiguration(projectKey, repoSlug).get("ollamaModel"));

        Map<String, Object> overrides = new HashMap<>();
        overrides.put("ollamaModel", "custom-model");
        overrides.put("autoApprove", true);
        overrides.put("maxChunks", 5);
        service.updateRepositoryConfiguration(projectKey, repoSlug, overrides, "tester");

        Map<String, Object> repoConfig = service.getRepositoryConfiguration(projectKey, repoSlug);
        Map<String, Object> effective = service.getEffectiveConfiguration(projectKey, repoSlug);

        @SuppressWarnings("unchecked")
        Map<String, Object> repoOverrides = (Map<String, Object>) repoConfig.get("overrides");
        assertEquals("custom-model", repoOverrides.get("ollamaModel"));
        assertEquals("custom-model", effective.get("ollamaModel"));
        assertEquals(5, ((Number) effective.get("maxChunks")).intValue());
        assertTrue((Boolean) effective.get("autoApprove"));
        assertFalse((Boolean) repoConfig.get("inheritGlobal"));

        service.clearRepositoryConfiguration(projectKey, repoSlug);
        Map<String, Object> afterClear = service.getEffectiveConfiguration(projectKey, repoSlug);
        assertEquals(defaults.get("ollamaModel"), afterClear.get("ollamaModel"));
        assertFalse((Boolean) afterClear.get("autoApprove"));
    }

    @Test
    public void updateRepositoryConfigurationWithEmptyOverridesCreatesInheritanceRecord() {
        String projectKey = "PROJ";
        String repoSlug = "repo";

        // Initial call to toggle selection without overrides
        service.updateRepositoryConfiguration(projectKey, repoSlug, new HashMap<>(), "tester");

        Map<String, Object> config = service.getRepositoryConfiguration(projectKey, repoSlug);
        assertTrue((Boolean) config.get("inheritGlobal"));
        @SuppressWarnings("unchecked")
        Map<String, Object> overrides = (Map<String, Object>) config.get("overrides");
        assertTrue(overrides.isEmpty());
    }

    @Test
    public void updateRepositoryConfigurationStripsGlobalMatches() {
        String projectKey = "PROJ";
        String repoSlug = "repo";

        Map<String, Object> global = service.getConfigurationAsMap();
        Map<String, Object> overrides = new HashMap<>();
        overrides.put("ollamaModel", global.get("ollamaModel"));
        overrides.put("maxChunks", global.get("maxChunks"));
        overrides.put("autoApprove", global.get("autoApprove"));

        service.updateRepositoryConfiguration(projectKey, repoSlug, overrides, "tester");

        Map<String, Object> config = service.getRepositoryConfiguration(projectKey, repoSlug);
        assertTrue((Boolean) config.get("inheritGlobal"));
        @SuppressWarnings("unchecked")
        Map<String, Object> storedOverrides = (Map<String, Object>) config.get("overrides");
        assertTrue(storedOverrides.isEmpty());
    }

    @Test
    public void synchronizeRepositoryOverridesUpdatesTargets() {
        assertTrue(service.listRepositoryConfigurations().isEmpty());

        service.synchronizeRepositoryOverrides(
                Arrays.asList(new AIReviewerConfigService.RepositoryScope("PROJ", "repo_ONE")),
                "tester");

        Map<String, Object> firstOverride = service.listRepositoryConfigurations().get(0);
        assertEquals("PROJ", firstOverride.get("projectKey"));
        assertEquals("repo_ONE", firstOverride.get("repositorySlug"));
        assertEquals("tester", firstOverride.get("modifiedBy"));
        assertTrue((Boolean) firstOverride.get("inheritGlobal"));
        @SuppressWarnings("unchecked")
        Map<String, Object> firstOverrides = (Map<String, Object>) firstOverride.get("overrides");
        assertTrue(firstOverrides.isEmpty());

        service.synchronizeRepositoryOverrides(
                Arrays.asList(new AIReviewerConfigService.RepositoryScope("PROJ", "repo_TWO")),
                "tester");

        Map<String, Object> secondOverride = service.listRepositoryConfigurations().get(0);
        assertEquals("repo_TWO", secondOverride.get("repositorySlug"));
        assertEquals("PROJ", secondOverride.get("projectKey"));
        assertTrue((Boolean) secondOverride.get("inheritGlobal"));

        service.synchronizeRepositoryOverrides(Arrays.<AIReviewerConfigService.RepositoryScope>asList(), "tester");
        assertTrue(service.listRepositoryConfigurations().isEmpty());
    }

    private static final class TestActiveObjects extends EntityManagedActiveObjects {
        TestActiveObjects(EntityManager entityManager) {
            super(entityManager, new ImmediateTransactionManager(), DatabaseType.H2);
        }
    }

    private static final class ImmediateTransactionManager implements TransactionManager {
        @Override
        public <T> T doInTransaction(TransactionCallback<T> callback) {
            return callback.doInTransaction();
        }
    }
}
