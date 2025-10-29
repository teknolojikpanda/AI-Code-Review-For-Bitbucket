(function($) {
    'use strict';

    var baseUrl = AJS.$('meta[name="application-base-url"]').attr('content') ||
                  AJS.$('meta[name="ajs-context-path"]').attr('content') ||
                  window.location.origin + (AJS.contextPath() || '');

    var historyUrl = baseUrl + '/rest/ai-reviewer/1.0/history';

    function init() {
        $('#refresh-history-btn').on('click', loadHistory);
        loadHistory();
    }

    function loadHistory() {
        setHistoryMessage('info', 'Loading review history...', true);
        $.ajax({
            url: historyUrl + '?limit=50',
            type: 'GET',
            dataType: 'json'
        }).done(function(response) {
            var entries = response && Array.isArray(response.entries) ? response.entries : [];
            renderHistory(entries);
            if (!entries.length) {
                setHistoryMessage('info', 'No review runs recorded yet.', false);
            } else {
                clearHistoryMessage();
            }
        }).fail(function(xhr, status, error) {
            console.error('Failed to fetch review history:', status, error);
            renderHistory([]);
            setHistoryMessage('error', 'Failed to load review history: ' + (error || status), false);
        });
    }

    function renderHistory(entries) {
        var $tbody = $('#review-history-table tbody');
        $tbody.empty();

        if (!entries.length) {
            $tbody.append('<tr class="history-empty"><td colspan="5">No history entries available.</td></tr>');
            return;
        }

        entries.forEach(function(entry) {
            var started = formatTimestamp(entry.reviewStartTime);
            var repo = formatRepo(entry);
            var status = formatStatus(entry);
            var issues = formatIssues(entry);
            var model = entry.modelUsed || '—';

            var row = '<tr>' +
                    '<td>' + started + '</td>' +
                    '<td>' + repo + '</td>' +
                    '<td>' + status + '</td>' +
                    '<td>' + issues + '</td>' +
                    '<td>' + model + '</td>' +
                '</tr>';
            $tbody.append(row);
        });
    }

    function formatTimestamp(epochMillis) {
        if (!epochMillis) {
            return '—';
        }
        var date = new Date(epochMillis);
        return isNaN(date.getTime()) ? '—' : date.toLocaleString();
    }

    function formatRepo(entry) {
        if (!entry) {
            return '—';
        }
        if (entry.projectKey && entry.repositorySlug) {
            var pr = entry.pullRequestId ? (' #' + entry.pullRequestId) : '';
            return entry.projectKey + '/' + entry.repositorySlug + pr;
        }
        return entry.pullRequestId || '—';
    }

    function formatStatus(entry) {
        if (!entry) {
            return 'UNKNOWN';
        }
        var status = entry.reviewStatus || 'UNKNOWN';
        if (entry.hasBlockingIssues) {
            status += ' ⚠️';
        }
        return status;
    }

    function formatIssues(entry) {
        if (!entry) {
            return '0';
        }
        return (entry.totalIssuesFound || 0) + ' (C' +
            (entry.criticalIssues || 0) + '/H' +
            (entry.highIssues || 0) + '/M' +
            (entry.mediumIssues || 0) + '/L' +
            (entry.lowIssues || 0) + ')';
    }

    function setHistoryMessage(type, message, showSpinner) {
        var $message = $('#history-message');
        if (!message) {
            $message.hide().text('').removeClass('info error');
            return;
        }
        $message.removeClass('info error').addClass(type === 'error' ? 'error' : 'info');
        $message.html(showSpinner ? '<span class="aui-icon aui-icon-wait"></span> ' + message : message).show();
    }

    function clearHistoryMessage() {
        setHistoryMessage(null, null, false);
    }

    $(document).ready(init);

})(AJS.$);
