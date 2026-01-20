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
    var effectivePreviewReady = false;
    var PROMPT_PLACEHOLDER = '{{ADDITIONAL_INSTRUCTIONS}}';
    var ADDITIONAL_HEADER = 'ADDITIONAL INSTRUCTIONS:\n';

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
        initMarkupEditors();
        $('#ai-reviewer-repo-config-form').on('submit', handleSubmit);
        $('#reset-repo-prompt-btn').on('click', handleReset);
        loadConfiguration();
    }

    function initMarkupEditors() {
        if (typeof require !== 'function') {
            return;
        }
        require(['bitbucket/internal/widget/markup-editor/markup-editor'], function(MarkupEditor) {
            if (!MarkupEditor || !MarkupEditor.bindTo) {
                return;
            }
            var $overrideEditor = $('#repo-prompt-override-editor');
            var $appendEditor = $('#repo-prompt-append-editor');
            var $effectiveEditor = $('#repo-prompt-effective-editor');

            setupMarkupEditor(MarkupEditor, $overrideEditor);
            setupMarkupEditor(MarkupEditor, $appendEditor);
            setupMarkupEditor(MarkupEditor, $effectiveEditor, { lockPreview: true });

            if ($effectiveEditor.length) {
                effectivePreviewReady = true;
            }
        });
    }

    function setupMarkupEditor(MarkupEditor, $editor, options) {
        if (!MarkupEditor || !$editor || !$editor.length) {
            return;
        }
        MarkupEditor.bindTo($editor);
        if (options && options.lockPreview) {
            lockEffectivePreview($editor);
        }
        bindPreviewSpinnerFix($editor);
    }

    function lockEffectivePreview($editor) {
        if (!$editor || !$editor.length) {
            return;
        }
        $editor.off('click', '.markup-preview');
        $editor.off('click', 'textarea');
        $editor.on('click', '.markup-preview, textarea', function(event) {
            event.preventDefault();
            event.stopImmediatePropagation();
        });
    }

    function bindPreviewSpinnerFix($editor) {
        if (!$editor || !$editor.length) {
            return;
        }
        $editor.on('click', '.markup-preview-button, .markup-preview', function() {
            stopSpinnerForEditor($editor);
        });
    }

    function stopSpinnerForEditor($editor) {
        if (!$editor || !$editor.length) {
            return;
        }
        var $target = $editor.find('textarea').parent();
        if ($target && $target.spinStop) {
            $target.spinStop();
        }
        setTimeout(function() {
            if ($target && $target.spinStop) {
                $target.spinStop();
            }
        }, 150);
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
        overrides['prompt.chunk'] = normalizeOverrideValue($('#repo-prompt-override').val());
        overrides['prompt.chunk.append'] = normalizeOverrideValue($('#repo-prompt-append').val());

        Object.keys(overrides).forEach(function(key) {
            if (overrides[key] === null) {
                delete overrides[key];
            }
        });
        return overrides;
    }

    function normalizeOverrideValue(text) {
        var trimmed = (text || '').trim();
        return trimmed.length ? text : null;
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
                applyConfigResponse(response);
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
                applyConfigResponse(response, overrides || {});
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

    function applyConfigResponse(response, fallbackOverrides) {
        response = response || {};
        var overrides = response.overrides || fallbackOverrides || {};
        currentOverrides = overrides;
        $('#repo-prompt-override').val(overrides['prompt.chunk'] || '');
        $('#repo-prompt-append').val(overrides['prompt.chunk.append'] || '');
        updatePromptPreview(response);
    }

    function updatePromptPreview(response) {
        var effectivePrompts = response.promptEffective || {};
        var effectivePrompt = effectivePrompts['prompt.chunk'];
        if (!effectivePrompt) {
            var defaults = response.defaults || {};
            var globalConfig = response.global || {};
            var overrides = response.overrides || currentOverrides || {};
            var effective = response.effective || {};
            var defaultTemplate = $('#prompt-chunk-default').val() || defaults['prompt.chunk'] || '';
            var globalTemplate = globalConfig['prompt.chunk'];
            var repoTemplate = overrides['prompt.chunk'];
            var baseTemplate = repoTemplate || globalTemplate || defaultTemplate;
            var appendText = effective['prompt.chunk.append'] || '';
            effectivePrompt = renderAdditionalInstructions(baseTemplate || '', appendText);
        }
        $('#repo-prompt-effective').val(effectivePrompt || '');
        triggerEffectivePreview();
        autoResizeAll();
    }

    function renderAdditionalInstructions(base, addition) {
        var trimmedAddition = (addition || '').trim();
        var hasAddition = trimmedAddition.length > 0;
        var hasPlaceholder = base.indexOf(PROMPT_PLACEHOLDER) >= 0;

        if (hasPlaceholder) {
            if (!hasAddition) {
                return base.split(PROMPT_PLACEHOLDER).join('').trim();
            }
            return base.split(PROMPT_PLACEHOLDER).join(ADDITIONAL_HEADER + trimmedAddition).trim();
        }

        if (!hasAddition) {
            return base;
        }

        return base + (base.endsWith('\n') ? '' : '\n') + ADDITIONAL_HEADER + trimmedAddition;
    }

    function triggerEffectivePreview() {
        if (!effectivePreviewReady) {
            return;
        }
        var $editor = $('#repo-prompt-effective-editor');
        if (!$editor.length) {
            return;
        }
        var $previewButton = $editor.find('.markup-preview-button');
        if (!$previewButton.length) {
            return;
        }
        if ($editor.hasClass('previewing')) {
            $previewButton.trigger('click');
            setTimeout(function() {
                $previewButton.trigger('click');
                stopSpinnerForEditor($editor);
            }, 0);
            return;
        }
        $previewButton.trigger('click');
        stopSpinnerForEditor($editor);
    }

    function autoResizeAll() {
        ['#repo-prompt-effective']
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
