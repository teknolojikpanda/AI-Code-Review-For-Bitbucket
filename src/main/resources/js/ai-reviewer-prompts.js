(function($) {
    'use strict';

    var baseUrl = AJS.$('meta[name="application-base-url"]').attr('content') ||
        AJS.$('meta[name="ajs-context-path"]').attr('content') ||
        window.location.origin + (AJS.contextPath() || '');

    var apiUrl = baseUrl + '/rest/ai-reviewer/1.0/config';
    var effectiveEditorsReady = false;
    var pendingEffectivePreview = false;
    var PROMPT_PLACEHOLDER = '{{ADDITIONAL_INSTRUCTIONS}}';
    var ADDITIONAL_HEADER = 'ADDITIONAL INSTRUCTIONS:\n';

    function init() {
        initMarkupEditors();
        $('#ai-reviewer-prompts-form').on('submit', handleSubmit);
        $('#reset-prompts-btn').on('click', handleReset);
        refreshPrompts();
    }

    function initMarkupEditors() {
        if (typeof require !== 'function') {
            return;
        }
        require(['bitbucket/internal/widget/markup-editor/markup-editor'], function(MarkupEditor) {
            if (!MarkupEditor || !MarkupEditor.bindTo) {
                return;
            }
            var $chunkEditor = $('#prompt-chunk-editor');
            var $systemEditor = $('#prompt-system-append-editor');
            var $effectiveSystemEditor = $('#prompt-system-effective-editor');
            var $effectiveChunkEditor = $('#prompt-chunk-effective-editor');

            setupMarkupEditor(MarkupEditor, $chunkEditor);
            setupMarkupEditor(MarkupEditor, $systemEditor);
            setupMarkupEditor(MarkupEditor, $effectiveSystemEditor, { lockPreview: true });
            setupMarkupEditor(MarkupEditor, $effectiveChunkEditor, { lockPreview: true });

            effectiveEditorsReady = true;
            if (pendingEffectivePreview) {
                triggerEffectivePreviews();
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
            var $target = $editor.find('textarea').parent();
            if ($target && $target.spinStop) {
                $target.spinStop();
            }
        });
    }

    function handleSubmit(event) {
        event.preventDefault();
        savePrompts();
    }

    function handleReset() {
        $('#prompt-chunk').val('');
        $('#prompt-system-append').val('');
        savePrompts();
    }

    function refreshPrompts() {
        showLoading(true);
        $.ajax({
            url: apiUrl,
            type: 'GET',
            dataType: 'json',
            success: function(config) {
                applyConfig(config || {});
                showLoading(false);
            },
            error: function(xhr, status, error) {
                var message = (xhr.responseJSON && xhr.responseJSON.error) || error || 'Unknown error';
                showMessage('error', 'Failed to load prompt configuration: ' + message);
                showLoading(false);
            }
        });
    }

    function applyConfig(config) {
        var defaults = {
            system: $('#prompt-system-default').val() || '',
            chunk: $('#prompt-chunk-default').val() || ''
        };
        var currentValues = {
            systemAppend: $('#prompt-system-append').val()
        };
        var effectiveFromServer = config.promptEffective || {};
        var result = computeEffectivePrompts(config, defaults, currentValues, effectiveFromServer);

        $('#prompt-chunk').val(config['prompt.chunk'] || '');
        $('#prompt-system-append').val(result.systemAppend);
        $('#prompt-system-effective').val(result.effectiveSystem);
        $('#prompt-chunk-effective').val(result.effectiveChunk);
        triggerEffectivePreviews();
        autoResizeAll();
    }

    function computeEffectivePrompts(config, defaults, currentValues, effectiveFromServer) {
        var systemAppend = resolveSystemAppend(config, currentValues);
        var effectiveSystem = effectiveFromServer['prompt.system'] ||
            renderAdditionalInstructions(defaults.system || '', systemAppend);

        var baseChunk = config['prompt.chunk'] || defaults.chunk || '';
        var chunkAppend = config['prompt.chunk.append'];
        var effectiveChunk = effectiveFromServer['prompt.chunk'] ||
            renderAdditionalInstructions(baseChunk, chunkAppend);

        return {
            systemAppend: systemAppend || '',
            effectiveSystem: effectiveSystem,
            effectiveChunk: effectiveChunk
        };
    }

    function resolveSystemAppend(config, currentValues) {
        if (typeof config['prompt.system.append'] !== 'undefined') {
            return config['prompt.system.append'];
        }
        if (typeof config.promptSystemAppend !== 'undefined') {
            return config.promptSystemAppend;
        }
        return (currentValues && currentValues.systemAppend) ? currentValues.systemAppend : '';
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

    function triggerEffectivePreviews() {
        if (!effectiveEditorsReady) {
            pendingEffectivePreview = true;
            return;
        }
        pendingEffectivePreview = false;
        triggerPreviewFor('#prompt-system-effective-editor');
        triggerPreviewFor('#prompt-chunk-effective-editor');
    }

    function triggerPreviewFor(selector) {
        var $editor = $(selector);
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
                stopPreviewSpinner($editor);
            }, 0);
            return;
        }
        $previewButton.trigger('click');
        stopPreviewSpinner($editor);
    }

    function stopPreviewSpinner($editor) {
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

    function savePrompts() {
        var chunkValue = $('#prompt-chunk').val();
        var systemAppendValue = $('#prompt-system-append').val();
        var payload = {
            'prompt.chunk': chunkValue && chunkValue.trim().length ? chunkValue : null,
            'prompt.system.append': systemAppendValue && systemAppendValue.trim().length ? systemAppendValue : null
        };
        showLoading(true);
        $.ajax({
            url: apiUrl,
            type: 'PUT',
            contentType: 'application/json',
            data: JSON.stringify(payload),
            success: handleSuccessfulSave,
            error: function(xhr, status, error) {
                var message = (xhr.responseJSON && xhr.responseJSON.error) || error || 'Unknown error';
                showMessage('error', 'Failed to save prompt settings: ' + message);
                showLoading(false);
            }
        });
    }

    function handleSuccessfulSave() {
        showMessage('success', 'Prompt settings saved.');
        refreshPrompts();
    }

    function showLoading(show) {
        $('#loading-indicator').toggle(!!show);
        $('#save-prompts-btn').prop('disabled', !!show);
        $('#reset-prompts-btn').prop('disabled', !!show);
    }

    function showMessage(type, message) {
        var $container = $('#aui-message-container');
        $container.empty();
        if (!message) {
            return;
        }
        var messageClass = 'aui-message-' + type;
        var iconClass = type === 'error' ? 'error' : type === 'success' ? 'success' : 'info';
        var $message = $('<div class="aui-message ' + messageClass + ' closeable">')
            .append('<p class="title"><span class="aui-icon icon-' + iconClass + '"></span><strong>' +
                    (type.charAt(0).toUpperCase() + type.slice(1)) + '</strong></p>')
            .append($('<p></p>').text(message))
            .append('<span class="aui-icon icon-close" role="button" tabindex="0"></span>');
        $container.append($message);
        $message.find('.icon-close').on('click', function() {
            $message.fadeOut(function() {
                $(this).remove();
            });
        });
    }

    function autoResizeAll() {
        ['#prompt-system-effective', '#prompt-chunk-effective']
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
