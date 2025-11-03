(function($) {
    'use strict';

    var Global = window.AIReviewer || (window.AIReviewer = {});
    if (Global.Common) {
        return;
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

    function tryParseJson(payload) {
        if (!payload || typeof payload !== 'string') {
            return null;
        }
        try {
            return JSON.parse(payload);
        } catch (e) {
            return null;
        }
    }

    function getCorrelationId(xhr) {
        if (!xhr || typeof xhr.getResponseHeader !== 'function') {
            return null;
        }
        var headerNames = [
            'X-Correlation-Id',
            'X-Correlation-ID',
            'X-Request-Id',
            'X-Trace-Id'
        ];
        for (var i = 0; i < headerNames.length; i++) {
            var value = xhr.getResponseHeader(headerNames[i]);
            if (value) {
                return value;
            }
        }
        return null;
    }

    function extractMessages(parsed) {
        if (!parsed) {
            return [];
        }
        if (typeof parsed === 'string') {
            return [parsed];
        }
        var messages = [];
        if (parsed.message) {
            messages.push(parsed.message);
        }
        if (Array.isArray(parsed.messages)) {
            messages = messages.concat(parsed.messages);
        }
        if (Array.isArray(parsed.errorMessages)) {
            messages = messages.concat(parsed.errorMessages);
        }
        if (parsed.errors && typeof parsed.errors === 'object') {
            Object.keys(parsed.errors).forEach(function(key) {
                messages.push(parsed.errors[key]);
            });
        }
        return messages;
    }

    function composeErrorDetails(xhr, fallback) {
        var parts = [];
        if (xhr) {
            if (xhr.status) {
                var statusLabel = xhr.statusText ? xhr.status + ' ' + xhr.statusText : String(xhr.status);
                parts.push(statusLabel);
            }
            var parsed = xhr.responseJSON || tryParseJson(xhr.responseText);
            var messages = extractMessages(parsed);
            if (messages.length) {
                parts = parts.concat(messages);
            }
        }
        if (fallback && parts.indexOf(fallback) === -1) {
            parts.push(fallback);
        }
        var detail = parts.filter(function(item) {
            return item !== undefined && item !== null && item !== '';
        }).join(' — ');
        return detail || 'Request failed';
    }

    function formatTimestamp(ms) {
        if (ms === undefined || ms === null || ms === '') {
            return '—';
        }
        var date = new Date(ms);
        if (isNaN(date.getTime())) {
            return '—';
        }
        return date.toLocaleString();
    }

    function logAjaxError(context, xhr, status, error) {
        var correlationId = getCorrelationId(xhr);
        var payload = {
            status: xhr ? xhr.status : undefined,
            statusText: xhr ? xhr.statusText : undefined,
            response: xhr ? (xhr.responseJSON || xhr.responseText) : undefined,
            correlationId: correlationId,
            statusArg: status,
            error: error
        };
        console.error(context, payload);
    }

    Global.Common = {
        escapeHtml: escapeHtml,
        tryParseJson: tryParseJson,
        getCorrelationId: getCorrelationId,
        composeErrorDetails: composeErrorDetails,
        formatTimestamp: formatTimestamp,
        logAjaxError: logAjaxError
    };
})(AJS.$);
