(function($) {
    'use strict';

    var baseUrl = AJS.$('meta[name="application-base-url"]').attr('content') ||
        AJS.$('meta[name="ajs-context-path"]').attr('content') ||
        window.location.origin + (AJS.contextPath() || '');

    var runtimeUrl = baseUrl + '/rest/ai-reviewer/1.0/monitoring/runtime';
    var queueAdminUrl = baseUrl + '/rest/ai-reviewer/1.0/progress/admin/queue';
    var cleanupUrl = baseUrl + '/rest/ai-reviewer/1.0/history/cleanup';

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
        loadHealth();
    }

    function loadHealth(options) {
        options = options || {};
        var silent = !!options.silent;
        if (!silent) {
            setHealthMessage('info', 'Loading telemetry…', true);
        }
        var runtimeRequest = $.ajax({ url: runtimeUrl, type: 'GET', dataType: 'json' });
        var queueRequest = $.ajax({ url: queueAdminUrl, type: 'GET', dataType: 'json' });

        $.when(runtimeRequest, queueRequest).done(function(runtimeResp, queueResp) {
            renderRuntime(runtimeResp[0]);
            renderQueue(queueResp[0]);
            if (!silent) {
                clearHealthMessage();
            }
        }).fail(function(xhr, status, error) {
            setHealthMessage('error', 'Failed to load telemetry: ' + (error || status), false);
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
        renderCleanup(retention.schedule || {}, null);
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

    function renderCleanup(status, latestResult) {
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
                renderCleanup(resp.status, runNow ? resp.result : null);
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

    function clearHealthMessage() {
        setHealthMessage(null, null, false);
    }

    $(document).ready(init);

})(AJS.$);
