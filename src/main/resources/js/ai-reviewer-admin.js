/**
 * AI Code Reviewer Admin Configuration JavaScript
 * Handles form submission, validation, and API interactions
 */

(function($) {
    'use strict';

    // Get base URL - try multiple methods
    var baseUrl = AJS.$('meta[name="application-base-url"]').attr("content") ||
                  AJS.$('meta[name="ajs-context-path"]').attr("content") ||
                  window.location.origin + (AJS.contextPath() || '');

    console.log('Base URL:', baseUrl);

    var apiUrl = baseUrl + '/rest/ai-reviewer/1.0/config';
    var userSearchUrl = apiUrl + '/users';
    var scopeTree = (window.AIReviewer && window.AIReviewer.ScopeTree) ? window.AIReviewer.ScopeTree : null;
    var profilePresets = {};
    var suppressProfileChange = false;
    var repositoryOverrides = [];
    var repoApiBase = baseUrl + '/rest/ai-reviewer/1.0/config/repositories';
    var repositoryCatalog = [];
    var catalogIndex = {
        projects: new Map(),
        repositories: new Map()
    };
    var totalRepositoryCount = 0;
    var treeInitialized = false;
    var scopeState = {
        mode: 'all',
        selectedRepositories: new Set(),
        previousSelection: new Set(),
        currentOverrides: new Map()
    };
    var suppressScopeEvents = false;
    var catalogCacheKey = 'aiReviewerRepoCatalog::v1';
    var catalogCacheTtlMs = 5 * 60 * 1000;
    var catalogFetchInFlight = null;
    var availableOllamaModels = [];

    /**
     * Initialize the admin configuration page
     */
    function init() {
        console.log('AI Reviewer Admin: Initializing...');

        // Bind event handlers
        $('#ai-reviewer-config-form').on('submit', handleFormSubmit);
        $('#test-connection-btn').on('click', testOllamaConnection);
        $('#reset-config-btn').on('click', resetToDefaults);
        $('#review-profile').on('change', handleProfileChange);
    $('#review-mode').on('change', updateImpactSummaryInlineAvailability);
        $('#ollama-url').on('change blur', handleOllamaUrlChanged);
        $('#ollama-model').on('change', handlePrimaryModelChanged);
        $('#auto-approve-apply-btn').on('click', applyAutoApproveToggle);
        $('#auto-approve').on('change', updateReviewerAccountAvailability);
        $('#repository-scope-tree').on('click', '.scope-node-toggle', handleNodeToggle);
        $('#repository-scope-tree').on('change', '.scope-checkbox', handleScopeCheckboxChange);
        $('#repository-overrides-body').on('click', '.override-toggle', handleOverrideToggle);
        $('#guardrails-scope-manage').on('click', focusScopeTreeSection);

        // Load current configuration
        loadConfiguration();

        console.log('AI Reviewer Admin: Initialized');
    }

    /**
     * Load current configuration from server
     */
    function loadConfiguration() {
        showLoading(true);

        $.ajax({
            url: apiUrl,
            type: 'GET',
            dataType: 'json',
            success: function(config) {
                console.log('Configuration loaded:', config);
                populateForm(config);
                showLoading(false);
            },
            error: function(xhr, status, error) {
                console.error('Failed to load configuration:', error);
                showMessage('error', 'Failed to load configuration: ' + error);
                showLoading(false);
            }
        });
    }

    /**
     * Populate form with configuration data
     */
    function populateForm(config) {
        profilePresets = buildProfilePresetMap(config.profilePresets);
        populateProfileOptions(config.profilePresets, config.reviewProfile);

        // Text fields
        $('#ollama-url').val(config.ollamaUrl || '');
        $('#ollama-model').attr('data-selected', config.ollamaModel || '');
        $('#fallback-model').attr('data-selected', config.fallbackModel || '');
        $('#rag-embedding-model').attr('data-selected', config.ragEmbeddingModel || '');
        $('#max-chars-per-chunk').val(config.maxCharsPerChunk || 60000);
        $('#max-files-per-chunk').val(config.maxFilesPerChunk || 3);
        $('#max-chunks').val(config.maxChunks || 20);
        $('#parallel-threads').val(config.parallelThreads || 4);
        $('#connect-timeout').val(config.connectTimeout || 10000);
        $('#read-timeout').val(config.readTimeout || 30000);
        $('#ollama-timeout').val(config.ollamaTimeout || 300000);
        $('#max-issues-per-file').val(config.maxIssuesPerFile || 50);
        $('#max-issue-comments').val(config.maxIssueComments || 30);
        $('#max-diff-size').val(config.maxDiffSize || 10000000);
        $('#overview-max-retries').val(config.overviewMaxRetries || config.maxRetries || 2);
        $('#overview-retry-delay').val(config.overviewRetryDelay || config.baseRetryDelay || 1500);
        $('#chunk-max-retries').val(config.chunkMaxRetries || config.maxRetries || 3);
        $('#chunk-retry-delay').val(config.chunkRetryDelay || config.baseRetryDelay || 1000);
        $('#api-delay').val(config.apiDelay || 100);
        $('#min-severity').val(config.minSeverity || 'medium');
        $('#require-approval-for').val(config.requireApprovalFor || 'critical,high');
        $('#review-extensions').val(config.reviewExtensions || '');
        $('#ignore-patterns').val(config.ignorePatterns || '');
        $('#ignore-paths').val(config.ignorePaths || '');
    $('#review-mode').val(config.reviewMode || 'standard');
    $('#impact-summary-inline').prop('checked', config.impactSummaryInline === true);
        $('#auto-approve').prop('checked', config.autoApprove === true);
        $('#worker-degradation-enabled').prop('checked', config.workerDegradationEnabled !== false);
    $('#verbose-mode').prop('checked', config.verboseMode === true);
    updateImpactSummaryInlineAvailability();

        // Checkboxes
        $('#enabled').prop('checked', config.enabled !== false);
        $('#review-draft-prs').prop('checked', config.reviewDraftPRs === true);
        $('#skip-generated-files').prop('checked', config.skipGeneratedFiles !== false);
        $('#skip-tests').prop('checked', config.skipTests === true);
        initializeReviewerUserSelect(config);
        updateReviewerAccountAvailability();

        updateProfileDetails($('#review-profile').val());

        repositoryOverrides = Array.isArray(config.repositoryOverrides) ? config.repositoryOverrides : [];
        initializeScopeStateFromOverrides((config.scopeMode || '').toLowerCase());
        renderOverridesTable();
        updateScopeSummary();
        updateOverridePanelVisibility();
        ensureRepositoryCatalog(false)
            .done(function() {
                refreshScopeUi();
            })
            .fail(function() {
                // Scope tree message already handled in ensureRepositoryCatalog.
            });

        loadOllamaModels();
    }

    function handleOllamaUrlChanged() {
        loadOllamaModels();
    }

    function handlePrimaryModelChanged() {
        renderModelDropdowns();
    }

    function loadOllamaModels() {
        var ollamaUrl = $('#ollama-url').val().trim();
        if (!ollamaUrl.length) {
            availableOllamaModels = [];
            renderModelDropdowns();
            return;
        }

        $.ajax({
            url: apiUrl + '/models',
            type: 'GET',
            dataType: 'json',
            data: { ollamaUrl: ollamaUrl },
            success: function(response) {
                availableOllamaModels = Array.isArray(response.models) ? response.models : [];
                renderModelDropdowns();
            },
            error: function(xhr) {
                availableOllamaModels = [];
                renderModelDropdowns();
                var errorText = (xhr.responseJSON && xhr.responseJSON.error) ? xhr.responseJSON.error : 'Failed to load Ollama model list';
                showMessage('warning', errorText + '. Existing selections preserved.');
            }
        });
    }

    function renderModelDropdowns() {
        var selectedPrimary = $('#ollama-model').val() || $('#ollama-model').attr('data-selected') || '';
        var selectedFallback = $('#fallback-model').val() || $('#fallback-model').attr('data-selected') || '';
        var selectedEmbedding = $('#rag-embedding-model').val() || $('#rag-embedding-model').attr('data-selected') || '';

        var allModelNames = availableOllamaModels.map(function(m) { return m.name; });
        var embeddingModelNames = availableOllamaModels
            .filter(function(m) { return m.embedding === true; })
            .map(function(m) { return m.name; });

        populateSelectOptions($('#ollama-model'), allModelNames, selectedPrimary, 'No models found', true);

        var fallbackNames = allModelNames.filter(function(name) {
            return name !== ($('#ollama-model').val() || selectedPrimary);
        });
        populateSelectOptions($('#fallback-model'), fallbackNames, selectedFallback, 'No fallback model', false);

        populateSelectOptions($('#rag-embedding-model'), embeddingModelNames, selectedEmbedding, 'No embedding model found', false);

        $('#ollama-model').attr('data-selected', $('#ollama-model').val() || selectedPrimary);
        $('#fallback-model').attr('data-selected', $('#fallback-model').val() || selectedFallback);
        $('#rag-embedding-model').attr('data-selected', $('#rag-embedding-model').val() || selectedEmbedding);
    }

    function populateSelectOptions($select, options, selectedValue, emptyLabel, keepUnknownSelection) {
        if (!$select || !$select.length) {
            return;
        }
        var values = Array.isArray(options) ? options.slice() : [];
        var selected = (selectedValue || '').trim();
        if (keepUnknownSelection !== false && selected.length && values.indexOf(selected) === -1) {
            values.unshift(selected);
        }

        $select.empty();

        if (!values.length) {
            $select.append($('<option>').attr('value', '').text(emptyLabel || 'No models available'));
            $select.val('');
            return;
        }

        values.forEach(function(value) {
            $select.append($('<option>').attr('value', value).text(value));
        });

        if (selected.length && values.indexOf(selected) !== -1) {
            $select.val(selected);
        } else {
            $select.val(values[0]);
        }
    }

    function buildProfilePresetMap(presets) {
        var map = {};
        if (Array.isArray(presets)) {
            presets.forEach(function(preset) {
                if (preset && preset.key) {
                    map[preset.key] = preset;
                }
            });
        }
        return map;
    }

    function populateProfileOptions(presets, selectedKey) {
        var $select = $('#review-profile');
        suppressProfileChange = true;
        $select.find('option').not('[value="custom"]').remove();

        if (Array.isArray(presets) && presets.length) {
            presets.forEach(function(preset) {
                if (!preset || !preset.key) {
                    return;
                }
                var $option = $('<option>')
                    .attr('value', preset.key)
                    .attr('data-preset', 'true')
                    .text(preset.name || preset.key);
                $select.append($option);
            });
        }

        var key = selectedKey;
        if (!key || key === '' || (key !== 'custom' && !profilePresets[key])) {
            if (key === 'custom') {
                // keep as custom if explicitly set
            } else if (Array.isArray(presets) && presets.length) {
                key = presets[0].key;
            } else {
                key = 'custom';
            }
        }
        $select.val(key);
        suppressProfileChange = false;
        updateProfileDetails(key);
    }

    var reviewerAccountState = null;

    function initializeReviewerUserSelect(config) {
        var $hidden = $('#ai-reviewer-user');
        if (!$hidden.length) {
            reviewerAccountState = null;
            return;
        }

        var state = reviewerAccountState || {};
        state.$hidden = $hidden;
        state.$search = $('#ai-reviewer-user-search');
        state.$results = $('#ai-reviewer-user-results');
        state.$card = $('#ai-reviewer-user-selected');
        state.$empty = state.$card.find('[data-role="empty"]');
        state.$details = state.$card.find('[data-role="details"]');
        state.$name = state.$card.find('[data-role="name"]');
        state.$username = state.$card.find('[data-role="username"]');
        state.$email = state.$card.find('[data-role="email"]');
        state.$initials = state.$card.find('[data-role="initials"]');
        state.$clear = $('#ai-reviewer-user-clear');
        state.latestUsers = state.latestUsers || [];
        state.pendingRequest = null;
        state.debounceTimer = null;

        state.selectedUser = {
            slug: config.aiReviewerUser || '',
            displayName: config.aiReviewerUserDisplayName || config.aiReviewerUser || '',
            name: '',
            email: ''
        };

        reviewerAccountState = state;

        function selectUser(user) {
            if (user && user.slug) {
                state.selectedUser = {
                    slug: user.slug,
                    displayName: user.displayName || user.name || user.slug,
                    name: user.name || '',
                    email: user.email || ''
                };
            } else {
                state.selectedUser = { slug: '', displayName: '', name: '', email: '' };
            }
            renderSelectedReviewer(state);
            if (state.$results.length) {
                renderReviewerSearchResults(state, {
                    users: state.latestUsers,
                    selectedSlug: state.selectedUser.slug,
                    onSelect: selectUser
                });
            }
        }

        function performSearch(term) {
            if (state.$results.length) {
                renderReviewerSearchResults(state, {
                    messageHtml: '<div class="user-search-status"><span class="aui-icon aui-icon-wait"></span> Searching…</div>'
                });
            }
            if (state.pendingRequest && state.pendingRequest.readyState && state.pendingRequest.readyState !== 4) {
                state.pendingRequest.abort();
            }
            state.pendingRequest = $.ajax({
                url: userSearchUrl,
                type: 'GET',
                dataType: 'json',
                data: {
                    q: term || '',
                    limit: 20
                }
            }).done(function(data) {
                state.latestUsers = Array.isArray(data.users) ? data.users : [];
                if (state.selectedUser.slug) {
                    var match = state.latestUsers.find(function(user) { return user.slug === state.selectedUser.slug; });
                    if (match) {
                        state.selectedUser.displayName = match.displayName || match.name || match.slug;
                        state.selectedUser.name = match.name || '';
                        state.selectedUser.email = match.email || '';
                        renderSelectedReviewer(state);
                    }
                }
                renderReviewerSearchResults(state, {
                    users: state.latestUsers,
                    selectedSlug: state.selectedUser.slug,
                    onSelect: selectUser
                });
            }).fail(function(xhr, status, error) {
                console.error('Failed to search users:', error || status);
                renderReviewerSearchResults(state, {
                    message: 'Failed to search users. Please try again.'
                });
            });
        }

        state.performSearch = performSearch;
        state.selectUser = selectUser;

        if (state.$clear.length) {
            state.$clear.off('.aiReviewer').on('click.aiReviewer', function(e) {
                e.preventDefault();
                selectUser(null);
            });
        }

        if (state.$search.length) {
            state.$search.off('.aiReviewer').on('input.aiReviewer', function() {
                var term = $(this).val();
                if (state.debounceTimer) {
                    clearTimeout(state.debounceTimer);
                }
                state.debounceTimer = setTimeout(function() {
                    performSearch(term);
                }, 250);
            });
        }

        renderSelectedReviewer(state);
        if (state.$results.length) {
            renderReviewerSearchResults(state, { message: 'Start typing to explore users.' });
        }
        performSearch('');
    }

    function renderReviewerSearchResults(state, options) {
        if (!state.$results || !state.$results.length) {
            return;
        }

        var opts = options || {};
        if (typeof opts === 'string') {
            opts = { message: opts };
        }

        if (opts.messageHtml) {
            state.$results.html(opts.messageHtml);
            return;
        }

        if (opts.message) {
            state.$results.html('<div class="user-search-status">' + escapeHtml(opts.message) + '</div>');
            return;
        }

        var users = opts.users || [];
        if (!users.length) {
            state.$results.html('<div class="user-search-empty">No matching users found.</div>');
            return;
        }

        var selectedSlug = typeof opts.selectedSlug !== 'undefined'
                ? opts.selectedSlug
                : (state.selectedUser ? state.selectedUser.slug : '');
        var onSelect = typeof opts.onSelect === 'function' ? opts.onSelect : function() {};

        var $list = $('<ul class="user-search-list"></ul>');
        users.forEach(function(user) {
            if (!user || !user.slug) {
                return;
            }
            var text = user.displayName || user.name || user.slug;
            var $item = $('<li class="user-search-item"></li>')
                .attr('data-slug', user.slug)
                .append($('<span class="user-name"></span>').text(text));

            if (user.email) {
                $item.append($('<span class="user-email"></span>').text(user.email));
            }
            if (user.name && user.name !== text) {
                $item.append($('<span class="user-username"></span>').text('@' + user.name));
            }
            if (selectedSlug && selectedSlug === user.slug) {
                $item.addClass('selected');
            }
            $item.on('click', function() {
                onSelect(user);
            });
            $list.append($item);
        });

        state.$results.empty().append($list);
        highlightSelectedReviewer(state, selectedSlug);
    }

    function highlightSelectedReviewer(state, slug) {
        if (!state.$results || !state.$results.length) {
            return;
        }
        state.$results.find('.user-search-item').removeClass('selected');
        if (!slug) {
            return;
        }
        state.$results.find('.user-search-item[data-slug="' + slug + '"]').addClass('selected');
    }

    function renderSelectedReviewer(state) {
        var user = state.selectedUser || { slug: '', displayName: '', name: '', email: '' };
        var hasUser = user.slug && user.slug.trim().length > 0;

        state.$hidden.val(hasUser ? user.slug : '');
        if (state.$search && state.$search.length) {
            state.$search.val(hasUser ? (user.displayName || user.slug) : '');
        }

        if (!state.$card.length) {
            return;
        }

        if (hasUser) {
            state.$empty.addClass('hidden');
            state.$details.removeClass('hidden');
            var display = user.displayName || user.name || user.slug;
            state.$name.text(display);
            if (user.name && user.name !== display) {
                state.$username.text('@' + user.name).show();
            } else {
                state.$username.text('').hide();
            }
            if (user.email) {
                state.$email.text(user.email).show();
            } else {
                state.$email.text('').hide();
            }
            state.$initials.text(computeInitials(display, user.slug));
        } else {
            state.$empty.removeClass('hidden');
            state.$details.addClass('hidden');
            state.$name.text('');
            state.$username.text('').hide();
            state.$email.text('').hide();
            state.$initials.text('AI');
        }
    }

    function computeInitials(value, fallback) {
        var source = (value || fallback || '').trim();
        if (!source) {
            return 'AI';
        }
        var parts = source.split(/\s+/).slice(0, 2);
        var initials = parts.map(function(part) {
            return part.charAt(0);
        }).join('');
        return initials.toUpperCase().substring(0, 2) || 'AI';
    }

    function updateReviewerAccountAvailability() {
        var autoApproveEnabled = $('#auto-approve').is(':checked');
        var $container = $('.ai-reviewer-account');
        if (!$container.length) {
            return;
        }
        if (autoApproveEnabled) {
            $container.removeClass('disabled');
        } else {
            $container.addClass('disabled');
        }
    }

    function handleProfileChange() {
        if (suppressProfileChange) {
            return;
        }
        var key = $('#review-profile').val();
        updateProfileDetails(key);
        if (key && key !== 'custom') {
            applyPresetDefaults(key);
            var preset = profilePresets[key];
            if (preset) {
                showMessage('info', 'Applied "' + (preset.name || key) + '" preset defaults. Review changes and save to persist.');
            }
        }
    }

    function applyPresetDefaults(key) {
        var preset = profilePresets[key];
        if (!preset || !preset.defaults) {
            return;
        }
        var defaults = preset.defaults;
        if (defaults.minSeverity) {
            $('#min-severity').val(defaults.minSeverity);
        }
        if (typeof defaults.requireApprovalFor !== 'undefined') {
            $('#require-approval-for').val(defaults.requireApprovalFor);
        }
        if (typeof defaults.skipGeneratedFiles === 'boolean') {
            $('#skip-generated-files').prop('checked', defaults.skipGeneratedFiles);
        }
        if (typeof defaults.skipTests === 'boolean') {
            $('#skip-tests').prop('checked', defaults.skipTests);
        }
        if (typeof defaults.maxIssuesPerFile !== 'undefined') {
            $('#max-issues-per-file').val(defaults.maxIssuesPerFile);
        }
        if (typeof defaults.autoApprove === 'boolean') {
            $('#auto-approve').prop('checked', defaults.autoApprove);
        }
    }

    function updateProfileDetails(key) {
        var descriptor = (key && key !== 'custom') ? profilePresets[key] : null;
        var $description = $('#profile-description-text');
        if (descriptor) {
            $description.text(descriptor.description || '');
        } else {
            $description.text('Select a profile to see recommended defaults, or choose Custom to control each setting manually.');
        }
        renderProfileDefaults(descriptor);
    }

    function renderProfileDefaults(descriptor) {
        var $list = $('#profile-defaults-list');
        $list.empty();

        if (!descriptor || !descriptor.defaults) {
            $list.append('<li>Custom profile: adjust thresholds and approvals below.</li>');
            return;
        }

        var defaults = descriptor.defaults;
        var items = [
            { label: 'Minimum severity', value: defaults.minSeverity },
            { label: 'Require approval for', value: defaults.requireApprovalFor },
            { label: 'Skip generated files', value: typeof defaults.skipGeneratedFiles === 'boolean' ? (defaults.skipGeneratedFiles ? 'Yes' : 'No') : '—' },
            { label: 'Skip tests', value: typeof defaults.skipTests === 'boolean' ? (defaults.skipTests ? 'Yes' : 'No') : '—' },
            { label: 'Max issues per file', value: defaults.maxIssuesPerFile },
            { label: 'Auto-approve clean runs', value: typeof defaults.autoApprove === 'boolean' ? (defaults.autoApprove ? 'Yes' : 'No') : '—' }
        ];

        items.forEach(function(item) {
            var text = item.value !== undefined && item.value !== null ? item.value : '—';
            $list.append('<li><strong>' + item.label + ':</strong> ' + text + '</li>');
        });
    }

    function ensureRepositoryCatalog(forceRefresh) {
        if (!forceRefresh && repositoryCatalog.length) {
            return $.Deferred().resolve(repositoryCatalog).promise();
        }

        if (!forceRefresh) {
            try {
                var cachedValue = sessionStorage.getItem(catalogCacheKey);
                if (cachedValue) {
                    var cachedPayload = JSON.parse(cachedValue);
                    if (cachedPayload && cachedPayload.projects && cachedPayload.fetchedAt &&
                            (Date.now() - cachedPayload.fetchedAt) < catalogCacheTtlMs) {
                        repositoryCatalog = Array.isArray(cachedPayload.projects) ? cachedPayload.projects : [];
                        buildCatalogIndex();
                        treeInitialized = false;
                        return $.Deferred().resolve(repositoryCatalog).promise();
                    }
                }
            } catch (e) {
                console.warn('Failed to parse cached repository catalogue:', e);
            }
        }

        if (catalogFetchInFlight) {
            return catalogFetchInFlight;
        }

        setScopeTreeMessage('<span class="aui-icon aui-icon-wait"></span> Loading repository catalogue…');

        var deferred = $.Deferred();
        catalogFetchInFlight = deferred.promise();

        var accumulated = [];
        var pageSize = 200;
        var totalExpected = null;

        function loadPage(start) {
            $.ajax({
                url: apiUrl + '/repository-catalog',
                type: 'GET',
                dataType: 'json',
                data: {
                    start: start,
                    limit: pageSize
                }
            }).done(function(data) {
                var projects = Array.isArray(data.projects) ? data.projects : [];
                if (totalExpected === null) {
                    totalExpected = typeof data.total === 'number' ? data.total : projects.length;
                }

                Array.prototype.push.apply(accumulated, projects);
                var fetchedCount = accumulated.length;
                var remaining = totalExpected != null ? Math.max(0, totalExpected - fetchedCount) : null;

                if (remaining === 0 || projects.length === 0) {
                    repositoryCatalog = accumulated;
                    buildCatalogIndex();
                    treeInitialized = false;
                    try {
                        sessionStorage.setItem(catalogCacheKey, JSON.stringify({
                            fetchedAt: Date.now(),
                            projects: repositoryCatalog
                        }));
                    } catch (e) {
                        console.warn('Failed to cache repository catalogue:', e);
                    }
                    setScopeTreeMessage('');
                    deferred.resolve(repositoryCatalog);
                    return;
                }

                var progressText = 'Loaded ' + fetchedCount + ' of ' + totalExpected + ' repositories. Still fetching…';
                setScopeTreeMessage('<span class="aui-icon aui-icon-wait"></span> ' + progressText);
                loadPage(start + projects.length);
            }).fail(function(xhr, status, error) {
                console.error('Failed to load repository catalog page:', error || status);
                displayScopeCatalogError('Failed to load repository catalogue: ' + (error || status));
                deferred.reject(error || status);
            });
        }

        loadPage(0);

        deferred.always(function() {
            catalogFetchInFlight = null;
        });

        return deferred.promise();
    }

    function setScopeTreeMessage(message) {
        var $container = $('#repository-scope-tree');
        if (!$container.length) {
            return;
        }
        if (scopeTree && typeof scopeTree.setMessage === 'function') {
            scopeTree.setMessage($container, message);
            return;
        }
        if (!message) {
            $container.empty();
            return;
        }
        $container.html('<div class="loading-message">' + message + '</div>');
    }

    function displayScopeCatalogError(message) {
        var safe = escapeHtml(message || 'Failed to load repository catalogue.');
        $('#repository-scope-tree').html('<div class="loading-message error">' + safe + '</div>');
        $('#scope-summary').text('Scope: Repository catalogue unavailable.');
    }

    function buildCatalogIndex() {
        if (scopeTree && typeof scopeTree.buildCatalogIndex === 'function') {
            var index = scopeTree.buildCatalogIndex(repositoryCatalog);
            catalogIndex.projects = index.projects;
            catalogIndex.repositories = index.repositories;
            totalRepositoryCount = index.totalRepositories;
            return;
        }
        catalogIndex.projects = new Map();
        catalogIndex.repositories = new Map();
        totalRepositoryCount = 0;
    }

    function initializeScopeStateFromOverrides(scopeMode) {
        scopeState.currentOverrides = new Map();
        scopeState.selectedRepositories = new Set();
        (Array.isArray(repositoryOverrides) ? repositoryOverrides : []).forEach(function(entry) {
            if (!entry || !entry.projectKey || !entry.repositorySlug) {
                return;
            }
            var key = buildRepoKey(entry.projectKey, entry.repositorySlug);
            scopeState.currentOverrides.set(key, entry);
            scopeState.selectedRepositories.add(key);
        });
        if (scopeMode === 'repositories') {
            scopeState.mode = 'repositories';
        } else if (scopeMode === 'all') {
            scopeState.mode = 'all';
        } else {
            scopeState.mode = scopeState.selectedRepositories.size ? 'repositories' : 'all';
        }
        scopeState.previousSelection = new Set(scopeState.selectedRepositories);
    }

    function buildRepoKey(projectKey, repositorySlug) {
        if (scopeTree && typeof scopeTree.buildRepoKey === 'function') {
            return scopeTree.buildRepoKey(projectKey, repositorySlug);
        }
        if (!projectKey || !repositorySlug) {
            return null;
        }
        return projectKey + '/' + repositorySlug;
    }

    function splitRepoKey(key) {
        if (scopeTree && typeof scopeTree.splitRepoKey === 'function') {
            return scopeTree.splitRepoKey(key);
        }
        if (!key || typeof key !== 'string') {
            return null;
        }
        var parts = key.split('/');
        if (parts.length !== 2) {
            return null;
        }
        return {
            projectKey: parts[0],
            repositorySlug: parts[1]
        };
    }

    function refreshScopeUi() {
        ensureScopeTree();
        applyScopeStateToTree();
        renderOverridesTable();
        updateScopeSummary();
        updateOverridePanelVisibility();
    }

    function ensureScopeTree() {
        if (treeInitialized) {
            return;
        }
        if (!repositoryCatalog.length) {
            setScopeTreeMessage('<em>No repositories available.</em>');
            treeInitialized = true;
            return;
        }
        renderRepositoryScopeTree();
        treeInitialized = true;
    }

    function focusScopeTreeSection(event) {
        if (event) {
            event.preventDefault();
        }
        var container = document.getElementById('scope-tree-container');
        if (!container) {
            return;
        }
        if (typeof container.scrollIntoView === 'function') {
            container.scrollIntoView({ behavior: 'smooth', block: 'start' });
        }
        container.classList.add('guardrails-scope-focus');
        window.setTimeout(function() {
            container.classList.remove('guardrails-scope-focus');
        }, 1600);
    }

    function renderRepositoryScopeTree() {
        var $container = $('#repository-scope-tree');
        if (!$container.length) {
            return;
        }
        if (!scopeTree || typeof scopeTree.render !== 'function') {
            displayScopeCatalogError('Failed to render repository scope tree: missing ScopeTree module.');
            return;
        }
        scopeTree.render($container, repositoryCatalog, {
            allLabel: 'All repositories (current and future)',
            projectGroupLabel: 'All project repositories',
            personalGroupLabel: 'All personal repositories'
        });
    }

    function applyScopeStateToTree() {
        var $tree = $('#repository-scope-tree');
        if (!$tree.length || !$tree.find('.scope-checkbox').length) {
            return;
        }
        if (!scopeTree || typeof scopeTree.applySelectionState !== 'function') {
            return;
        }

        suppressScopeEvents = true;
        scopeTree.applySelectionState($tree, scopeState);
        suppressScopeEvents = false;
    }

    function updateScopeSummary() {
        var $summary = $('#scope-summary');
        var mode = scopeState.mode === 'repositories' ? 'repositories' : 'all';
        var count = scopeState.selectedRepositories.size;
        var summaryText;

        if (mode === 'all') {
            summaryText = 'Scope: All repositories (current and future).';
        } else if (!count) {
            summaryText = 'Scope: No repositories selected. Save to remove existing overrides.';
        } else if (totalRepositoryCount > 0) {
            summaryText = 'Scope: ' + count + ' of ' + totalRepositoryCount + ' repositories selected.';
        } else {
            summaryText = 'Scope: ' + count + ' repositories selected.';
        }

        if ($summary.length) {
            $summary.text(summaryText);
        }

        updateScopeFeatureFlagCard({
            mode: mode,
            count: count,
            total: totalRepositoryCount
        });
    }

    function updateScopeFeatureFlagCard(details) {
        var $pill = $('#guardrails-scope-pill');
        var $summary = $('#guardrails-scope-summary');
        var $description = $('#guardrails-scope-description');

        if (!$pill.length || !$summary.length || !$description.length) {
            return;
        }

        var mode = (details && details.mode === 'repositories') ? 'repositories' : 'all';
        var count = details && typeof details.count === 'number' ? details.count : 0;
        var total = details && typeof details.total === 'number' ? details.total : 0;

        var pillClass = 'aui-lozenge guardrails-scope-pill';
        var pillText;
        var summaryText;
        var descriptionText;

        if (mode === 'all') {
            pillClass += ' aui-lozenge-success';
            pillText = 'Global';
            summaryText = 'Guardrails apply to every repository.';
            descriptionText = 'All existing and future repositories inherit these guardrails. Use the Scope section above if you need a smaller allow list.';
        } else if (count > 0) {
            pillClass += ' aui-lozenge-current';
            pillText = 'Targeted';
            var totalPortion = total > 0 ? (' of ' + total) : '';
            summaryText = count + totalPortion + ' repositories are explicitly covered.';
            descriptionText = 'Only the selected projects/repositories enforce guardrails. Save after adjusting the scope tree to update the allow list.';
        } else {
            pillClass += ' aui-lozenge-error';
            pillText = 'Targeted';
            summaryText = 'No repositories selected.';
            descriptionText = 'Guardrails are effectively disabled because the allow list is empty. Select repositories in the Scope section to re-enable coverage.';
        }

        $pill.attr('class', pillClass).text(pillText);
        $summary.text(summaryText);
        $description.text(descriptionText);
    }

    function updateOverridePanelVisibility() {
        var $panel = $('#repository-override-panel');
        if (!$panel.length) {
            return;
        }
        if (scopeState.mode === 'all' &&
            !scopeState.selectedRepositories.size &&
            !scopeState.currentOverrides.size) {
            $panel.addClass('hidden');
        } else {
            $panel.removeClass('hidden');
        }
    }

    function renderOverridesTable() {
        var $body = $('#repository-overrides-body');
        if (!$body.length) {
            return;
        }
        $body.empty();

        var unionKeys = new Set();
        scopeState.selectedRepositories.forEach(function(key) {
            unionKeys.add(key);
        });
        scopeState.currentOverrides.forEach(function(value, key) {
            unionKeys.add(key);
        });

        if (!unionKeys.size) {
            $body.append('<tr><td colspan="6"><em>No overrides configured.</em></td></tr>');
            return;
        }

        Array.from(unionKeys).sort(function(a, b) {
            return a.localeCompare(b);
        }).forEach(function(key) {
            var parts = splitRepoKey(key);
            if (!parts) {
                return;
            }
            var projectKey = parts.projectKey;
            var repositorySlug = parts.repositorySlug;
            var selectionActive = scopeState.selectedRepositories.has(key);
            var overrideEntry = scopeState.currentOverrides.get(key);

            var statusClass;
            var statusText;
            var inheritsGlobal = overrideEntry && overrideEntry.inheritGlobal === true;
            if (selectionActive && overrideEntry) {
                if (inheritsGlobal) {
                    statusText = 'Inherits global';
                    statusClass = 'status-inherit';
                } else {
                    statusText = 'Override active';
                    statusClass = 'status-active';
                }
            } else if (selectionActive && !overrideEntry) {
                statusText = 'Will add';
                statusClass = 'status-pending-add';
            } else if (!selectionActive && overrideEntry) {
                statusText = inheritsGlobal ? 'Will remove' : 'Remove override';
                statusClass = 'status-pending-remove';
            } else {
                return;
            }

            var catalogInfo = catalogIndex.repositories.get(key) || {};
            var projectLabel = catalogInfo.projectName ? (projectKey + ' · ' + catalogInfo.projectName) : projectKey;
            var repositoryLabel = catalogInfo.repositoryName || repositorySlug;
            if (!catalogInfo.repositoryName && overrideEntry) {
                repositoryLabel = repositorySlug + ' (missing)';
            }

            var modified = overrideEntry && overrideEntry.modifiedDate ? formatTimestamp(overrideEntry.modifiedDate) : '—';
            var modifiedBy = overrideEntry && overrideEntry.modifiedBy ? escapeHtml(overrideEntry.modifiedBy) : '—';

            var $row = $('<tr>');
            $row.append('<td>' + escapeHtml(projectLabel) + '</td>');
            $row.append('<td>' + escapeHtml(repositoryLabel) + '</td>');
            $row.append('<td class="status-column"><span class="status-badge ' + statusClass + '">' + statusText + '</span></td>');
            $row.append('<td>' + escapeHtml(modified) + '</td>');
            $row.append('<td>' + modifiedBy + '</td>');

            var $actions = $('<td class="actions-column"></td>');
            var buttonLabel = selectionActive ? 'Remove' : 'Restore';
            var $button = $('<button type="button" class="aui-button aui-button-link override-toggle"></button>')
                .text(buttonLabel)
                .data('projectKey', projectKey)
                .data('repositorySlug', repositorySlug);
            $actions.append($button);
            $row.append($actions);

            $body.append($row);
        });
    }

    function handleNodeToggle(event) {
        event.preventDefault();
        var $button = $(this);
        var $node = $button.closest('.scope-node');
        if (!$node.hasClass('scope-node-expandable')) {
            return;
        }
        var collapsed = $node.hasClass('scope-node-collapsed');
        $node.toggleClass('scope-node-collapsed', !collapsed);
        $button.attr('aria-expanded', collapsed ? 'true' : 'false');
    }

    function handleScopeCheckboxChange(event) {
        if (suppressScopeEvents) {
            return;
        }
        var $checkbox = $(this);
        var nodeType = $checkbox.data('nodeType');

        if (nodeType === 'all') {
            toggleAllScope($checkbox.is(':checked'));
            return;
        }

        if (scopeState.mode === 'all') {
            scopeState.mode = 'repositories';
            $('#scope-checkbox-all').prop('checked', false).prop('indeterminate', false);
        }

        if (nodeType === 'group') {
            toggleGroupSelection($checkbox);
        } else if (nodeType === 'project') {
            toggleProjectSelection($checkbox);
        } else if (nodeType === 'repository') {
            toggleRepositorySelection($checkbox);
        }

        refreshScopeUiAfterSelection();
    }

    function toggleAllScope(checked) {
        if (checked) {
            scopeState.previousSelection = new Set(scopeState.selectedRepositories);
            scopeState.selectedRepositories.clear();
            scopeState.mode = 'all';
        } else {
            scopeState.mode = 'repositories';
            scopeState.selectedRepositories = scopeState.previousSelection ?
                new Set(scopeState.previousSelection) : new Set();
        }
        refreshScopeUiAfterSelection();
    }

    function toggleGroupSelection($checkbox) {
        if (!scopeTree || typeof scopeTree.toggleGroupSelection !== 'function') {
            return;
        }
        scopeTree.toggleGroupSelection($('#repository-scope-tree'), scopeState, $checkbox);
    }

    function toggleProjectSelection($checkbox) {
        if (!scopeTree || typeof scopeTree.toggleProjectSelection !== 'function') {
            return;
        }
        scopeTree.toggleProjectSelection($('#repository-scope-tree'), scopeState, $checkbox);
    }

    function toggleRepositorySelection($checkbox) {
        if (!scopeTree || typeof scopeTree.toggleRepositorySelection !== 'function') {
            return;
        }
        scopeTree.toggleRepositorySelection(scopeState, $checkbox);
    }

    function refreshScopeUiAfterSelection() {
        if (scopeState.mode === 'repositories') {
            scopeState.previousSelection = new Set(scopeState.selectedRepositories);
        }
        applyScopeStateToTree();
        renderOverridesTable();
        updateScopeSummary();
        updateOverridePanelVisibility();
    }

    function handleOverrideToggle(event) {
        event.preventDefault();
        var $button = $(this);
        var projectKey = $button.data('projectKey');
        var repositorySlug = $button.data('repositorySlug');
        var key = buildRepoKey(projectKey, repositorySlug);
        if (!key) {
            return;
        }
        if (scopeState.mode === 'all') {
            scopeState.mode = 'repositories';
            $('#scope-checkbox-all').prop('checked', false).prop('indeterminate', false);
        }
        if (scopeState.selectedRepositories.has(key)) {
            scopeState.selectedRepositories.delete(key);
        } else {
            scopeState.selectedRepositories.add(key);
        }
        refreshScopeUiAfterSelection();
    }

    /**
     * Handle form submission
     */
    function handleFormSubmit(event) {
        event.preventDefault();

        if (!validateForm()) {
            showMessage('error', 'Please fix validation errors before saving');
            return;
        }

        var config = collectFormData();
        saveConfiguration(config);
    }

    /**
     * Validate form inputs
     */
    function validateForm() {
        var isValid = true;
        clearValidationErrors();

        // Validate required fields
        if (!$('#ollama-url').val().trim()) {
            showFieldError('ollama-url', 'Ollama URL is required');
            isValid = false;
        } else if (!isValidUrl($('#ollama-url').val())) {
            showFieldError('ollama-url', 'Invalid URL format');
            isValid = false;
        }

        if (!$('#ollama-model').val().trim()) {
            showFieldError('ollama-model', 'Primary model is required');
            isValid = false;
        }

        // Validate numeric fields
        if (!validateNumericField('max-chars-per-chunk', 10000, 100000)) isValid = false;
        if (!validateNumericField('max-files-per-chunk', 1, 10)) isValid = false;
        if (!validateNumericField('parallel-threads', 1, 16)) isValid = false;

        return isValid;
    }

    /**
     * Validate numeric field with range
     */
    function validateNumericField(fieldId, min, max) {
        var value = parseInt($('#' + fieldId).val());
        if (isNaN(value) || value < min || value > max) {
            showFieldError(fieldId, 'Must be between ' + min + ' and ' + max);
            return false;
        }
        return true;
    }

    /**
     * Check if string is valid URL
     */
    function isValidUrl(string) {
        try {
            var url = new URL(string);
            return url.protocol === 'http:' || url.protocol === 'https:';
        } catch (_) {
            return false;
        }
    }

    function normalizePromptText(text) {
        if (!text) {
            return '';
        }
        return text.trim();
    }

    /**
     * Collect form data into configuration object
     */
    function collectFormData() {
        var reviewerUser = $('#ai-reviewer-user').val();
        var overviewMaxRetries = parseInt($('#overview-max-retries').val());
        var overviewRetryDelay = parseInt($('#overview-retry-delay').val());
        var chunkMaxRetries = parseInt($('#chunk-max-retries').val());
        var chunkRetryDelay = parseInt($('#chunk-retry-delay').val());
        var primaryModel = $('#ollama-model').val().trim();
        var fallbackModel = $('#fallback-model').val().trim();
        if (fallbackModel === primaryModel) {
            fallbackModel = '';
        }
        var config = {
            ollamaUrl: $('#ollama-url').val().trim(),
            ollamaModel: primaryModel,
            fallbackModel: fallbackModel,
            ragEmbeddingModel: $('#rag-embedding-model').val().trim(),
            maxCharsPerChunk: parseInt($('#max-chars-per-chunk').val()),
            maxFilesPerChunk: parseInt($('#max-files-per-chunk').val()),
            maxChunks: parseInt($('#max-chunks').val()),
            parallelThreads: parseInt($('#parallel-threads').val()),
            connectTimeout: parseInt($('#connect-timeout').val()),
            readTimeout: parseInt($('#read-timeout').val()),
            ollamaTimeout: parseInt($('#ollama-timeout').val()),
            maxIssuesPerFile: parseInt($('#max-issues-per-file').val()),
            maxIssueComments: parseInt($('#max-issue-comments').val()),
            maxDiffSize: parseInt($('#max-diff-size').val()),
            overviewMaxRetries: overviewMaxRetries,
            overviewRetryDelay: overviewRetryDelay,
            chunkMaxRetries: chunkMaxRetries,
            chunkRetryDelay: chunkRetryDelay,
            maxRetries: chunkMaxRetries,
            baseRetryDelay: chunkRetryDelay,
            apiDelay: parseInt($('#api-delay').val()),
            minSeverity: $('#min-severity').val(),
            requireApprovalFor: $('#require-approval-for').val().trim(),
            reviewExtensions: $('#review-extensions').val().trim(),
            ignorePatterns: $('#ignore-patterns').val().trim(),
            ignorePaths: $('#ignore-paths').val().trim(),
            reviewProfile: $('#review-profile').val(),
            reviewMode: $('#review-mode').val(),
            impactSummaryInline: $('#impact-summary-inline').is(':checked'),
            enabled: $('#enabled').is(':checked'),
            reviewDraftPRs: $('#review-draft-prs').is(':checked'),
            skipGeneratedFiles: $('#skip-generated-files').is(':checked'),
            skipTests: $('#skip-tests').is(':checked'),
            autoApprove: $('#auto-approve').is(':checked'),
            workerDegradationEnabled: $('#worker-degradation-enabled').is(':checked'),
            verboseMode: $('#verbose-mode').is(':checked'),
            aiReviewerUser: reviewerUser && reviewerUser.length ? reviewerUser : null
        };

        var promptChunk = $('#prompt-chunk').val();
        var promptChunkDefault = $('#prompt-chunk-default').val() || '';
        if (promptChunk && normalizePromptText(promptChunk).length) {
            if (normalizePromptText(promptChunk) === normalizePromptText(promptChunkDefault)) {
                config['prompt.chunk'] = null;
            } else {
                config['prompt.chunk'] = promptChunk;
            }
        } else {
            config['prompt.chunk'] = null;
        }

        var promptSystemAppend = $('#prompt-system-append').val();
        config['prompt.system.append'] = normalizePromptText(promptSystemAppend).length
            ? promptSystemAppend
            : null;

        return config;
    }

    function synchronizeRepositoryScope() {
        var payload = {
            mode: scopeState.mode,
            repositories: Array.from(scopeState.selectedRepositories).map(function(key) {
                var parts = splitRepoKey(key);
                if (!parts) {
                    return null;
                }
                return {
                    projectKey: parts.projectKey,
                    repositorySlug: parts.repositorySlug
                };
            }).filter(Boolean)
        };

        $.ajax({
            url: apiUrl + '/scope',
            type: 'POST',
            contentType: 'application/json',
            data: JSON.stringify(payload),
            success: function(response) {
                repositoryOverrides = Array.isArray(response.repositoryOverrides) ? response.repositoryOverrides : [];
                initializeScopeStateFromOverrides(response.mode);
                refreshScopeUi();
                showMessage('success', 'Configuration saved successfully!');
                showLoading(false);
            },
            error: function(xhr, status, error) {
                console.error('Failed to synchronize repository scope:', error);
                var message = (xhr.responseJSON && xhr.responseJSON.error) || error || 'Unknown error';
                showMessage('error', 'Configuration saved but scope update failed: ' + message);
                showLoading(false);
                loadConfiguration();
            }
        });
    }

    /**
     * Save configuration to server
     */
    function saveConfiguration(config) {
        showLoading(true);

        $.ajax({
            url: apiUrl,
            type: 'PUT',
            contentType: 'application/json',
            data: JSON.stringify(config),
            success: function(response) {
                console.log('Configuration saved:', response);
                synchronizeRepositoryScope();
            },
            error: function(xhr, status, error) {
                console.error('Failed to save configuration:', error);
                var errorMsg = xhr.responseJSON && xhr.responseJSON.message ?
                    xhr.responseJSON.message : error;
                showMessage('error', 'Failed to save configuration: ' + errorMsg);
                showLoading(false);
            }
        });
    }

    /**
     * Test connection to Ollama
     */
    function testOllamaConnection() {
        var ollamaUrl = $('#ollama-url').val().trim();

        if (!ollamaUrl) {
            showMessage('error', 'Please enter Ollama URL first');
            return;
        }

        $('#test-connection-result').removeClass('success error').addClass('testing')
            .text('Testing connection...');

        $.ajax({
            url: apiUrl + '/test-connection',
            type: 'POST',
            contentType: 'application/json',
            data: JSON.stringify({ ollamaUrl: ollamaUrl }),
            success: function(response) {
                console.log('Connection test response:', response);
                var message = response.message || 'Connection successful!';
                $('#test-connection-result').removeClass('testing').addClass('success')
                    .text(message);
                setTimeout(function() {
                    $('#test-connection-result').fadeOut(function() {
                        $(this).text('').removeClass('success').show();
                    });
                }, 3000);
            },
            error: function(xhr, status, error) {
                console.error('Connection test failed:', xhr, status, error);
                var errorMsg = 'Connection failed';
                
                if (xhr.responseJSON && xhr.responseJSON.error) {
                    errorMsg = xhr.responseJSON.error;
                } else if (xhr.responseJSON && xhr.responseJSON.message) {
                    errorMsg = xhr.responseJSON.message;
                } else if (error) {
                    errorMsg = 'Connection failed: ' + error;
                }
                
                $('#test-connection-result').removeClass('testing').addClass('error')
                    .text(errorMsg);
            }
        });
    }

    /**
     * Reset form to default values
     */
    function resetToDefaults() {
        if (!confirm('Are you sure you want to reset all settings to defaults? This will overwrite your current configuration.')) {
            return;
        }

        var defaults = {
            ollamaUrl: 'http://10.152.98.37:11434',
            ollamaModel: 'qwen3-coder:30b',
            fallbackModel: 'qwen3-coder:7b',
            ragEmbeddingModel: '',
            maxCharsPerChunk: 60000,
            maxFilesPerChunk: 3,
            maxChunks: 20,
            parallelThreads: 4,
            connectTimeout: 10000,
            readTimeout: 30000,
            ollamaTimeout: 300000,
            maxIssuesPerFile: 50,
            maxIssueComments: 30,
            maxDiffSize: 10000000,
            overviewMaxRetries: 2,
            overviewRetryDelay: 1500,
            chunkMaxRetries: 3,
            chunkRetryDelay: 1000,
            maxRetries: 3,
            baseRetryDelay: 1000,
            apiDelay: 100,
            minSeverity: 'medium',
            requireApprovalFor: 'critical,high',
            reviewExtensions: 'java,groovy,js,ts,tsx,jsx,py,go,rs,cpp,c,cs,php,rb,kt,swift,scala',
            ignorePatterns: '*.min.js,*.generated.*,package-lock.json,yarn.lock,*.map',
            ignorePaths: 'node_modules/,vendor/,build/,dist/,.git/',
            reviewProfile: 'balanced',
            reviewMode: 'standard',
            impactSummaryInline: false,
            enabled: true,
            reviewDraftPRs: false,
            skipGeneratedFiles: true,
            skipTests: false,
            autoApprove: false,
            workerDegradationEnabled: true,
            verboseMode: false
        };

        defaults.profilePresets = Object.values(profilePresets);

        populateForm(defaults);
        showMessage('info', 'Form reset to default values. Click "Save Configuration" to apply.');
    }

    function updateImpactSummaryInlineAvailability() {
        var mode = ($('#review-mode').val() || '').toLowerCase();
        var enabled = mode === 'deep' || mode === 'full';
        var $checkbox = $('#impact-summary-inline');
        $checkbox.prop('disabled', !enabled);
        if (!enabled) {
            $checkbox.prop('checked', false);
        }
    }

    /**
     * Show message to user
     */
    function showMessage(type, message) {
        var $container = $('#aui-message-container');
        $container.empty();

        var messageClass = 'aui-message-' + type;
        var iconClass = type === 'error' ? 'error' :
                       type === 'success' ? 'success' :
                       type === 'warning' ? 'warning' : 'info';

        var $message = $('<div class="aui-message ' + messageClass + ' closeable">')
            .append('<p class="title"><span class="aui-icon icon-' + iconClass + '"></span><strong>' +
                    (type.charAt(0).toUpperCase() + type.slice(1)) + '</strong></p>')
            .append('<p>' + message + '</p>')
            .append('<span class="aui-icon icon-close" role="button" tabindex="0"></span>');

        $container.append($message);

        // Auto-dismiss success messages
        if (type === 'success') {
            setTimeout(function() {
                $message.fadeOut(function() {
                    $(this).remove();
                });
            }, 5000);
        }

        // Handle close button
        $message.find('.icon-close').on('click', function() {
            $message.fadeOut(function() {
                $(this).remove();
            });
        });

        // Scroll to message
        $('html, body').animate({
            scrollTop: $container.offset().top - 20
        }, 500);
    }

    /**
     * Show/hide loading indicator
     */
    function showLoading(show) {
        if (show) {
            $('#loading-indicator').addClass('active').show();
            $('#save-config-btn').prop('disabled', true);
        } else {
            $('#loading-indicator').removeClass('active').hide();
            $('#save-config-btn').prop('disabled', false);
        }
    }

    /**
     * Show field-specific validation error
     */
    function showFieldError(fieldId, message) {
        var $fieldGroup = $('#' + fieldId).closest('.field-group');
        $fieldGroup.addClass('error');

        var $errorMsg = $('<div class="error-message">' + message + '</div>');
        $fieldGroup.find('.description').after($errorMsg);
    }

    /**
     * Clear all validation errors
     */
    function clearValidationErrors() {
        $('.field-group').removeClass('error success');
        $('.error-message').remove();
    }

    function applyAutoApproveToggle() {
        var enabled = $('#auto-approve').is(':checked');
        var $status = $('#auto-approve-status');
        $status.removeClass('error success').text('Applying...');

        $.ajax({
            url: apiUrl + '/auto-approve',
            type: 'POST',
            contentType: 'application/json',
            data: JSON.stringify({ enabled: enabled }),
            success: function(response) {
                var message = response && response.message ? response.message : 'Auto-approve updated.';
                $status.addClass('success').text(message);
                setTimeout(function() {
                    $status.fadeOut(function() {
                        $(this).removeClass('success').text('').show();
                    });
                }, 4000);
            },
            error: function(xhr, status, error) {
                console.error('Failed to toggle auto-approve:', error);
                var message = 'Failed to update auto-approve';
                if (xhr.responseJSON && xhr.responseJSON.error) {
                    message = xhr.responseJSON.error;
                } else if (error) {
                    message += ': ' + error;
                }
                $status.addClass('error').text(message);
            }
        });
    }

    function escapeHtml(value) {
        if (value === undefined || value === null) {
            return '';
        }
        return String(value).replace(/[&<>"']/g, function(ch) {
            switch (ch) {
                case '&': return '&amp;';
                case '<': return '&lt;';
                case '>': return '&gt;';
                case '"': return '&quot;';
                case "'": return '&#39;';
                default: return ch;
            }
        });
    }

    function formatTimestamp(ms) {
        try {
            return new Date(ms).toLocaleString();
        } catch (e) {
            return '—';
        }
    }

    // Initialize when DOM is ready
    $(document).ready(function() {
        init();
    });

})(AJS.$);
