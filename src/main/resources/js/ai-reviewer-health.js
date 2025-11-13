(function($) {
    'use strict';

    var baseUrl = AJS.$('meta[name="application-base-url"]').attr('content') ||
        AJS.$('meta[name="ajs-context-path"]').attr('content') ||
        window.location.origin + (AJS.contextPath() || '');
    var Common = window.AIReviewer && window.AIReviewer.Common;

    var runtimeUrl = baseUrl + '/rest/ai-reviewer/1.0/monitoring/runtime';
    var alertsUrl = baseUrl + '/rest/ai-reviewer/1.0/alerts';
    var deliveriesUrl = baseUrl + '/rest/ai-reviewer/1.0/automation/alerts/deliveries';
    var metricsUrl = baseUrl + '/rest/ai-reviewer/1.0/metrics';

    function init() {
        $('#refresh-health-btn').on('click', function() {
            loadHealth();
            loadMetricsSummary();
        });
        loadHealth();
        loadMetricsSummary();
    }

    function loadHealth() {
        setHealthMessage('info', 'Loading telemetry…', true);
        var runtimeRequest = $.ajax({ url: runtimeUrl, type: 'GET', dataType: 'json' });
        var alertsRequest = $.ajax({ url: alertsUrl, type: 'GET', dataType: 'json' });
        var deliveriesRequest = $.ajax({ url: deliveriesUrl + '?limit=0&offset=0', type: 'GET', dataType: 'json' });

        $.when(runtimeRequest, alertsRequest, deliveriesRequest).done(function(runtimeResp, alertsResp, deliveriesResp) {
            renderRuntime(runtimeResp[0]);
            renderAlerts(alertsResp[0]);
            var aggregates = deliveriesResp[0] && deliveriesResp[0].aggregates ? deliveriesResp[0].aggregates : {};
            renderDeliveryMetrics(aggregates);
            clearHealthMessage();
        }).fail(function(xhr, status, error) {
            setHealthMessage('error', describeError(xhr, error || status), false);
        });
    }

    function loadMetricsSummary() {
        $('#metrics-summary-note').text('Loading metrics summary…');
        $.ajax({
            url: metricsUrl,
            type: 'GET',
            dataType: 'json'
        }).done(function(resp) {
            renderMetricsSummary(resp || {});
            $('#metrics-summary-note').text('');
        }).fail(function(xhr, status, error) {
            $('#metrics-summary-note').text('Failed to load metrics summary: ' + describeError(xhr, error || status));
            clearMetricCards();
        });
    }

    function renderRuntime(data) {
        data = data || {};
        var queue = data.queue || {};
        var worker = data.workerPool || {};
        var limiter = data.rateLimiter || {};
        var retention = data.retention || {};
        var cleanup = data.cleanupSchedule || {};

        $('#health-queue-active').text(valueOrDash(queue.active));
        var waiting = valueOrDash(queue.waiting);
        var slots = queue.maxConcurrent != null && queue.active != null
            ? Math.max(0, queue.maxConcurrent - queue.active)
            : '—';
        $('#health-queue-note').text('Waiting ' + waiting + ' • Slots ' + slots);
        $('#health-updated').text(queue.capturedAt ? formatTimestamp(queue.capturedAt) : '—');

        $('#health-worker-active').text(valueOrDash(worker.activeThreads));
        var workerQueued = valueOrDash(worker.queuedTasks);
        var workerSize = valueOrDash(worker.configuredSize);
        $('#health-worker-note').text('Queued ' + workerQueued + ' • Size ' + workerSize);

        $('#health-rate-limit').text(valueOrDash(limiter.repoLimitPerHour));
        var trackedRepo = valueOrDash(limiter.trackedRepoBuckets);
        var trackedProject = valueOrDash(limiter.trackedProjectBuckets);
        $('#health-rate-note').text('Repo buckets ' + trackedRepo + ' • Project buckets ' + trackedProject);

        $('#health-retention-total').text(valueOrDash(retention.totalEntries));
        var retentionDays = valueOrDash(retention.retentionDays);
        var older = valueOrDash(retention.entriesOlderThanRetention);
        var cutoff = retention.cutoffEpochMs ? formatTimestamp(retention.cutoffEpochMs) : '—';
        $('#health-retention-note').text('Older than ' + retentionDays + 'd: ' + older + ' • Cutoff ' + cutoff);

        renderCleanupCard(cleanup);

        renderWorkerNodes(data.workerPoolNodes || []);
        renderScalingHints(data.scalingHints || [], data.generatedAt);
        renderHealthTimeline(data.healthTimeline || {});
    }

    function renderCleanupCard(cleanup) {
        cleanup = cleanup || {};
        var statusText = cleanup.enabled === false ? 'Disabled' : 'Enabled';
        $('#health-cleanup-status').text(statusText);
        var lastRun = cleanup.lastRun ? formatTimestamp(cleanup.lastRun) : 'Never';
        var duration = cleanup.lastDurationMs ? formatDurationMs(cleanup.lastDurationMs) : '—';
        var deletedHistories = valueOrDash(cleanup.lastDeletedHistories);
        var deletedChunks = valueOrDash(cleanup.lastDeletedChunks);
        var batches = valueOrDash(cleanup.lastBatchesExecuted);
        var windowStart = cleanup.windowStartHour != null ? formatWindowHour(cleanup.windowStartHour) : '—';
        var windowDuration = cleanup.windowDurationMinutes != null ? cleanup.windowDurationMinutes + ' min' : '—';
        var noteParts = [
            'Last run ' + lastRun,
            'Duration ' + duration,
            'Deleted ' + deletedHistories + '/' + deletedChunks
        ];
        if (batches !== '—') {
            noteParts.push('Batches ' + batches);
        }
        noteParts.push('Window ' + windowStart + ' • ' + windowDuration);
        if (cleanup.lastError) {
            noteParts.push('Error ' + cleanup.lastError);
        }
        $('#health-cleanup-note').text(noteParts.join(' • '));
    }

    function renderWorkerNodes(nodes) {
        nodes = nodes || [];
        var table = $('#worker-nodes-table');
        var tbody = table.find('tbody');
        var empty = $('#worker-nodes-empty');
        if (!nodes.length) {
            table.hide();
            empty.show();
            $('#worker-nodes-updated').text('—');
            return;
        }
        tbody.empty();
        nodes.sort(function(a, b) {
            return (b.capturedAt || 0) - (a.capturedAt || 0);
        }).forEach(function(node) {
            var label = (node.nodeName || node.nodeId || '—');
            var suffix = '';
            if (node.nodeId && node.nodeName && node.nodeName !== node.nodeId) {
                suffix = ' (' + node.nodeId + ')';
            } else if (!node.nodeName && node.nodeId) {
                label = node.nodeId;
            }
            var staleBadge = node.stale ? '<span class="aui-lozenge aui-lozenge-subtle">STALE</span>' : '';
            var utilization = node.utilization != null
                ? Math.round(Math.max(0, Math.min(1, node.utilization)) * 100) + '%'
                : '—';
            var updated = node.capturedAt ? formatTimestamp(node.capturedAt) : '—';
            var row = '<tr>' +
                '<td>' + escapeHtml(label) + escapeHtml(suffix) + ' ' + staleBadge + '</td>' +
                '<td>' + valueOrDash(node.configuredSize) + '</td>' +
                '<td>' + valueOrDash(node.activeThreads) + '</td>' +
                '<td>' + valueOrDash(node.queuedTasks) + '</td>' +
                '<td>' + utilization + '</td>' +
                '<td>' + updated + '</td>' +
                '</tr>';
            tbody.append(row);
        });
        var latest = nodes[0] && nodes[0].capturedAt ? formatTimestamp(nodes[0].capturedAt) : '—';
        $('#worker-nodes-updated').text(latest);
        empty.hide();
        table.show();
    }

    function renderScalingHints(hints, generatedAt) {
        hints = hints || [];
        var list = $('#scaling-hints-list');
        var empty = $('#scaling-hints-empty');
        if (!hints.length) {
            list.hide();
            empty.show();
            $('#scaling-hints-updated').text('—');
            return;
        }
        list.empty();
        hints.forEach(function(hint) {
            var severity = (hint.severity || 'info').toLowerCase();
            var label = severity.charAt(0).toUpperCase() + severity.slice(1);
            var lozengeClass = 'aui-lozenge aui-lozenge-subtle';
            if (severity === 'critical') {
                lozengeClass = 'aui-lozenge aui-lozenge-error';
            } else if (severity === 'warning') {
                lozengeClass = 'aui-lozenge';
            }
            var recommendation = hint.recommendation ? '<div class="queue-meta">' + escapeHtml(hint.recommendation) + '</div>' : '';
            var detail = hint.detail ? '<div class="queue-meta">' + escapeHtml(hint.detail) + '</div>' : '';
            var item = '<li>' +
                '<div class="health-hint">' +
                '<span class="' + lozengeClass + '">' + escapeHtml(label) + '</span> ' +
                '<strong>' + escapeHtml(hint.summary || 'Scaling hint') + '</strong>' +
                detail +
                recommendation +
                '</div>' +
                '</li>';
            list.append(item);
        });
        $('#scaling-hints-updated').text(generatedAt ? formatTimestamp(generatedAt) : '—');
        empty.hide();
        list.show();
    }

    function renderDeliveryMetrics(aggregates) {
        aggregates = aggregates || {};
        $('#delivery-metrics-updated').text(new Date().toLocaleString());
        $('#delivery-metrics-samples').text(valueOrDash(aggregates.samples));
        $('#delivery-metrics-failures').text(valueOrDash(aggregates.failures));
        if (aggregates.failureRate != null) {
            var percent = Math.round(aggregates.failureRate * 1000) / 10;
            $('#delivery-metrics-rate').text(percent + '%');
        } else {
            $('#delivery-metrics-rate').text('—');
        }
        $('#delivery-metrics-tests').text(valueOrDash(aggregates.tests));
    }

    function renderAlerts(payload) {
        payload = payload || {};
        var alerts = payload.alerts || [];
        var count = alerts.length;
        $('#alerts-updated').text(payload.generatedAt ? formatTimestamp(payload.generatedAt) : new Date().toLocaleString());
        var $table = $('#alerts-table');
        var $empty = $('#alerts-empty');
        if (!alerts.length) {
            $table.hide();
            $table.find('tbody').empty();
            $empty.show();
            return;
        }
        var rows = alerts.map(function(alert) {
            var severity = (alert.severity || 'info').toLowerCase();
            var lozengeClass = 'aui-lozenge aui-lozenge-subtle';
            if (severity === 'critical') {
                lozengeClass = 'aui-lozenge aui-lozenge-error';
            } else if (severity === 'warning') {
                lozengeClass = 'aui-lozenge';
            }
            return '<tr>' +
                '<td><span class="' + lozengeClass + '">' + escapeHtml(alert.severity || 'info') + '</span></td>' +
                '<td>' + escapeHtml(alert.summary || '—') + '</td>' +
                '<td>' + escapeHtml(alert.detail || '—') + '</td>' +
                '<td>' + escapeHtml(alert.recommendation || '—') + '</td>' +
                '</tr>';
        }).join('');
        $table.find('tbody').html(rows);
        $table.show();
        $empty.hide();
        $('#alerts-note').text(count + (count === 1 ? ' alert loaded.' : ' alerts loaded.'));
    }

    function renderHealthTimeline(timeline) {
        timeline = timeline || {};
        var events = [];
        (timeline.queueActions || []).forEach(function(action) {
            events.push({
                type: 'Queue',
                severity: action.action || 'event',
                summary: describeQueueAction(action),
                detail: action.note || '',
                timestamp: action.timestamp || 0
            });
        });
        (timeline.rateLimitIncidents || []).forEach(function(incident) {
            events.push({
                type: 'Limiter',
                severity: 'warning',
                summary: describeRateIncident(incident),
                detail: incident.reason || '',
                timestamp: incident.occurredAt || 0
            });
        });
        (timeline.alertDeliveries || []).forEach(function(delivery) {
            events.push({
                type: delivery.test ? 'Alert (test)' : 'Alert',
                severity: delivery.success ? (delivery.acknowledged ? 'info' : 'warning') : 'critical',
                summary: describeAlertDelivery(delivery),
                detail: delivery.ackUserDisplayName ? 'Ack by ' + escapeHtml(delivery.ackUserDisplayName) : '',
                timestamp: delivery.deliveredAt || 0
            });
        });
        events.sort(function(a, b) {
            return (b.timestamp || 0) - (a.timestamp || 0);
        });
        var $table = $('#health-timeline-table');
        var $empty = $('#health-timeline-empty');
        if (!events.length) {
            $table.hide();
            $table.find('tbody').empty();
            $empty.text('No recent health events.').show();
            $('#health-timeline-updated').text(new Date().toLocaleString());
            return;
        }
        var rows = events.slice(0, 30).map(function(event) {
            var badge = '<span class="timeline-type">' + escapeHtml(event.type) + '</span>';
            var summary = escapeHtml(event.summary || '—');
            var detail = escapeHtml(event.detail || '—');
            return '<tr>' +
                '<td>' + escapeHtml(formatTimestamp(event.timestamp)) + '</td>' +
                '<td>' + badge + '</td>' +
                '<td>' + summary + '</td>' +
                '<td>' + detail + '</td>' +
                '</tr>';
        }).join('');
        $table.find('tbody').html(rows);
        $table.show();
        $empty.hide();
        $('#health-timeline-updated').text(new Date().toLocaleString());
    }

    function describeQueueAction(action) {
        var summary = action.action ? action.action.replace(/[_-]/g, ' ') : 'Queue action';
        var scope = [];
        if (action.projectKey) {
            scope.push(action.projectKey);
        }
        if (action.repositorySlug) {
            scope.push(action.repositorySlug);
        }
        if (action.pullRequestId != null) {
            scope.push('#' + action.pullRequestId);
        }
        if (scope.length) {
            summary += ' · ' + scope.join('/');
        }
        return summary;
    }

    function describeRateIncident(incident) {
        var scope = incident.scope || 'REPOSITORY';
        var id = incident.identifier || incident.projectKey || incident.repositorySlug || 'unknown';
        var summary = 'Throttle (' + scope.toLowerCase() + ') for ' + id;
        if (incident.retryAfterMs != null) {
            summary += ' · retry ' + formatDurationSeconds(Math.round((incident.retryAfterMs || 0) / 1000));
        }
        return summary;
    }

    function describeAlertDelivery(delivery) {
        var summary = delivery.channelDescription || delivery.channelUrl || 'Channel ' + delivery.id;
        if (!delivery.success) {
            summary += ' · delivery failed';
        } else if (!delivery.acknowledged) {
            summary += ' · awaiting ack';
        } else {
            summary += ' · acknowledged';
        }
        return summary;
    }

    function renderMetricsSummary(payload) {
        var metrics = Array.isArray(payload.metrics) ? payload.metrics : [];
        var thresholds = payload.alertThresholds || {};
        var generatedAt = payload.generatedAt || (payload.runtime && payload.runtime.generatedAt);
        $('#metrics-summary-updated').text(generatedAt ? formatTimestamp(generatedAt) : new Date().toLocaleString());

        setMetricCard('queue-waiting', metricValue(metrics, 'ai.queue.waiting'), 'count', thresholds['ai.queue.waiting']);
        setMetricCard('queue-slots', metricValue(metrics, 'ai.queue.availableSlots'), 'count', thresholds['ai.queue.availableSlots']);
        setMetricCard('worker-queued', metricValue(metrics, 'ai.worker.queuedTasks'), 'count', thresholds['ai.worker.queuedTasks']);
        setMetricCard('limiter-throttles', metricValue(metrics, 'ai.rateLimiter.totalThrottles'), 'events', thresholds['ai.rateLimiter.totalThrottles']);
        setMetricCard('breaker-open', metricValue(metrics, 'ai.breaker.openSampleRatio'), 'ratio', thresholds['ai.breaker.openSampleRatio']);
        setMetricCard('cleanup-age', metricValue(metrics, 'ai.retention.cleanup.lastRunAgeSeconds'), 'seconds', thresholds['ai.retention.cleanup.lastRunAgeSeconds']);
    }

    function metricValue(metrics, name) {
        for (var i = 0; i < metrics.length; i++) {
            if (metrics[i] && metrics[i].name === name) {
                return metrics[i].value;
            }
        }
        return null;
    }

    function setMetricCard(key, value, unit, threshold) {
        var $value = $('#metric-' + key + '-value');
        var $status = $('#metric-' + key + '-status');
        var $card = $('#metric-' + key + '-card');
        var displayValue = formatMetricValue(value, unit);
        $value.text(displayValue);
        var status = determineThresholdStatus(value, threshold);
        applyMetricStatus($card, $status, status, threshold);
    }

    function formatMetricValue(value, unit) {
        if (value === null || value === undefined || value !== value) {
            return '—';
        }
        if (unit === 'ratio') {
            return Math.round(value * 1000) / 10 + '%';
        }
        if (unit === 'seconds') {
            return formatDurationSeconds(value);
        }
        return value;
    }

    function determineThresholdStatus(value, threshold) {
        if (!threshold || value === null || value === undefined || value !== value) {
            return 'normal';
        }
        var warning = threshold.warning;
        var critical = threshold.critical;
        var direction = (threshold.direction || 'gte').toLowerCase();
        if (compareAgainstThreshold(value, critical, direction)) {
            return 'critical';
        }
        if (compareAgainstThreshold(value, warning, direction)) {
            return 'warning';
        }
        return 'normal';
    }

    function compareAgainstThreshold(value, target, direction) {
        if (target == null) {
            return false;
        }
        if (direction === 'lte') {
            return value <= target;
        }
        if (direction === 'eq') {
            return value === target;
        }
        return value >= target;
    }

    function applyMetricStatus($card, $status, status, threshold) {
        var label;
        var css;
        if (status === 'critical') {
            label = 'Critical';
            css = 'aui-lozenge aui-lozenge-error';
        } else if (status === 'warning') {
            label = 'Warning';
            css = 'aui-lozenge';
        } else {
            label = 'Normal';
            css = 'aui-lozenge aui-lozenge-success';
        }
        if ($status.length) {
            $status.empty().append('<span class=\"' + css + '\">' + label + '</span>');
            if (threshold && threshold.description) {
                $status.attr('title', threshold.description);
            } else {
                $status.removeAttr('title');
            }
        }
        if ($card.length) {
            $card.removeClass('metric-status-warning metric-status-critical metric-status-normal');
            $card.addClass('metric-status-' + status);
        }
    }

    function clearMetricCards() {
        var keys = ['queue-waiting', 'queue-slots', 'worker-queued', 'limiter-throttles', 'breaker-open', 'cleanup-age'];
        keys.forEach(function(key) {
            $('#metric-' + key + '-value').text('—');
            $('#metric-' + key + '-status').text('—');
            $('#metric-' + key + '-card')
                .removeClass('metric-status-warning metric-status-critical metric-status-normal')
                .addClass('metric-status-normal');
        });
    }

    function setHealthMessage(type, message, showSpinner) {
        var $message = $('#health-message');
        if (!message) {
            $message.hide().text('').removeClass('info error');
            return;
        }
        var css = type === 'error' ? 'error' : 'info';
        $message.removeClass('info error').addClass(css);
        var content = showSpinner ? '<span class="aui-icon aui-icon-wait"></span> ' + escapeHtml(message) : escapeHtml(message);
        $message.html(content).show();
    }

    function clearHealthMessage() {
        setHealthMessage(null, null, false);
    }

    function valueOrDash(value) {
        if (value === null || value === undefined || value !== value) {
            return '—';
        }
        return value;
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

    function formatDurationMs(ms) {
        if (ms === null || ms === undefined || ms !== ms) {
            return '—';
        }
        return formatDurationSeconds(Math.max(0, Math.round(ms / 1000)));
    }

    function formatDurationSeconds(seconds) {
        if (seconds === null || seconds === undefined || seconds !== seconds) {
            return '—';
        }
        var value = Math.max(0, seconds);
        if (value < 60) {
            return value + 's';
        }
        var minutes = Math.floor(value / 60);
        var remSeconds = Math.floor(value % 60);
        if (minutes < 60) {
            return minutes + 'm ' + remSeconds + 's';
        }
        var hours = Math.floor(minutes / 60);
        var remMinutes = minutes % 60;
        if (hours < 24) {
            return hours + 'h ' + remMinutes + 'm';
        }
        var days = Math.floor(hours / 24);
        var remHours = hours % 24;
        return days + 'd ' + remHours + 'h';
    }

    function formatWindowHour(hour) {
        if (hour === null || hour === undefined || hour !== hour) {
            return '—';
        }
        var normalized = parseInt(hour, 10);
        if (isNaN(normalized)) {
            return '—';
        }
        if (normalized < 0) {
            normalized = 0;
        }
        normalized = normalized % 24;
        return (normalized < 10 ? '0' + normalized : normalized) + ':00';
    }

    function formatTimestamp(epochMillis) {
        if (!epochMillis) {
            return '—';
        }
        var date = new Date(epochMillis);
        return isNaN(date.getTime()) ? '—' : date.toLocaleString();
    }

    function describeError(xhr, fallback) {
        if (Common && typeof Common.composeErrorDetails === 'function') {
            return Common.composeErrorDetails(xhr, fallback);
        }
        var message = fallback || 'Request failed';
        if (xhr && xhr.responseJSON && xhr.responseJSON.error) {
            message = xhr.responseJSON.error;
        } else if (xhr && xhr.responseText) {
            message = xhr.responseText;
        }
        return message;
    }

    $(document).ready(init);

})(AJS.$);
