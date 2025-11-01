(function($) {
    'use strict';

    var baseUrl = AJS.$('meta[name="application-base-url"]').attr('content') ||
                  AJS.$('meta[name="ajs-context-path"]').attr('content') ||
                  window.location.origin + (AJS.contextPath() || '');

    var historyUrl = baseUrl + '/rest/ai-reviewer/1.0/history';
    var selectedHistoryId = null;

    function init() {
        $('#refresh-history-btn').on('click', loadHistory);
        loadHistory();
    }

    function loadHistory() {
        setHistoryMessage('info', 'Loading review history...', true);

        var projectKey = $('#filter-project').val();
        var repositorySlug = $('#filter-repo').val();
        var pullRequestId = $('#filter-pr').val();

        var params = { limit: 50 };
        if (projectKey) {
            params.projectKey = projectKey;
        }
        if (repositorySlug) {
            params.repositorySlug = repositorySlug;
        }
        if (pullRequestId) {
            params.pullRequestId = pullRequestId;
        }

        $.ajax({
            url: historyUrl,
            type: 'GET',
            dataType: 'json',
            data: params
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
            hideDetailPanel();
            return;
        }

        entries.forEach(function(entry, idx) {
            var started = formatTimestamp(entry.reviewStartTime);
            var repo = formatRepo(entry);
            var status = formatStatus(entry);
            var issues = formatIssues(entry);
            var model = entry.modelUsed || '—';

            var row = '<tr class="history-row" data-history-id="' + entry.id + '">' +
                    '<td>' + started + '</td>' +
                    '<td>' + repo + '</td>' +
                    '<td>' + status + '</td>' +
                    '<td>' + issues + '</td>' +
                    '<td>' + model + '</td>' +
                '</tr>';
            $tbody.append(row);
        });

        $tbody.find('tr.history-row').on('click', function() {
            var $row = $(this);
            var historyId = $row.data('history-id');
            if (!historyId) {
                return;
            }
            selectHistoryRow($row, historyId);
            loadHistoryDetail(historyId);
        });

        var initialSelection = selectedHistoryId;
        if (initialSelection == null && entries.length) {
            initialSelection = entries[0].id;
        }
        if (initialSelection != null) {
            var $initialRow = $tbody.find('tr.history-row[data-history-id="' + initialSelection + '"]');
            if ($initialRow.length) {
                selectHistoryRow($initialRow, initialSelection);
                loadHistoryDetail(initialSelection);
            } else {
                hideDetailPanel();
            }
        } else {
            hideDetailPanel();
        }
    }

    function selectHistoryRow($row, historyId) {
        $('#review-history-table tbody tr.history-row').removeClass('is-selected');
        $row.addClass('is-selected');
        selectedHistoryId = historyId;
    }

    function loadHistoryDetail(historyId) {
        if (!historyId) {
            hideDetailPanel();
            return;
        }
        showDetailPanel();
        setDetailMessage('info', 'Loading details...', true);

        $.when(
            $.ajax({
                url: historyUrl + '/' + historyId,
                type: 'GET',
                dataType: 'json'
            }),
            $.ajax({
                url: historyUrl + '/' + historyId + '/chunks',
                type: 'GET',
                dataType: 'json',
                data: { limit: 200 }
            })
        ).done(function(historyResp, chunksResp) {
            var entry = historyResp[0] || {};
            var chunkPayload = chunksResp[0] || {};
            renderDetail(entry, chunkPayload);
            clearDetailMessage();
        }).fail(function(xhr, status, error) {
            console.error('Failed to fetch history detail:', status, error);
            renderDetail({}, { chunks: [] });
            setDetailMessage('error', 'Failed to load detail: ' + (error || status), false);
        });
    }

    function renderDetail(entry, chunkPayload) {
        var subtitle = entry.projectKey && entry.repositorySlug
            ? (entry.projectKey + '/' + entry.repositorySlug + (entry.pullRequestId ? (' #' + entry.pullRequestId) : ''))
            : (entry.pullRequestId ? ('PR #' + entry.pullRequestId) : '');
        $('#history-detail-subtitle').text(subtitle || '');

        renderDetailList('#detail-overview', [
            { label: 'Status', value: formatStatus(entry) },
            { label: 'Duration', value: formatDuration(entry.durationSeconds) },
            { label: 'Start', value: formatTimestamp(entry.reviewStartTime) },
            { label: 'End', value: formatTimestamp(entry.reviewEndTime) },
            { label: 'Profile', value: entry.profileKey || '—' },
            { label: 'Auto Approve', value: entry.autoApproveEnabled ? 'Enabled' : 'Disabled' }
        ]);

        renderDetailList('#detail-findings', [
            { label: 'Total Issues', value: entry.totalIssuesFound != null ? entry.totalIssuesFound : '0' },
            { label: 'Critical', value: entry.criticalIssues || 0 },
            { label: 'High', value: entry.highIssues || 0 },
            { label: 'Medium', value: entry.mediumIssues || 0 },
            { label: 'Low', value: entry.lowIssues || 0 },
            { label: 'Comments Posted', value: entry.commentsPosted || 0 }
        ]);

        renderDetailList('#detail-model', [
            { label: 'Primary Invocations', value: entry.primaryModelInvocations || 0 },
            { label: 'Primary Successes', value: entry.primaryModelSuccesses || 0 },
            { label: 'Primary Failures', value: entry.primaryModelFailures || 0 },
            { label: 'Fallback Invocations', value: entry.fallbackModelInvocations || 0 },
            { label: 'Fallback Successes', value: entry.fallbackModelSuccesses || 0 },
            { label: 'Fallback Failures', value: entry.fallbackModelFailures || 0 },
            { label: 'Fallback Triggered', value: entry.fallbackTriggered || 0 }
        ]);

        renderChunkTable(chunkPayload && Array.isArray(chunkPayload.chunks) ? chunkPayload.chunks : []);
    }

    function renderDetailList(selector, items) {
        var $dl = $(selector);
        if (!$dl.length) {
            return;
        }
        var html = '';
        items.forEach(function(item) {
            html += '<dt>' + escapeHtml(item.label) + '</dt>' +
                    '<dd>' + escapeHtml(item.value != null ? item.value : '—') + '</dd>';
        });
        $dl.html(html);
    }

    function renderChunkTable(chunks) {
        var $tbody = $('#chunk-table tbody');
        $tbody.empty();
        if (!chunks.length) {
            $tbody.append('<tr class="chunk-empty"><td colspan="7">No chunk records available for this review.</td></tr>');
            return;
        }
        chunks.forEach(function(chunk, index) {
            var status = chunk.success ? 'Success' : (chunk.modelNotFound ? 'Model Missing' : 'Failed');
            var row = '<tr>' +
                '<td>' + (index + 1) + '</td>' +
                '<td>' + escapeHtml(chunk.chunkId || '—') + '</td>' +
                '<td>' + escapeHtml(chunk.role || '—') + '</td>' +
                '<td>' + escapeHtml(chunk.model || '—') + '</td>' +
                '<td>' + formatAttempts(chunk.attempts, chunk.retries) + '</td>' +
                '<td>' + formatDurationMs(chunk.durationMs) + '</td>' +
                '<td>' + escapeHtml(status) + (chunk.lastError ? '<br><span class="chunk-error">' + escapeHtml(chunk.lastError) + '</span>' : '') + '</td>' +
                '</tr>';
            $tbody.append(row);
        });
    }

    function formatAttempts(attempts, retries) {
        var result = (attempts != null ? attempts : '—');
        if (retries != null && retries > 0) {
            result += ' (' + retries + ' retry' + (retries === 1 ? '' : 's') + ')';
        }
        return result;
    }

    function formatDurationMs(durationMs) {
        if (!durationMs && durationMs !== 0) {
            return '—';
        }
        var ms = parseInt(durationMs, 10);
        if (isNaN(ms)) {
            return '—';
        }
        if (ms < 1000) {
            return ms + ' ms';
        }
        var seconds = ms / 1000;
        if (seconds < 60) {
            return seconds.toFixed(1) + ' s';
        }
        var minutes = Math.floor(seconds / 60);
        var rem = (seconds % 60).toFixed(0);
        return minutes + 'm ' + rem + 's';
    }

    function formatDuration(durationSeconds) {
        if (!durationSeconds && durationSeconds !== 0) {
            return '—';
        }
        var seconds = Number(durationSeconds);
        if (!isFinite(seconds) || seconds < 0) {
            return '—';
        }
        if (seconds < 60) {
            return seconds.toFixed(1) + ' s';
        }
        var minutes = Math.floor(seconds / 60);
        var rem = Math.round(seconds % 60);
        return minutes + 'm ' + rem + 's';
    }

    function showDetailPanel() {
        $('#history-detail-panel').show();
    }

    function hideDetailPanel() {
        $('#history-detail-panel').hide();
        selectedHistoryId = null;
    }

    function setDetailMessage(type, message, showSpinner) {
        var $message = $('#detail-message');
        if (!message) {
            $message.hide().text('').removeClass('info error');
            return;
        }
        $message.removeClass('info error').addClass(type === 'error' ? 'error' : 'info');
        var content = showSpinner ? '<span class="aui-icon aui-icon-wait"></span> ' + escapeHtml(message) : escapeHtml(message);
        $message.html(content).show();
    }

    function clearDetailMessage() {
        setDetailMessage(null, null, false);
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

    function escapeHtml(value) {
        if (value == null) {
            return '';
        }
        return String(value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#039;');
    }

    $(document).ready(init);

})(AJS.$);
