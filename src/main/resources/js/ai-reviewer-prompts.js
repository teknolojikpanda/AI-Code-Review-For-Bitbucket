(function($) {
    'use strict';

    var baseUrl = AJS.$('meta[name="application-base-url"]').attr('content') ||
        AJS.$('meta[name="ajs-context-path"]').attr('content') ||
        window.location.origin + (AJS.contextPath() || '');

    var apiUrl = baseUrl + '/rest/ai-reviewer/1.0/config';

    function init() {
        $('#ai-reviewer-prompts-form').on('submit', handleSubmit);
        $('#reset-prompts-btn').on('click', handleReset);
        refreshPrompts();
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
        $('#prompt-system-append').val(config['prompt.system.append'] || '');

        var effectiveSystem = defaultSystem;
        if (config['prompt.system.append']) {
            var systemAppend = config['prompt.system.append'];
            if (systemAppend.trim().length) {
                effectiveSystem += (effectiveSystem.endsWith('\n') ? '' : '\n') + systemAppend;
            }
        }
        $('#prompt-system-effective').val(effectiveSystem);

        var effectiveChunk = config['prompt.chunk'] || defaultChunk;
        var chunkAppend = config['prompt.chunk.append'];
        if (chunkAppend && chunkAppend.trim().length) {
            effectiveChunk += (effectiveChunk.endsWith('\n') ? '' : '\n') + chunkAppend;
        }
        $('#prompt-chunk-effective').val(effectiveChunk);
        autoResizeAll();
    }

    function savePrompts() {
        var payload = {
            'prompt.chunk': $('#prompt-chunk').val(),
            'prompt.system.append': $('#prompt-system-append').val()
        };
        showLoading(true);
        $.ajax({
            url: apiUrl,
            type: 'PUT',
            contentType: 'application/json',
            data: JSON.stringify(payload),
            success: function() {
                showMessage('success', 'Prompt settings saved.');
                refreshPrompts();
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
            .append('<p>' + message + '</p>')
            .append('<span class="aui-icon icon-close" role="button" tabindex="0"></span>');
        $container.append($message);
        $message.find('.icon-close').on('click', function() {
            $message.fadeOut(function() {
                $(this).remove();
            });
        });
    }

    function autoResizeAll() {
        ['#prompt-system-effective', '#prompt-chunk-effective', '#prompt-chunk', '#prompt-system-append']
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
