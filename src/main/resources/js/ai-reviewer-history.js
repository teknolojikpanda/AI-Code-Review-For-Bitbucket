(function($) {
    'use strict';

    var baseUrl = AJS.$('meta[name="application-base-url"]').attr('content') ||
                  AJS.$('meta[name="ajs-context-path"]').attr('content') ||
                  window.location.origin + (AJS.contextPath() || '');

    var historyUrl = baseUrl + '/rest/ai-reviewer/1.0/history';
    var selectedHistoryId = null;

    function init() {
        $('#refresh-history-btn').on('click', function() {
            loadHistory(0);
        });
        $('#page-size-select').on('change', function() {
            loadHistory(0);
        });
        loadHistory();
    }

    var pagination = {
        offset: 0,
        limit: 100,
        total: 0,
        all: false
    };

    function loadHistory(newOffset) {
        setHistoryMessage('info', 'Loading review history...', true);

        if (typeof newOffset === 'number') {
            pagination.offset = Math.max(newOffset, 0);
        }

        var selectedSize = $('#page-size-select').val();
        pagination.all = selectedSize === 'ALL';
        pagination.limit = pagination.all ? 1000 : parseInt(selectedSize || '100', 10);
        if (pagination.all) {
            pagination.offset = 0;
        }

        var projectKey = $('#filter-project').val();
        var repositorySlug = $('#filter-repo').val();
        var pullRequestId = $('#filter-pr').val();
        var sinceValue = parseDateTime($('#filter-since').val());
        var untilValue = parseDateTime($('#filter-until').val());

        var params = {};
        if (pagination.all) {
            params.limit = 0;
            params.offset = 0;
        } else {
            params.limit = pagination.limit;
            params.offset = pagination.offset;
        }
        if (projectKey) {
            params.projectKey = projectKey;
        }
        if (repositorySlug) {
            params.repositorySlug = repositorySlug;
        }
        if (pullRequestId) {
            params.pullRequestId = pullRequestId;
        }
        if (sinceValue != null) {
            params.since = sinceValue;
        }
        if (untilValue != null) {
            params.until = untilValue;
        }

        var metricsFilters = {};
        if (projectKey) {
            metricsFilters.projectKey = projectKey;
        }
        if (repositorySlug) {
            metricsFilters.repositorySlug = repositorySlug;
        }
        if (pullRequestId) {
            metricsFilters.pullRequestId = pullRequestId;
        }
        if (sinceValue != null) {
            metricsFilters.since = sinceValue;
        }
        if (untilValue != null) {
            metricsFilters.until = untilValue;
        }
        loadMetrics(metricsFilters);
        loadDailyMetrics(metricsFilters);

        $.ajax({
            url: historyUrl,
            type: 'GET',
            dataType: 'json',
            data: params
        }).done(function(response) {
            var entries = response && Array.isArray(response.entries) ? response.entries : [];
            if (pagination.all) {
                pagination.total = entries.length;
                pagination.offset = 0;
                pagination.nextOffset = null;
                pagination.prevOffset = null;
            } else {
                pagination.total = response && typeof response.total === 'number' ? response.total : entries.length;
                pagination.limit = response && typeof response.limit === 'number' ? response.limit : pagination.limit;
                pagination.offset = response && typeof response.offset === 'number' ? response.offset : pagination.offset;
                pagination.nextOffset = response && typeof response.nextOffset === 'number' ? response.nextOffset : null;
                pagination.prevOffset = response && typeof response.prevOffset === 'number' ? response.prevOffset : null;
            }

            renderHistory(entries);
            renderPagination();
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

    function loadMetrics(filters) {
        showMetricsSection();
        setMetricsMessage('info', 'Loading metrics...', true);

        var metricsBase = filters || {};
        if (pagination.all) {
            metricsBase = $.extend({
                limit: 0,
                offset: 0
            }, metricsBase);
        } else {
            metricsBase = $.extend({
                limit: pagination.limit,
                offset: pagination.offset
            }, metricsBase);
        }
        var data = metricsBase;

        $.ajax({
            url: historyUrl + '/metrics',
            type: 'GET',
            dataType: 'json',
            data: data
        }).done(function(response) {
            renderMetrics(response || {});
        }).fail(function(xhr, status, error) {
            console.error('Failed to fetch history metrics:', status, error);
            renderMetrics({});
            setMetricsMessage('error', 'Failed to load metrics: ' + (error || status), false);
        });
    }

    function loadDailyMetrics(filters) {
        var data = $.extend({ limit: 30 }, filters || {});
        $.ajax({
            url: historyUrl + '/metrics/daily',
            type: 'GET',
            dataType: 'json',
            data: data
        }).done(function(response) {
            var rows = response && Array.isArray(response.days) ? response.days : [];
            renderDailyMetrics(rows);
        }).fail(function(xhr, status, error) {
            console.error('Failed to fetch daily metrics:', status, error);
            renderDailyMetrics(null, error || status);
        });
    }

    function showMetricsSection() {
        $('#metrics-section').show();
    }

    function renderMetrics(data) {
        data = data || {};
        showMetricsSection();

        var totalReviews = Number(data.totalReviews || 0);
        var statusCounts = data.statusCounts || {};
        var issueTotals = data.issueTotals || {};
        var duration = data.durationSeconds || {};
        var fallback = data.fallback || {};
        var chunkTotals = data.chunkTotals || {};

        $('#metric-total-reviews').text(totalReviews);

        var statusBreakdown = Object.keys(statusCounts).length
            ? Object.keys(statusCounts).map(function(key) {
                return key + ': ' + (statusCounts[key] || 0);
            }).join(' • ')
            : 'No reviews';
        $('#metric-status-breakdown').text(statusBreakdown);

        $('#metric-avg-duration').text(formatDuration(duration.average));
        var durationDetailParts = [];
        if (duration.p95 != null) {
            durationDetailParts.push('p95 ' + formatDuration(duration.p95));
        }
        if (duration.max != null) {
            durationDetailParts.push('max ' + formatDuration(duration.max));
        }
        $('#metric-duration-detail').text(durationDetailParts.length ? durationDetailParts.join(' • ') : '—');

        var totalIssues = Number(issueTotals.totalIssues || 0);
        $('#metric-total-issues').text(totalIssues);
        var issueBreakdown = 'C' + (issueTotals.critical || 0) +
            ' H' + (issueTotals.high || 0) +
            ' M' + (issueTotals.medium || 0) +
            ' L' + (issueTotals.low || 0);
        $('#metric-issue-breakdown').text(issueBreakdown);

        var fallbackRate = totalReviews > 0 ? (fallback.triggered || 0) / totalReviews : null;
        $('#metric-fallback-rate').text(formatPercent(fallbackRate));
        var fallbackDetailParts = [
            'Triggered ' + (fallback.triggered || 0),
            'Primary fail ' + (fallback.primaryFailures || 0),
            'Fallback success ' + (fallback.fallbackSuccesses || 0)
        ];
        if (chunkTotals && chunkTotals.successRate != null) {
            fallbackDetailParts.push('Chunk success ' + formatPercent(chunkTotals.successRate));
        }
        $('#metric-fallback-detail').text(fallbackDetailParts.join(' • '));

        var subtitle = buildMetricsSubtitle(data.filter || {});
        $('#metrics-subtitle').text(subtitle);

        if (totalReviews === 0) {
            setMetricsMessage('info', 'No review runs match the current filters.', false);
        } else {
            clearMetricsMessage();
        }
    }

    function renderDailyMetrics(rows, error) {
        var $tbody = $('#metrics-daily-table tbody');
        $tbody.empty();
        if (error) {
            $tbody.append('<tr class="daily-empty"><td colspan="4">' + escapeHtml('Failed: ' + error) + '</td></tr>');
            renderDailySparkline([]);
            return;
        }
        if (!rows || !rows.length) {
            $tbody.append('<tr class="daily-empty"><td colspan="4">No data for selected filters.</td></tr>');
            renderDailySparkline([]);
            return;
        }
        rows.forEach(function(row) {
            var duration = formatDuration(row.avgDurationSeconds);
            var tr = '<tr>' +
                '<td>' + escapeHtml(row.date) + '</td>' +
                '<td>' + escapeHtml(row.reviewCount) + '</td>' +
                '<td>' + escapeHtml(row.totalIssues) + ' (C' +
                    escapeHtml(row.criticalIssues || 0) + '/H' +
                    escapeHtml(row.highIssues || 0) + '/M' +
                    escapeHtml(row.mediumIssues || 0) + '/L' +
                    escapeHtml(row.lowIssues || 0) + ')' + '</td>' +
                '<td>' + escapeHtml(duration) + '</td>' +
                '</tr>';
            $tbody.append(tr);
        });
        renderDailySparkline(rows);
    }

    function renderDailySparkline(rows) {
        var $svg = $('#metrics-daily-sparkline');
        if (!$svg.length) {
            return;
        }
        if (!rows || !rows.length) {
            $svg.empty();
            return;
        }
        var rect = $svg[0].getBoundingClientRect();
        var width = rect.width || 400;
        var height = rect.height || 80;
        var points = rows.slice().reverse();
        var values = points.map(function(row) { return Number(row.reviewCount || 0); });
        var min = Math.min.apply(null, values);
        var max = Math.max.apply(null, values);
        if (min === max) {
            if (max === 0) {
                max = 1;
            } else {
                min = 0;
            }
        }
        var range = max - min;
        var step = points.length > 1 ? width / (points.length - 1) : width;
        var path = '';
        var fillPath = '';
        points.forEach(function(row, idx) {
            var value = Number(row.reviewCount || 0);
            var x = idx * step;
            var y = height - ((value - min) / range) * height;
            var command = idx === 0 ? 'M' : 'L';
            path += command + x.toFixed(2) + ' ' + y.toFixed(2);
        });
        fillPath = path + ' L ' + ((points.length - 1) * step).toFixed(2) + ' ' + height + ' L 0 ' + height + ' Z';
        $svg.html('<path class="sparkline-fill" d="' + fillPath + '"></path><path class="sparkline-line" d="' + path + '"></path>');
    }

    function buildMetricsSubtitle(filter) {
        if (!filter) {
            return '';
        }
        var parts = [];
        if (filter.projectKey && filter.repositorySlug) {
            parts.push(filter.projectKey + '/' + filter.repositorySlug + (filter.pullRequestId ? (' #' + filter.pullRequestId) : ''));
        } else if (filter.projectKey) {
            parts.push(filter.projectKey);
        } else if (filter.repositorySlug) {
            parts.push(filter.repositorySlug);
        }
        if (filter.pullRequestId && !(filter.projectKey && filter.repositorySlug && filter.pullRequestId)) {
            parts.push('PR #' + filter.pullRequestId);
        }
        if (filter.since || filter.until) {
            var range = [];
            if (filter.since) {
                range.push('from ' + formatTimestamp(filter.since));
            }
            if (filter.until) {
                range.push('to ' + formatTimestamp(filter.until));
            }
            parts.push(range.join(' '));
        }
        if (!parts.length) {
            return 'Showing all reviews';
        }
        return parts.join(' • ');
    }

    function setMetricsMessage(type, message, showSpinner) {
        var $message = $('#metrics-message');
        if (!message) {
            $message.hide().text('').removeClass('info error');
            return;
        }
        $message.removeClass('info error').addClass(type === 'error' ? 'error' : 'info');
        var content = showSpinner ? '<span class="aui-icon aui-icon-wait"></span> ' + escapeHtml(message) : escapeHtml(message);
        $message.html(content).show();
    }

    function clearMetricsMessage() {
        setMetricsMessage(null, null, false);
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

    function scrollToDetailPanel() {
        var $panel = $('#history-detail-panel');
        if (!$panel.is(':visible')) {
            return;
        }
        var top = $panel.offset().top - 80;
        $('html, body').animate({ scrollTop: top }, 250);
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

    function formatPercent(ratio, decimals) {
        if (ratio == null || isNaN(ratio)) {
            return '—';
        }
        var value = Number(ratio);
        var digits = (decimals === 0 || decimals) ? decimals : 1;
        return (value * 100).toFixed(digits) + '%';
    }

    function collapseDetails() {
        $('#review-history-table tbody tr.history-row').removeClass('is-selected is-expanded');
        $('#review-history-table tbody tr.history-detail-row').remove();
    }

    function expandRow($row, suppressScroll) {
        collapseDetails();
        var historyId = $row.data('history-id');
        if (!historyId) {
            return;
        }
        selectedHistoryId = historyId;
        $row.addClass('is-selected is-expanded');
        var $detailRow = $('<tr class="history-detail-row"><td colspan="5"><div class="history-detail-card detail-loading"><span class="aui-icon aui-icon-wait"></span> Loading details...</div></td></tr>');
        $row.after($detailRow);
        loadHistoryDetail(historyId, $detailRow, suppressScroll);
        if (!suppressScroll) {
            scrollToRow($row);
        }
    }

    function renderHistory(entries) {
        var $tbody = $('#review-history-table tbody');
        $tbody.empty();
        collapseDetails();

        if (!entries.length) {
            $tbody.append('<tr class="history-empty"><td colspan="5">No history entries available.</td></tr>');
            selectedHistoryId = null;
            return;
        }

        entries.forEach(function(entry) {
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
            if ($row.hasClass('is-expanded')) {
                collapseDetails();
                selectedHistoryId = null;
                return;
            }
            expandRow($row, false);
        });

        if (selectedHistoryId != null) {
            var $initialRow = $tbody.find('tr.history-row[data-history-id="' + selectedHistoryId + '"]');
            if ($initialRow.length) {
                expandRow($initialRow, true);
            } else {
                selectedHistoryId = null;
            }
        }
    }

    function loadHistoryDetail(historyId, $detailRow, suppressScroll) {
        if (!historyId || !$detailRow || !$detailRow.length) {
            return;
        }

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
            renderDetail($detailRow, entry, chunkPayload, suppressScroll);
        }).fail(function(xhr, status, error) {
            console.error('Failed to fetch history detail:', status, error);
            var errorHtml = '<div class="history-detail-card detail-error"><span class="aui-icon aui-icon-error"></span> ' +
                escapeHtml('Failed to load detail: ' + (error || status)) + '</div>';
            $detailRow.find('td').html(errorHtml);
        });
    }

    function renderDetail($detailRow, entry, chunkPayload, suppressScroll) {
        var overviewItems = [
            { label: 'Status', value: formatStatus(entry) },
            { label: 'Duration', value: formatDuration(entry.durationSeconds) },
            { label: 'Start', value: formatTimestamp(entry.reviewStartTime) },
            { label: 'End', value: formatTimestamp(entry.reviewEndTime) },
            { label: 'Profile', value: entry.profileKey || '—' },
            { label: 'Auto Approve', value: entry.autoApproveEnabled ? 'Enabled' : 'Disabled' }
        ];

        var findingItems = [
            { label: 'Total Issues', value: entry.totalIssuesFound != null ? entry.totalIssuesFound : 0 },
            { label: 'Critical', value: entry.criticalIssues || 0 },
            { label: 'High', value: entry.highIssues || 0 },
            { label: 'Medium', value: entry.mediumIssues || 0 },
            { label: 'Low', value: entry.lowIssues || 0 },
            { label: 'Comments Posted', value: entry.commentsPosted || 0 }
        ];

        var modelItems = [
            { label: 'Primary Invocations', value: entry.primaryModelInvocations || 0 },
            { label: 'Primary Successes', value: entry.primaryModelSuccesses || 0 },
            { label: 'Primary Failures', value: entry.primaryModelFailures || 0 },
            { label: 'Fallback Invocations', value: entry.fallbackModelInvocations || 0 },
            { label: 'Fallback Successes', value: entry.fallbackModelSuccesses || 0 },
            { label: 'Fallback Failures', value: entry.fallbackModelFailures || 0 },
            { label: 'Fallback Triggered', value: entry.fallbackTriggered || 0 }
        ];

        var chunks = chunkPayload && Array.isArray(chunkPayload.chunks) ? chunkPayload.chunks : [];
        var subtitle = entry.projectKey && entry.repositorySlug
            ? (entry.projectKey + '/' + entry.repositorySlug + (entry.pullRequestId ? (' #' + entry.pullRequestId) : ''))
            : (entry.pullRequestId ? ('PR #' + entry.pullRequestId) : '');

        var html = buildDetailCard(subtitle, overviewItems, findingItems, modelItems, chunks, chunkPayload);
        $detailRow.find('td').html(html);

        if (!suppressScroll) {
            scrollToRow($detailRow.prev());
        }
    }

    function buildDetailCard(subtitle, overviewItems, findingItems, modelItems, chunks, chunkPayload) {
        var chunkCount = chunkPayload && typeof chunkPayload.total === 'number' ? chunkPayload.total : chunks.length;
        var chunkSummary = chunkCount ? 'Showing ' + chunks.length + ' of ' + chunkCount + ' chunk invocations' : '';

        return '<div class="history-detail-card">' +
            '<header class="history-detail-header">' +
                '<h3>Review Details</h3>' +
                '<span class="detail-subtitle">' + escapeHtml(subtitle || '') + '</span>' +
            '</header>' +
            '<div class="history-detail-grid">' +
                '<div class="detail-block">' +
                    '<h4>Overview</h4>' +
                    buildDetailListHtml(overviewItems) +
                '</div>' +
                '<div class="detail-block">' +
                    '<h4>Findings</h4>' +
                    buildDetailListHtml(findingItems) +
                '</div>' +
                '<div class="detail-block">' +
                    '<h4>Model Summary</h4>' +
                    buildDetailListHtml(modelItems) +
                '</div>' +
            '</div>' +
            '<div class="history-detail-chunks">' +
                '<div class="chunk-summary">' + escapeHtml(chunkSummary) + '</div>' +
                buildChunkTableHtml(chunks) +
            '</div>' +
        '</div>';
    }

    function buildDetailListHtml(items) {
        var html = '<dl class="detail-list">';
        items.forEach(function(item) {
            html += '<dt>' + escapeHtml(item.label) + '</dt>' +
                '<dd>' + escapeHtml(item.value != null ? item.value : '—') + '</dd>';
        });
        html += '</dl>';
        return html;
    }

    function buildChunkTableHtml(chunks) {
        if (!chunks.length) {
            return '<div class="chunk-empty">No chunk records available for this review.</div>';
        }
        var rows = chunks.map(function(chunk, index) {
            var status = chunk.success ? 'Success' : (chunk.modelNotFound ? 'Model Missing' : 'Failed');
            var errorHtml = chunk.lastError ? '<br><span class="chunk-error">' + escapeHtml(chunk.lastError) + '</span>' : '';
            return '<tr>' +
                '<td>' + (index + 1) + '</td>' +
                '<td>' + escapeHtml(chunk.chunkId || '—') + '</td>' +
                '<td>' + escapeHtml(chunk.role || '—') + '</td>' +
                '<td>' + escapeHtml(chunk.model || '—') + '</td>' +
                '<td>' + formatAttempts(chunk.attempts, chunk.retries) + '</td>' +
                '<td>' + formatDurationMs(chunk.durationMs) + '</td>' +
                '<td>' + escapeHtml(status) + errorHtml + '</td>' +
            '</tr>';
        }).join('');

        return '<table class="aui aui-table aui-table-rowhover detail-chunk-table">' +
            '<thead><tr>' +
                '<th>#</th>' +
                '<th>Chunk</th>' +
                '<th>Role</th>' +
                '<th>Model</th>' +
                '<th>Attempts</th>' +
                '<th>Duration</th>' +
                '<th>Status</th>' +
            '</tr></thead>' +
            '<tbody>' + rows + '</tbody>' +
        '</table>';
    }

    function scrollToRow($row) {
        if (!$row || !$row.length) {
            return;
        }
        var top = $row.offset().top - 80;
        $('html, body').animate({ scrollTop: top }, 200);
    }

    function parseDateTime(value) {
        if (!value) {
            return null;
        }
        var date = new Date(value);
        var time = date.getTime();
        return isNaN(time) ? null : time;
    }

    function renderPagination() {
        var $links = $('#history-pagination');
        if (!$links.length) {
            return;
        }
        if (pagination.all) {
            $links.hide();
            return;
        }
        $links.show();
        var hasPrev = typeof pagination.prevOffset === 'number';
        var hasNext = typeof pagination.nextOffset === 'number';

        $links.find('#history-prev')
            .toggleClass('disabled', !hasPrev)
            .off('click')
            .on('click', function(e) {
                e.preventDefault();
                if (hasPrev) {
                    loadHistory(pagination.prevOffset);
                }
            });

        $links.find('#history-next')
            .toggleClass('disabled', !hasNext)
            .off('click')
            .on('click', function(e) {
                e.preventDefault();
                if (hasNext) {
                    loadHistory(pagination.nextOffset);
                }
            });

        var start = pagination.offset + 1;
        var end = Math.min(pagination.offset + pagination.limit, pagination.total);
        if (pagination.total === 0) {
            start = 0;
            end = 0;
        }
        $links.find('#history-range').text(start + ' - ' + end + ' of ' + pagination.total);
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
