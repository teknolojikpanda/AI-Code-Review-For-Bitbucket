(function($) {
    'use strict';

    var baseUrl = AJS.$('meta[name="application-base-url"]').attr('content') ||
        AJS.$('meta[name="ajs-context-path"]').attr('content') ||
        window.location.origin + (AJS.contextPath() || '');

    var $container = $('#ai-reviewer-repo-config');
    var repositoryId = $container.data('repositoryId') || $container.data('repository-id');
    var projectKey = $container.data('projectKey') || $container.data('project-key') ||
        AJS.$('meta[name="projectKey"]').attr('content');
    var repositorySlug = $container.data('repositorySlug') || $container.data('repository-slug') ||
        AJS.$('meta[name="repositorySlug"]').attr('content');

    function resolveContextFromQuery() {
        var params = new URLSearchParams(window.location.search || '');
        var queryProjectKey = params.get('projectKey');
        var queryRepositorySlug = params.get('repositorySlug');
        var queryRepositoryId = params.get('repositoryId');
        if (!repositoryId && queryRepositoryId) {
            repositoryId = queryRepositoryId;
            $container.attr('data-repository-id', repositoryId);
        }
        if (!projectKey && queryProjectKey) {
            projectKey = queryProjectKey;
            $container.attr('data-project-key', projectKey);
        }
        if (!repositorySlug && queryRepositorySlug) {
            repositorySlug = queryRepositorySlug;
            $container.attr('data-repository-slug', repositorySlug);
        }
    }

    function buildApiUrl() {
        if (repositoryId) {
            return baseUrl + '/rest/ai-reviewer/1.0/config/repositories/id/' +
                encodeURIComponent(repositoryId);
        }
        return baseUrl + '/rest/ai-reviewer/1.0/config/repositories/' +
            encodeURIComponent(projectKey) + '/' + encodeURIComponent(repositorySlug);
    }

    var currentOverrides = {};

    function init() {
        resolveContextFromQuery();
        $('#ai-reviewer-repo-config-form').on('submit', handleSubmit);
        $('#reset-repo-prompt-btn').on('click', handleReset);
        loadConfiguration();
    }

    function handleSubmit(event) {
        event.preventDefault();
        var overrides = buildOverridesPayload();
        saveConfiguration(overrides);
    }

    function handleReset() {
        $('#repo-prompt-override').val('');
        $('#repo-prompt-append').val('');
        saveConfiguration(buildOverridesPayload());
    }

    function buildOverridesPayload() {
        var overrides = $.extend({}, currentOverrides || {});
        var overrideText = $('#repo-prompt-override').val();
        if (overrideText && overrideText.trim().length) {
            overrides['prompt.chunk'] = overrideText;
        } else {
            delete overrides['prompt.chunk'];
        }
        var appendText = $('#repo-prompt-append').val();
        if (appendText && appendText.trim().length) {
            overrides['prompt.chunk.append'] = appendText;
        } else {
            delete overrides['prompt.chunk.append'];
        }
        return overrides;
    }

    function loadConfiguration() {
        if (!repositoryId && (!projectKey || !repositorySlug)) {
            showMessage('error', 'Failed to load repository configuration: Missing repository context.');
            return;
        }
        showLoading(true);
        $.ajax({
            url: buildApiUrl(),
            type: 'GET',
            dataType: 'json',
            success: function(response) {
                currentOverrides = response && response.overrides ? response.overrides : {};
                $('#repo-prompt-override').val(currentOverrides['prompt.chunk'] || '');
                $('#repo-prompt-append').val(currentOverrides['prompt.chunk.append'] || '');
                updatePromptPreview(response || {});
                showLoading(false);
            },
            error: function(xhr, status, error) {
                var message = (xhr.responseJSON && xhr.responseJSON.error) || error || 'Unknown error';
                showMessage('error', 'Failed to load repository configuration: ' + message);
                showLoading(false);
            }
        });
    }

    function saveConfiguration(overrides) {
        showLoading(true);
        $.ajax({
            url: buildApiUrl(),
            type: 'PUT',
            contentType: 'application/json',
            data: JSON.stringify(overrides || {}),
            success: function(response) {
                currentOverrides = response && response.overrides ? response.overrides : (overrides || {});
                $('#repo-prompt-override').val(currentOverrides['prompt.chunk'] || '');
                $('#repo-prompt-append').val(currentOverrides['prompt.chunk.append'] || '');
                updatePromptPreview(response || {});
                showMessage('success', 'Repository prompt instructions saved.');
                showLoading(false);
            },
            error: function(xhr, status, error) {
                var message = (xhr.responseJSON && xhr.responseJSON.error) || error || 'Unknown error';
                showMessage('error', 'Failed to save repository prompt instructions: ' + message);
                showLoading(false);
            }
        });
    }

    function showLoading(isLoading) {
        $('#loading-indicator').toggle(!!isLoading);
        $('#save-repo-prompt-btn').prop('disabled', !!isLoading);
        $('#reset-repo-prompt-btn').prop('disabled', !!isLoading);
    }

    function showMessage(type, text) {
        var $container = $('#aui-message-container');
        $container.empty();
        if (!text) {
            return;
        }
        var $message = $('<div>')
            .addClass('aui-message')
            .addClass(type)
            .append($('<p>').text(text));
        $container.append($message);
    }

    function updatePromptPreview(response) {
        var defaults = response.defaults || {};
        var globalConfig = response.global || {};
        var overrides = response.overrides || {};
        var effective = response.effective || {};
        var defaultTemplate = $('#prompt-chunk-default').val() || defaults['prompt.chunk'] || '';
        var globalTemplate = globalConfig['prompt.chunk'];
        var repoTemplate = overrides['prompt.chunk'];
        var baseTemplate = repoTemplate || globalTemplate || defaultTemplate;

        var appendText = effective['prompt.chunk.append'] || '';
        var effectivePrompt = baseTemplate || '';
        if (appendText && appendText.trim().length) {
            effectivePrompt += (effectivePrompt.endsWith('\n') ? '' : '\n') + appendText;
        }
        $('#repo-prompt-effective').val(effectivePrompt);
        autoResizeAll();
    }

    function autoResizeAll() {
        ['#repo-prompt-effective', '#repo-prompt-override', '#repo-prompt-append']
            .forEach(function(selector) {
                autoResize($(selector));
            });
    }

    function autoResize($textarea) {
        if (!$textarea || !$textarea.length) {
            return;
        }
        $textarea.css('height', 'auto');
        $textarea.css('height', $textarea[0].scrollHeight + 12 + 'px');
    }

    AJS.toInit(init);
})(AJS.$);
