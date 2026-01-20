(function($) {
    'use strict';

    var baseUrl = AJS.$('meta[name="application-base-url"]').attr('content') ||
        AJS.$('meta[name="ajs-context-path"]').attr('content') ||
        window.location.origin + (AJS.contextPath() || '');

    var apiUrl = baseUrl + '/rest/ai-reviewer/1.0/config';
    var effectiveEditorsReady = false;

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
            MarkupEditor.bindTo($chunkEditor);
            MarkupEditor.bindTo($systemEditor);
            if ($effectiveSystemEditor.length) {
                MarkupEditor.bindTo($effectiveSystemEditor);
                lockEffectivePreview($effectiveSystemEditor);
            }
            if ($effectiveChunkEditor.length) {
                MarkupEditor.bindTo($effectiveChunkEditor);
                lockEffectivePreview($effectiveChunkEditor);
            }
            effectiveEditorsReady = true;
            bindPreviewSpinnerFix($chunkEditor);
            bindPreviewSpinnerFix($systemEditor);
            bindPreviewSpinnerFix($effectiveSystemEditor);
            bindPreviewSpinnerFix($effectiveChunkEditor);
        });
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
        var defaultSystem = $('#prompt-system-default').val() || '';
        var defaultChunk = $('#prompt-chunk-default').val() || '';
        $('#prompt-chunk').val(config['prompt.chunk'] || '');
        var configSystemAppend = typeof config['prompt.system.append'] !== 'undefined'
            ? config['prompt.system.append']
            : config.promptSystemAppend;
        if (typeof configSystemAppend === 'undefined') {
            configSystemAppend = $('#prompt-system-append').val();
        }
        $('#prompt-system-append').val(configSystemAppend || '');

        var effectiveSystem = defaultSystem;
        var systemAppend = configSystemAppend;
        var placeholder = '{{ADDITIONAL_INSTRUCTIONS}}';
        if (systemAppend && systemAppend.trim().length) {
            var replacement = 'ADDITIONAL INSTRUCTIONS:\n' + systemAppend.trim();
            if (effectiveSystem.indexOf(placeholder) >= 0) {
                effectiveSystem = effectiveSystem.split(placeholder).join(replacement);
            } else {
                effectiveSystem += (effectiveSystem.endsWith('\n') ? '' : '\n') + replacement;
            }
        } else if (effectiveSystem.indexOf(placeholder) >= 0) {
            effectiveSystem = effectiveSystem.split(placeholder).join('').trim();
        }
        $('#prompt-system-effective').val(effectiveSystem);

        var effectiveChunk = config['prompt.chunk'] || defaultChunk;
        var chunkAppend = config['prompt.chunk.append'];
        if (chunkAppend && chunkAppend.trim().length) {
            effectiveChunk += (effectiveChunk.endsWith('\n') ? '' : '\n') + chunkAppend;
        }
        $('#prompt-chunk-effective').val(effectiveChunk);
        triggerEffectivePreviews();
        autoResizeAll();
    }

    function triggerEffectivePreviews() {
        if (!effectiveEditorsReady) {
            return;
        }
        triggerPreviewFor('#prompt-system-effective-editor');
        triggerPreviewFor('#prompt-chunk-effective-editor');
    }

    function triggerPreviewFor(selector) {
        var $editor = $(selector);
        if (!$editor.length || $editor.hasClass('previewing')) {
            return;
        }
        var $previewButton = $editor.find('.markup-preview-button');
        if ($previewButton.length) {
            $previewButton.trigger('click');
            stopPreviewSpinner($editor);
        }
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
            success: function() {
                showMessage('success', 'Prompt settings saved.');
                applyConfig(payload || {});
                showLoading(false);
            },
            error: function(xhr, status, error) {
                var message = (xhr.responseJSON && xhr.responseJSON.error) || error || 'Unknown error';
                showMessage('error', 'Failed to save prompt settings: ' + message);
                showLoading(false);
            }
        });
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
