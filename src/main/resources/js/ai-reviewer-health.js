(function($) {
    'use strict';

    var baseUrl = AJS.$('meta[name="application-base-url"]').attr('content') ||
        AJS.$('meta[name="ajs-context-path"]').attr('content') ||
        window.location.origin + (AJS.contextPath() || '');
    var Common = window.AIReviewer && window.AIReviewer.Common;

    var runtimeUrl = baseUrl + '/rest/ai-reviewer/1.0/monitoring/runtime';
    var queueAdminUrl = baseUrl + '/rest/ai-reviewer/1.0/progress/admin/queue';
    var cleanupUrl = baseUrl + '/rest/ai-reviewer/1.0/history/cleanup';
    var alertsUrl = baseUrl + '/rest/ai-reviewer/1.0/alerts';
    var automationBaseUrl = baseUrl + '/rest/ai-reviewer/1.0/automation';
    var rolloutStateUrl = automationBaseUrl + '/rollout/state';
    var channelsUrl = automationBaseUrl + '/channels';
    var deliveriesUrl = automationBaseUrl + '/alerts/deliveries';
    var channelListLimit = 200;

    function init() {
        $('#refresh-health-btn').on('click', function() {
            loadHealth();
        });
        $('#cleanup-form').on('submit', function(event) {
            event.preventDefault();
            submitCleanup(false);
        });
        $('#cleanup-run-btn').on('click', function(event) {
            event.preventDefault();
            submitCleanup(true);
        });
        $('.rollout-btn').on('click', function(event) {
            event.preventDefault();
            changeRolloutState($(this).data('mode'));
        });
        $('#channel-create-form').on('submit', function(event) {
            event.preventDefault();
            createChannel();
        });
        $('#channels-table').on('click', '.channel-toggle', function(event) {
            event.preventDefault();
            var enabled = $(this).data('enabled');
            var isEnabled = enabled === true || enabled === 'true' || enabled === 1;
            toggleChannel($(this).data('id'), isEnabled);
        });
        $('#channels-table').on('click', '.channel-delete', function(event) {
            event.preventDefault();
            var label = $(this).closest('tr').find('td:first').text();
            if (!label) {
                label = $(this).closest('tr').find('td:nth-child(2)').text();
            }
            deleteChannel($(this).data('id'), label);
        });
        $('#channels-table').on('click', '.channel-test', function(event) {
            event.preventDefault();
            testChannel($(this).data('id'));
        });
        $('#channels-table').on('click', '.channel-rotate', function(event) {
            event.preventDefault();
            rotateChannel($(this).data('id'));
        });
        $('#deliveries-table').on('click', '.delivery-ack', function(event) {
            event.preventDefault();
            acknowledgeDelivery($(this).data('id'));
        });
        loadHealth();
        loadAutomationData();
    }

    function loadHealth(options) {
        options = options || {};
        var silent = !!options.silent;
        if (!silent) {
            setHealthMessage('info', 'Loading telemetry…', true);
        }
        var runtimeRequest = $.ajax({ url: runtimeUrl, type: 'GET', dataType: 'json' });
        var queueRequest = $.ajax({ url: queueAdminUrl, type: 'GET', dataType: 'json' });
        var alertsRequest = $.ajax({ url: alertsUrl, type: 'GET', dataType: 'json' });

        $.when(runtimeRequest, queueRequest, alertsRequest).done(function(runtimeResp, queueResp, alertsResp) {
            renderRuntime(runtimeResp[0]);
            renderQueue(queueResp[0]);
            renderAlerts(alertsResp[0]);
            if (!silent) {
                clearHealthMessage();
            }
        }).fail(function(xhr, status, error) {
            setHealthMessage('error', 'Failed to load telemetry: ' + (error || status), false);
        });
    }

    function loadAutomationData() {
        loadRolloutState();
        loadChannels();
        loadDeliveries();
    }

    function loadRolloutState() {
        $.ajax({
            url: rolloutStateUrl,
            type: 'GET',
            dataType: 'json'
        }).done(function(resp) {
            renderRollout(resp || {});
            setRolloutMessage();
        }).fail(function(xhr) {
            setRolloutMessage('error', describeError(xhr, 'Failed to load scheduler state'));
        });
    }

    function loadChannels() {
        $.ajax({
            url: channelsUrl + '?limit=' + channelListLimit + '&offset=0',
            type: 'GET',
            dataType: 'json'
        }).done(function(resp) {
            renderChannels(resp || {});
            setChannelMessage();
        }).fail(function(xhr) {
            setChannelMessage('error', describeError(xhr, 'Failed to load alert channels'));
        });
    }

    function loadDeliveries() {
        $.ajax({
            url: deliveriesUrl + '?limit=50&offset=0',
            type: 'GET',
            dataType: 'json'
        }).done(function(resp) {
            renderDeliveries(resp || {});
            renderDeliveryMetrics(resp && resp.aggregates);
            setDeliveriesMessage();
        }).fail(function(xhr) {
            setDeliveriesMessage('error', describeError(xhr, 'Failed to load alert deliveries'));
        });
    }

    function renderRuntime(data) {
        data = data || {};
        var queue = data.queue || {};
        var worker = data.workerPool || {};
        var limiter = data.rateLimiter || {};
        var retention = data.retention || {};

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
        renderCleanup(retention.schedule || {}, retention.recentRuns || [], null);
    }

    function renderQueue(data) {
        data = data || {};
        var stats = data.queue || {};
        $('#queue-active').text(valueOrDash(stats.active));
        $('#queue-available').text(valueOrDash(Math.max(0, stats.maxConcurrent - stats.active)));
        $('#queue-waiting').text(valueOrDash(stats.waiting));
        $('#queue-max-concurrent').text(valueOrDash(stats.maxConcurrent));
        $('#queue-max-queued').text(valueOrDash(stats.maxQueued));
        $('#queue-updated').text(stats.capturedAt ? formatTimestamp(stats.capturedAt) : '—');
        var waiting = stats.waiting || 0;
        $('#queue-note').text(waiting > 0 ? 'Waiting ' + waiting + ' review' + (waiting === 1 ? '' : 's') : 'Queue is empty');

        var entries = convertQueueEntries(data.entries || []);
        renderQueueEntries(entries);
        renderQueueActions(convertQueueActions(data.actions || []));
    }

    function convertQueueEntries(entries) {
        if (!entries || !entries.length) {
            return [];
        }
        var now = Date.now();
        return entries.map(function(entry, index) {
            var waitingMs = entry.waitingSince ? Math.max(0, now - entry.waitingSince) : 0;
            var estimatedStart = entry.estimatedStartMs || (entry.waitingSince || now);
            return {
                position: index + 1,
                runId: entry.runId,
                projectKey: entry.projectKey,
                repositorySlug: entry.repositorySlug,
                pullRequestId: entry.pullRequestId,
                manual: entry.manual,
                update: entry.update,
                force: entry.force,
                requestedBy: entry.requestedBy || '—',
                waitingMs: waitingMs,
                estimatedStartMs: estimatedStart,
                repoWaiting: entry.repoWaiting,
                projectWaiting: entry.projectWaiting,
                backpressureReason: entry.backpressureReason
            };
        });
    }

    function convertQueueActions(actions) {
        if (!actions || !actions.length) {
            return [];
        }
        return actions.map(function(action) {
            return {
                timestamp: action.timestamp,
                action: action.action,
                actor: action.actor || 'system',
                runId: action.runId,
                projectKey: action.projectKey,
                repositorySlug: action.repositorySlug,
                pullRequestId: action.pullRequestId,
                manual: action.manual,
                update: action.update,
                force: action.force,
                note: action.note
            };
        });
    }

    function renderQueueEntries(entries) {
        var $table = $('#queue-entries-table');
        var $empty = $('#queue-entries-empty');
        var $count = $('#queue-entries-count');
        if (!entries.length) {
            $table.hide();
            $table.find('tbody').empty();
            $empty.text('No queued reviews.').show();
            $count.text('');
            return;
        }
        var rows = entries.map(function(entry) {
            var scope = 'Repo ' + valueOrDash(entry.repoWaiting) + ' / Project ' + valueOrDash(entry.projectWaiting);
            if (entry.backpressureReason) {
                scope += ' (' + entry.backpressureReason + ')';
            }
            return '<tr>' +
                '<td>' + entry.position + '</td>' +
                '<td>' + escapeHtml(formatRepo(entry)) + '</td>' +
                '<td>' + escapeHtml(entry.requestedBy) + '</td>' +
                '<td>' + escapeHtml(formatDurationMs(entry.waitingMs)) + '</td>' +
                '<td>' + escapeHtml(scope) + '</td>' +
                '</tr>';
        }).join('');
        $table.find('tbody').html(rows);
        $table.show();
        $empty.hide();
        $count.text(entries.length + (entries.length === 1 ? ' queued item' : ' queued items'));
    }

    function renderQueueActions(actions) {
        var $table = $('#queue-actions-table');
        var $empty = $('#queue-actions-empty');
        var $count = $('#queue-actions-count');
        if (!actions.length) {
            $table.hide();
            $table.find('tbody').empty();
            $empty.text('No recent queue actions.').show();
            $count.text('');
            return;
        }
        var rows = actions.map(function(action) {
            var meta = [];
            if (action.manual) {
                meta.push('manual');
            }
            if (action.update) {
                meta.push('update');
            }
            if (action.force) {
                meta.push('force');
            }
            var metaLabel = meta.length ? '<div class="queue-meta">' + meta.join(', ') + '</div>' : '';
            return '<tr>' +
                '<td>' + escapeHtml(formatTimestamp(action.timestamp)) + '</td>' +
                '<td>' + escapeHtml(formatStageLabel(action.action)) + '</td>' +
                '<td>' + escapeHtml(action.actor) + '</td>' +
                '<td>' + escapeHtml(formatActionTarget(action)) + metaLabel + '</td>' +
                '<td>' + escapeHtml(action.note || '—') + '</td>' +
                '</tr>';
        }).join('');
        $table.find('tbody').html(rows);
        $table.show();
        $empty.hide();
        $count.text(actions.length + (actions.length === 1 ? ' action' : ' actions'));
    }

    function renderCleanup(status, recentRuns, latestResult) {
        status = status || {};
        setFieldValue('#cleanup-retention-days', status.retentionDays);
        setFieldValue('#cleanup-batch-size', status.batchSize);
        setFieldValue('#cleanup-interval-minutes', status.intervalMinutes);
        $('#cleanup-enabled').prop('checked', status.enabled !== false);

        var cadence = status.intervalMinutes ? ('Every ' + status.intervalMinutes + ' min') : 'Not scheduled';
        var enabledLabel = status.enabled === false ? 'Disabled' : 'Enabled';
        $('#cleanup-updated').text(enabledLabel + (status.intervalMinutes ? ' • ' + cadence : ''));

        var lastRun = status.lastRun ? formatTimestamp(status.lastRun) : 'Never';
        $('#cleanup-last-run').text(lastRun);
        var duration = status.lastDurationMs ? formatDurationMs(status.lastDurationMs) : '—';
        $('#cleanup-last-run-note').text(enabledLabel + ' • ' + cadence + ' • Duration ' + duration);

        var outcomeValue;
        if (status.lastDeletedHistories != null || status.lastDeletedChunks != null) {
            var histories = status.lastDeletedHistories != null ? status.lastDeletedHistories : '—';
            var chunks = status.lastDeletedChunks != null ? status.lastDeletedChunks : '—';
            outcomeValue = histories + ' histories / ' + chunks + ' chunks';
        } else {
            outcomeValue = 'No data yet';
        }
        $('#cleanup-last-outcome').text(outcomeValue);
        var $outcomeNote = $('#cleanup-last-outcome-note');
        if (status.lastError) {
            $outcomeNote.text('Failed: ' + status.lastError).css('color', '#d04437');
        } else if (status.lastRun) {
            $outcomeNote.text('Last run succeeded').css('color', '');
        } else {
            $outcomeNote.text('Waiting for first run').css('color', '');
        }

        renderCleanupLog(recentRuns);

        if (latestResult) {
            var message = formatCleanupResult(latestResult);
            setCleanupMessage('info', message, false);
            if (latestResult.remainingCandidates != null) {
                var cutoff = latestResult.cutoffEpochMs ? formatTimestamp(latestResult.cutoffEpochMs) : '—';
                $('#health-retention-note').text('Remaining older than ' + latestResult.retentionDays + 'd: ' +
                    valueOrDash(latestResult.remainingCandidates) + ' • Cutoff ' + cutoff);
            }
        }
    }

    function submitCleanup(runNow) {
        var $save = $('#cleanup-save-btn');
        var $run = $('#cleanup-run-btn');
        $save.prop('disabled', true);
        $run.prop('disabled', true);

        var payload = {
            retentionDays: parseIntField('#cleanup-retention-days'),
            batchSize: parseIntField('#cleanup-batch-size'),
            intervalMinutes: parseIntField('#cleanup-interval-minutes'),
            enabled: $('#cleanup-enabled').is(':checked'),
            runNow: runNow
        };

        setCleanupMessage('info', runNow ? 'Running cleanup…' : 'Saving schedule…', true);

        $.ajax({
            url: cleanupUrl,
            type: 'POST',
            contentType: 'application/json',
            dataType: 'json',
            data: JSON.stringify(payload)
        }).done(function(resp) {
            if (resp && resp.status) {
                renderCleanup(resp.status, resp.recentRuns || [], runNow ? resp.result : null);
            }
            if (!runNow) {
                setCleanupMessage('info', 'Cleanup schedule updated.', false);
            } else if (!resp || !resp.result) {
                setCleanupMessage('info', 'Cleanup triggered. Results will appear once it finishes.', false);
            }
            loadHealth({ silent: true });
        }).fail(function(xhr) {
            var message = (xhr && xhr.responseJSON && xhr.responseJSON.error) ||
                (xhr && xhr.responseText) ||
                'Unable to update cleanup schedule';
            setCleanupMessage('error', message, false);
        }).always(function() {
            $save.prop('disabled', false);
            $run.prop('disabled', false);
        });
    }

    function parseIntField(selector) {
        var value = $(selector).val();
        if (value === undefined || value === null || value === '') {
            return null;
        }
        var parsed = parseInt(value, 10);
        return isNaN(parsed) ? null : parsed;
    }

    function setFieldValue(selector, value) {
        var $field = $(selector);
        if (!$field.length) {
            return;
        }
        if (value === undefined || value === null || value !== value) {
            $field.val('');
        } else {
            $field.val(value);
        }
    }

    function setCleanupMessage(type, message, showSpinner) {
        var $message = $('#cleanup-message');
        if (!$message.length) {
            return;
        }
        if (!message) {
            $message.hide().text('').removeClass('info error');
            return;
        }
        $message.removeClass('info error').addClass(type === 'error' ? 'error' : 'info');
        var content = showSpinner
            ? '<span class="aui-icon aui-icon-wait"></span> ' + escapeHtml(message)
            : escapeHtml(message);
        $message.html(content).show();
    }

    function formatCleanupResult(result) {
        if (!result) {
            return '';
        }
        var deletedHistories = valueOrDash(result.deletedHistories);
        var deletedChunks = valueOrDash(result.deletedChunks);
        var remaining = valueOrDash(result.remainingCandidates);
        return 'Deleted ' + deletedHistories + ' histories / ' + deletedChunks +
            ' chunks • Remaining candidates ' + remaining;
    }

    function renderCleanupLog(runs) {
        var $table = $('#cleanup-log-table');
        var $empty = $('#cleanup-log-empty');
        var $count = $('#cleanup-log-count');
        if (!runs || !runs.length) {
            $table.hide();
            $table.find('tbody').empty();
            $empty.text('No cleanup runs recorded yet.').show();
            $count.text('');
            return;
        }
        var rows = runs.map(function(run) {
            var statusLabel = run.success ? 'Success' : 'Failed';
            if (run.manual) {
                statusLabel += ' (manual)';
            }
            var statusHtml = escapeHtml(statusLabel);
            if (!run.success && run.errorMessage) {
                statusHtml += '<div class="queue-meta">Error: ' + escapeHtml(run.errorMessage) + '</div>';
            }
            var deleted = valueOrDash(run.deletedHistories) + ' / ' + valueOrDash(run.deletedChunks);
            var actor = run.actorDisplayName || run.actorUserKey || '—';
            if (run.actorUserKey && run.actorUserKey !== actor) {
                actor += ' (' + run.actorUserKey + ')';
            }
            var duration = run.durationMs ? formatDurationMs(run.durationMs) : '—';
            if (duration === '—' && run.durationMs === 0 && run.success) {
                duration = 'instant';
            }
            return '<tr>' +
                '<td>' + escapeHtml(formatTimestamp(run.runTimestamp)) + '</td>' +
                '<td>' + escapeHtml(duration) + '</td>' +
                '<td>' + escapeHtml(deleted) + '</td>' +
                '<td>' + escapeHtml(actor) + '</td>' +
                '<td>' + statusHtml + '</td>' +
                '</tr>';
        }).join('');
        $table.find('tbody').html(rows);
        $table.show();
        $empty.hide();
        $count.text(runs.length + (runs.length === 1 ? ' run' : ' runs'));
    }

    function renderAlerts(data) {
        data = data || {};
        var alerts = data.alerts || [];
        $('#alerts-updated').text(data.generatedAt ? formatTimestamp(data.generatedAt) : '—');
        var $table = $('#alerts-table');
        var $empty = $('#alerts-empty');
        if (!alerts.length) {
            $table.hide();
            $table.find('tbody').empty();
            $empty.text('No active alerts.').show();
            return;
        }
        var rows = alerts.map(function(alert) {
            var severity = (alert.severity || '').toUpperCase();
            return '<tr>' +
                '<td>' + escapeHtml(severity) + '</td>' +
                '<td>' + escapeHtml(alert.summary || '—') + '</td>' +
                '<td>' + escapeHtml(alert.detail || '—') + '</td>' +
                '<td>' + escapeHtml(alert.recommendation || '—') + '</td>' +
                '</tr>';
        }).join('');
        $table.find('tbody').html(rows);
        $table.show();
        $empty.hide();
    }

    function renderRollout(state) {
        state = state || {};
        var mode = (state.mode || 'UNKNOWN').toUpperCase();
        $('#rollout-mode').text(mode);
        var parts = [];
        if (state.updatedByDisplayName) {
            parts.push('Updated by ' + state.updatedByDisplayName);
        } else if (state.updatedBy) {
            parts.push('Updated by ' + state.updatedBy);
        }
        if (state.reason) {
            parts.push('Reason: ' + state.reason);
        }
        $('#rollout-meta').text(parts.length ? parts.join(' • ') : 'No recent changes recorded.');
        $('#rollout-updated').text(state.updatedAt ? formatTimestamp(state.updatedAt) : '—');
    }

    function renderChannels(payload) {
        payload = payload || {};
        var channels = payload.channels || [];
        var total = payload.total != null ? payload.total : channels.length;
        $('#channels-count').text(total + (total === 1 ? ' channel' : ' channels'));
        var $table = $('#channels-table');
        var $empty = $('#channels-empty');
        if (!channels.length) {
            $table.hide();
            $table.find('tbody').empty();
            $empty.show();
            return;
        }
        var rows = channels.map(function(channel) {
            var statusClass = channel.enabled ? 'channel-status-enabled' : 'channel-status-disabled';
            var statusLabel = channel.enabled ? 'Enabled' : 'Disabled';
            var signingMeta = channel.signRequests
                ? '<div class="queue-meta">Signed (secret: <code>' + escapeHtml(channel.secret || '—') + '</code>)</div>'
                : '<div class="queue-meta">Unsigned</div>';
            var retryMeta = '<div class="queue-meta">Retries ' + escapeHtml(channel.maxRetries) +
                ' • Backoff ' + escapeHtml(channel.retryBackoffSeconds) + 's</div>';
            return '<tr>' +
                '<td>' + escapeHtml(channel.description || '—') + '</td>' +
                '<td><code>' + escapeHtml(channel.url || '—') + '</code>' + signingMeta + retryMeta + '</td>' +
                '<td class="' + statusClass + '">' + statusLabel + '</td>' +
                '<td><div class="channel-actions">' +
                '<button type="button" class="aui-button aui-button-link channel-toggle" data-id="' + channel.id + '" data-enabled="' + !!channel.enabled + '">' +
                (channel.enabled ? 'Disable' : 'Enable') + '</button>' +
                '<button type="button" class="aui-button aui-button-link channel-test" data-id="' + channel.id + '">Test</button>' +
                '<button type="button" class="aui-button aui-button-link channel-rotate" data-id="' + channel.id + '">Rotate Secret</button>' +
                '<button type="button" class="aui-button aui-button-link channel-delete" data-id="' + channel.id + '">Delete</button>' +
                '</div></td>' +
                '</tr>';
        }).join('');
        $table.find('tbody').html(rows);
        $empty.hide();
        $table.show();
    }

    function renderDeliveries(payload) {
        payload = payload || {};
        var deliveries = payload.deliveries || [];
        var total = payload.total != null ? payload.total : deliveries.length;
        $('#deliveries-count').text(total + (total === 1 ? ' delivery' : ' deliveries'));
        var $table = $('#deliveries-table');
        var $empty = $('#deliveries-empty');
        if (!deliveries.length) {
            $table.hide();
            $table.find('tbody').empty();
            $empty.show();
            return;
        }
        var rows = deliveries.map(function(delivery) {
            var statusClass = delivery.success ? 'deliveries-status-success' : 'deliveries-status-failed';
            var statusLabel = delivery.success ? 'Success' : 'Failed';
            if (delivery.test) {
                statusLabel += ' (test)';
            }
            var ackLabel = delivery.acknowledged
                ? 'Ack by ' + (delivery.ackUserDisplayName || delivery.ackUserKey || 'admin')
                : '';
            var errorText = delivery.errorMessage ? escapeHtml(delivery.errorMessage) : '—';
            var actions = delivery.acknowledged
                ? '<span class="aui-lozenge aui-lozenge-success">Acknowledged</span>'
                : '<button type="button" class="aui-button aui-button-link delivery-ack" data-id="' + delivery.id + '">Acknowledge</button>';
            return '<tr>' +
                '<td>' + escapeHtml(formatTimestamp(delivery.deliveredAt)) + '</td>' +
                '<td>' + escapeHtml(delivery.channelDescription || delivery.channelUrl || ('Channel ' + delivery.channelId)) + '</td>' +
                '<td class="' + statusClass + '">' + escapeHtml(statusLabel) +
                (ackLabel ? '<div class="queue-meta">' + escapeHtml(ackLabel) + '</div>' : '') +
                '</td>' +
                '<td>' + (delivery.httpStatus || '—') + '</td>' +
                '<td>' + errorText + '</td>' +
                '<td>' + actions + '</td>' +
                '</tr>';
        }).join('');
        $table.find('tbody').html(rows);
        $empty.hide();
        $table.show();
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

    function changeRolloutState(mode) {
        if (!mode) {
            return;
        }
        var payload = {
            reason: $('#rollout-reason').val() || null
        };
        setRolloutMessage('info', 'Updating scheduler…');
        $.ajax({
            url: automationBaseUrl + '/rollout/' + encodeURIComponent(mode),
            type: 'POST',
            dataType: 'json',
            contentType: 'application/json',
            data: JSON.stringify(payload)
        }).done(function(resp) {
            renderRollout(resp || {});
            setRolloutMessage('success', 'Scheduler set to ' + mode.toUpperCase() + '.');
            loadHealth({ silent: true });
        }).fail(function(xhr) {
            setRolloutMessage('error', describeError(xhr, 'Failed to update scheduler state'));
        });
    }

    function setRolloutMessage(type, message) {
        var $message = $('#rollout-message');
        if (!message) {
            $message.hide().text('');
            return;
        }
        var css = type === 'error' ? 'error' : (type === 'success' ? 'success' : 'info');
        $message.removeClass('error success info').addClass(css).text(message).show();
    }

    function createChannel() {
        var payload = {
            url: $('#channel-url').val(),
            description: $('#channel-description').val(),
            enabled: $('#channel-enabled').is(':checked'),
            signRequests: $('#channel-signing').is(':checked'),
            secret: $('#channel-secret').val() || null,
            maxRetries: parseOptionalInt($('#channel-max-retries').val()),
            retryBackoffSeconds: parseOptionalInt($('#channel-backoff').val())
        };
        var $form = $('#channel-create-form');
        var $submit = $form.find('button[type="submit"]');
        $submit.prop('disabled', true);
        setChannelMessage('info', 'Creating channel…');
        $.ajax({
            url: channelsUrl,
            type: 'POST',
            contentType: 'application/json',
            dataType: 'json',
            data: JSON.stringify(payload)
        }).done(function() {
            setChannelMessage('success', 'Channel created.');
            $form[0].reset();
            $('#channel-enabled').prop('checked', true);
            $('#channel-signing').prop('checked', true);
            $('#channel-max-retries').val('2');
            $('#channel-backoff').val('5');
            loadChannels();
        }).fail(function(xhr) {
            setChannelMessage('error', describeError(xhr, 'Failed to create channel'));
        }).always(function() {
            $submit.prop('disabled', false);
        });
    }

    function toggleChannel(id, enabled) {
        var next = !enabled;
        setChannelMessage('info', (next ? 'Enabling' : 'Disabling') + ' channel…');
        $.ajax({
            url: channelsUrl + '/' + id,
            type: 'PUT',
            contentType: 'application/json',
            dataType: 'json',
            data: JSON.stringify({ enabled: next })
        }).done(function() {
            setChannelMessage('success', 'Channel ' + (next ? 'enabled' : 'disabled') + '.');
            loadChannels();
        }).fail(function(xhr) {
            setChannelMessage('error', describeError(xhr, 'Failed to update channel'));
        });
    }

    function deleteChannel(id, description) {
        var label = description || ('channel #' + id);
        if (!confirm('Delete ' + label + '? Alerts will no longer be sent to this endpoint.')) {
            return;
        }
        setChannelMessage('info', 'Deleting channel…');
        $.ajax({
            url: channelsUrl + '/' + id,
            type: 'DELETE'
        }).done(function() {
            setChannelMessage('success', 'Channel deleted.');
            loadChannels();
        }).fail(function(xhr) {
            setChannelMessage('error', describeError(xhr, 'Failed to delete channel'));
        });
    }

    function testChannel(id) {
        setChannelMessage('info', 'Sending test alert…');
        $.ajax({
            url: channelsUrl + '/' + id + '/test',
            type: 'POST',
            dataType: 'json'
        }).done(function(resp) {
            var delivered = resp && resp.delivered;
            setChannelMessage(delivered ? 'success' : 'error',
                delivered ? 'Test alert delivered successfully.' : 'Test alert failed. Check the remote endpoint.');
        }).fail(function(xhr) {
            setChannelMessage('error', describeError(xhr, 'Failed to send test alert'));
        });
    }

    function rotateChannel(id) {
        if (!confirm('Rotate the signing secret for this channel? Existing integrations must be updated.')) {
            return;
        }
        setChannelMessage('info', 'Rotating secret…');
        $.ajax({
            url: channelsUrl + '/' + id,
            type: 'PUT',
            contentType: 'application/json',
            dataType: 'json',
            data: JSON.stringify({ rotateSecret: true })
        }).done(function() {
            setChannelMessage('success', 'Secret rotated.');
            loadChannels();
        }).fail(function(xhr) {
            setChannelMessage('error', describeError(xhr, 'Failed to rotate secret'));
        });
    }

    function setChannelMessage(type, message) {
        var $message = $('#channel-message');
        if (!message) {
            $message.hide().text('');
            return;
        }
        var css = type === 'error' ? 'error' : (type === 'success' ? 'success' : 'info');
        $message.removeClass('error success info').addClass(css).text(message).show();
    }

    function acknowledgeDelivery(id) {
        var note = window.prompt('Add acknowledgement note (optional):', '');
        if (note === null) {
            return;
        }
        setDeliveriesMessage('info', 'Acknowledging delivery…');
        $.ajax({
            url: deliveriesUrl + '/' + id + '/ack',
            type: 'POST',
            contentType: 'application/json',
            dataType: 'json',
            data: JSON.stringify({ note: note || null })
        }).done(function() {
            setDeliveriesMessage('success', 'Delivery acknowledged.');
            loadDeliveries();
        }).fail(function(xhr) {
            setDeliveriesMessage('error', describeError(xhr, 'Failed to acknowledge delivery'));
        });
    }

    function setDeliveriesMessage(type, message) {
        var $message = $('#deliveries-message');
        if (!message) {
            $message.hide().text('');
            return;
        }
        var css = type === 'error' ? 'error' : (type === 'success' ? 'success' : 'info');
        $message.removeClass('error success info').addClass(css).text(message).show();
    }

    function formatRepo(entry) {
        if (!entry) {
            return '—';
        }
        var repo = (entry.projectKey || '—') + '/' + (entry.repositorySlug || '—');
        var pr = entry.pullRequestId != null ? (' #' + entry.pullRequestId) : '';
        return repo + pr;
    }

    function formatActionTarget(action) {
        if (!action) {
            return '—';
        }
        var repo = '';
        if (action.projectKey && action.repositorySlug) {
            repo = action.projectKey + '/' + action.repositorySlug;
        }
        var pr = action.pullRequestId != null && action.pullRequestId >= 0 ? (' #' + action.pullRequestId) : '';
        var run = action.runId ? (' (' + action.runId + ')') : '';
        var target = (repo + pr).trim();
        if (!target) {
            target = action.runId || '—';
        }
        return (target + run).trim() || '—';
    }

    function formatStageLabel(value) {
        if (value == null) {
            return 'Unknown';
        }
        return String(value)
            .replace(/[_\s-]+/g, ' ')
            .replace(/\b([a-zA-Z])/g, function(match) {
                return match.toUpperCase();
            })
            .trim();
    }

    function formatDurationMs(ms) {
        if (ms == null || isNaN(ms) || ms < 0) {
            return '—';
        }
        var seconds = Math.floor(ms / 1000);
        if (seconds < 60) {
            return seconds + 's';
        }
        var minutes = Math.floor(seconds / 60);
        var hours = Math.floor(minutes / 60);
        var days = Math.floor(hours / 24);
        if (days > 0) {
            var remHours = hours % 24;
            return days + 'd ' + remHours + 'h';
        }
        if (hours > 0) {
            var remMinutes = minutes % 60;
            return hours + 'h ' + remMinutes + 'm';
        }
        var remSeconds = seconds % 60;
        return minutes + 'm ' + remSeconds + 's';
    }

    function formatTimestamp(epochMillis) {
        if (!epochMillis) {
            return '—';
        }
        var date = new Date(epochMillis);
        return isNaN(date.getTime()) ? '—' : date.toLocaleString();
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

    function setHealthMessage(type, message, showSpinner) {
        var $message = $('#health-message');
        if (!message) {
            $message.hide().text('').removeClass('info error');
            return;
        }
        $message.removeClass('info error').addClass(type === 'error' ? 'error' : 'info');
        var content = showSpinner ? '<span class="aui-icon aui-icon-wait"></span> ' + escapeHtml(message) : escapeHtml(message);
        $message.html(content).show();
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

    function clearHealthMessage() {
        setHealthMessage(null, null, false);
    }

    function parseOptionalInt(value) {
        if (value === undefined || value === null || value === '') {
            return null;
        }
        var parsed = parseInt(value, 10);
        return isNaN(parsed) ? null : parsed;
    }

    $(document).ready(init);

})(AJS.$);
