(function($) {
    'use strict';

    var baseUrl = AJS.$('meta[name="application-base-url"]').attr('content') ||
        AJS.$('meta[name="ajs-context-path"]').attr('content') ||
        window.location.origin + (AJS.contextPath() || '');

    var $container = $('#ai-reviewer-repo-config');
    var projectKey = $container.data('project-key');
    var repositorySlug = $container.data('repository-slug');

    var apiUrl = baseUrl + '/rest/ai-reviewer/1.0/config/repositories/' +
        encodeURIComponent(projectKey) + '/' + encodeURIComponent(repositorySlug);

    var currentOverrides = {};

    function init() {
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
        $('#repo-prompt-append').val('');
        saveConfiguration(buildOverridesPayload());
    }

    function buildOverridesPayload() {
        var overrides = $.extend({}, currentOverrides || {});
        var appendText = $('#repo-prompt-append').val();
        if (appendText && appendText.trim().length) {
            overrides['prompt.chunk.append'] = appendText;
        } else {
            delete overrides['prompt.chunk.append'];
        }
        return overrides;
    }

    function loadConfiguration() {
        showLoading(true);
        $.ajax({
            url: apiUrl,
            type: 'GET',
            dataType: 'json',
            success: function(response) {
                currentOverrides = response && response.overrides ? response.overrides : {};
                $('#repo-prompt-append').val(currentOverrides['prompt.chunk.append'] || '');
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
            url: apiUrl,
            type: 'PUT',
            contentType: 'application/json',
            data: JSON.stringify(overrides || {}),
            success: function(response) {
                currentOverrides = response && response.overrides ? response.overrides : (overrides || {});
                $('#repo-prompt-append').val(currentOverrides['prompt.chunk.append'] || '');
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

    AJS.toInit(init);
})(AJS.$);
