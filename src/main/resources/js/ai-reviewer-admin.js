/**
 * AI Code Reviewer Admin Configuration JavaScript
 * Handles form submission, validation, and API interactions
 */

(function($) {
    'use strict';

    var baseUrl = AJS.$('meta[name="application-base-url"]').attr("content");
    var apiUrl = baseUrl + '/rest/ai-reviewer/1.0/config';

    /**
     * Initialize the admin configuration page
     */
    function init() {
        console.log('AI Reviewer Admin: Initializing...');

        // Bind event handlers
        $('#ai-reviewer-config-form').on('submit', handleFormSubmit);
        $('#test-connection-btn').on('click', testOllamaConnection);
        $('#reset-config-btn').on('click', resetToDefaults);

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
        // Text fields
        $('#ollama-url').val(config.ollamaUrl || '');
        $('#ollama-model').val(config.ollamaModel || '');
        $('#fallback-model').val(config.fallbackModel || '');
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
        $('#max-retries').val(config.maxRetries || 3);
        $('#base-retry-delay').val(config.baseRetryDelay || 1000);
        $('#api-delay').val(config.apiDelay || 100);
        $('#min-severity').val(config.minSeverity || 'medium');
        $('#require-approval-for').val(config.requireApprovalFor || 'critical,high');
        $('#review-extensions').val(config.reviewExtensions || '');
        $('#ignore-patterns').val(config.ignorePatterns || '');
        $('#ignore-paths').val(config.ignorePaths || '');

        // Checkboxes
        $('#enabled').prop('checked', config.enabled !== false);
        $('#review-draft-prs').prop('checked', config.reviewDraftPRs === true);
        $('#skip-generated-files').prop('checked', config.skipGeneratedFiles !== false);
        $('#skip-tests').prop('checked', config.skipTests === true);
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

    /**
     * Collect form data into configuration object
     */
    function collectFormData() {
        return {
            ollamaUrl: $('#ollama-url').val().trim(),
            ollamaModel: $('#ollama-model').val().trim(),
            fallbackModel: $('#fallback-model').val().trim(),
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
            maxRetries: parseInt($('#max-retries').val()),
            baseRetryDelay: parseInt($('#base-retry-delay').val()),
            apiDelay: parseInt($('#api-delay').val()),
            minSeverity: $('#min-severity').val(),
            requireApprovalFor: $('#require-approval-for').val().trim(),
            reviewExtensions: $('#review-extensions').val().trim(),
            ignorePatterns: $('#ignore-patterns').val().trim(),
            ignorePaths: $('#ignore-paths').val().trim(),
            enabled: $('#enabled').is(':checked'),
            reviewDraftPRs: $('#review-draft-prs').is(':checked'),
            skipGeneratedFiles: $('#skip-generated-files').is(':checked'),
            skipTests: $('#skip-tests').is(':checked')
        };
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
                showMessage('success', 'Configuration saved successfully!');
                showLoading(false);
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
                $('#test-connection-result').removeClass('testing').addClass('success')
                    .text('Connection successful!');
                setTimeout(function() {
                    $('#test-connection-result').fadeOut(function() {
                        $(this).text('').removeClass('success').show();
                    });
                }, 3000);
            },
            error: function(xhr, status, error) {
                var errorMsg = xhr.responseJSON && xhr.responseJSON.message ?
                    xhr.responseJSON.message : error;
                $('#test-connection-result').removeClass('testing').addClass('error')
                    .text('Connection failed: ' + errorMsg);
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
            maxRetries: 3,
            baseRetryDelay: 1000,
            apiDelay: 100,
            minSeverity: 'medium',
            requireApprovalFor: 'critical,high',
            reviewExtensions: 'java,groovy,js,ts,tsx,jsx,py,go,rs,cpp,c,cs,php,rb,kt,swift,scala',
            ignorePatterns: '*.min.js,*.generated.*,package-lock.json,yarn.lock,*.map',
            ignorePaths: 'node_modules/,vendor/,build/,dist/,.git/',
            enabled: true,
            reviewDraftPRs: false,
            skipGeneratedFiles: true,
            skipTests: false
        };

        populateForm(defaults);
        showMessage('info', 'Form reset to default values. Click "Save Configuration" to apply.');
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

    // Initialize when DOM is ready
    $(document).ready(function() {
        init();
    });

})(AJS.$);
