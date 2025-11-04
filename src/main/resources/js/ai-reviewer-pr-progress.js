(function($) {
    'use strict';

    var HISTORY_LIMIT = 10;
    var metrics = {
        cacheHits: 0,
        cacheMisses: 0,
        hydrationCount: 0,
        hydrationTotalMs: 0,
        hydrationLastMs: null,
        hydrationLastSource: null
    };
    var analytics = {
        events: [],
        counters: {},
        once: {}
    };

    function now() {
        if (typeof window !== 'undefined' && window.performance && typeof window.performance.now === 'function') {
            return window.performance.now();
        }
        return Date.now();
    }

    function emitMetrics(reason) {
        if (typeof console === 'undefined' || typeof console.debug !== 'function') {
            return;
        }
        var cacheTotal = metrics.cacheHits + metrics.cacheMisses;
        var cacheRatio = cacheTotal ? (metrics.cacheHits / cacheTotal) * 100 : null;
        var avg = metrics.hydrationCount ? (metrics.hydrationTotalMs / metrics.hydrationCount) : null;
        var shouldLog = true;
        if (!(reason && reason.indexOf('cache') === 0)) {
            if (metrics.hydrationCount > 0 && metrics.hydrationCount % 5 !== 0 && reason !== 'hydration-manual' && reason !== 'hydration-history') {
                shouldLog = false;
            }
        }
        if (!shouldLog) {
            return;
        }
        var avgText = avg != null ? avg.toFixed(1) : 'n/a';
        var lastText = metrics.hydrationLastMs != null ? metrics.hydrationLastMs.toFixed(1) : 'n/a';
        var ratioText = cacheRatio != null ? ' (' + cacheRatio.toFixed(1) + '% hits)' : '';
        console.debug(
            '[AI Reviewer] Progress metrics (%s): cache hits=%d, misses=%d%s, hydration avg=%s ms (count=%d, last=%s ms from %s)',
            reason || 'update',
            metrics.cacheHits,
            metrics.cacheMisses,
            ratioText,
            avgText,
            metrics.hydrationCount,
            lastText,
            metrics.hydrationLastSource || 'unknown'
        );
    }

    function formatState(value) {
        if (!value) {
            return 'Unknown';
        }
        return String(value)
            .replace(/[_\s-]+/g, ' ')
            .replace(/\b([a-zA-Z])/g, function(match) {
                return match.toUpperCase();
            })
            .trim();
    }

    function humanizeState(value) {
        return formatState(value);
    }

    function recordAnalytics(eventName, attributes, options) {
        if (typeof window === 'undefined' || !eventName) {
            return;
        }
        analytics.events = Array.isArray(analytics.events) ? analytics.events : [];
        analytics.counters = analytics.counters || {};
        analytics.once = analytics.once || {};

        var opts = options || {};
        if (opts.once && analytics.once[eventName]) {
            return;
        }
        if (opts.once) {
            analytics.once[eventName] = true;
        }

        var payload = attributes && typeof attributes === 'object' ? attributes : {};
        analytics.counters[eventName] = (analytics.counters[eventName] || 0) + 1;
        analytics.events.push({
            name: eventName,
            attributes: payload,
            timestamp: Date.now()
        });

        try {
            if (window.AJS && typeof window.AJS.trigger === 'function') {
                window.AJS.trigger('analyticsEvent', {
                    name: 'aiReviewer.' + eventName,
                    data: payload
                });
            } else if (window.analytics && typeof window.analytics.track === 'function') {
                window.analytics.track('aiReviewer.' + eventName, payload);
            }
        } catch (err) {
            if (typeof console !== 'undefined' && typeof console.debug === 'function') {
                console.debug('[AI Reviewer] Analytics dispatch failed for %s', eventName, err);
            }
        }
    }

    function ensureGlobalHelpers() {
        if (typeof window === 'undefined') {
            return;
        }
        var namespace = window.AIReviewer || (window.AIReviewer = {});
        namespace.PrProgress = namespace.PrProgress || {};
        if (namespace.PrProgress.metrics) {
            metrics = namespace.PrProgress.metrics;
        } else {
            namespace.PrProgress.metrics = metrics;
        }
        if (namespace.PrProgress.analytics) {
            analytics = namespace.PrProgress.analytics;
            analytics.events = Array.isArray(analytics.events) ? analytics.events : [];
            analytics.counters = analytics.counters || {};
            analytics.once = analytics.once || {};
        } else {
            namespace.PrProgress.analytics = analytics;
        }
        namespace.PrProgress.emitMetrics = emitMetrics;
        namespace.PrProgress.formatState = formatState;
        namespace.PrProgress.humanizeState = humanizeState;
        namespace.PrProgress.trackEvent = recordAnalytics;
        window.AIReviewerFormatState = formatState;
        if (typeof window.formatState !== 'function') {
            window.formatState = formatState;
        }
        if (typeof window.humanizeState !== 'function') {
            window.humanizeState = humanizeState;
        }
    }

    ensureGlobalHelpers();

    function init() {
        var locationContext = resolvePullRequestContext();
        if (!locationContext) {
            return;
        }

        var Progress = window.AIReviewer && window.AIReviewer.Progress;
        if (!Progress || typeof Progress.createPoller !== 'function') {
            console.warn('AI Reviewer progress poller unavailable');
            return;
        }
        var Common = window.AIReviewer && window.AIReviewer.Common;

        var $panel = $('#ai-reviewer-pr-progress');
        if (!$panel.length) {
            $panel = buildFloatingPanel(locationContext.projectKey, locationContext.repositorySlug, locationContext.pullRequestId);
        }
        $panel = ensurePanelStructure($panel);
        recordAnalytics('panel.view.opened', {
            variant: $panel.hasClass('ai-reviewer-floating') ? 'floating' : 'inline'
        }, { once: true });

        var projectKey = $panel.data('projectKey');
        var repositorySlug = $panel.data('repositorySlug');
        var pullRequestId = $panel.data('prId');
        if (!projectKey || !repositorySlug || !pullRequestId) {
            console.warn('AI Reviewer progress panel missing pull request context');
            return;
        }

        var baseUrl = (AJS && typeof AJS.contextPath === 'function' ? AJS.contextPath() : '') || '';
        var progressUrl = baseUrl + '/rest/ai-reviewer/1.0/progress/' +
            encodeURIComponent(projectKey) + '/' +
            encodeURIComponent(repositorySlug) + '/' +
            encodeURIComponent(pullRequestId);
        var historyUrl = progressUrl + '/history';
        var historyDetailUrl = baseUrl + '/rest/ai-reviewer/1.0/progress/history/';
        var cacheKey = 'aiReviewer.progress.snapshot:' + projectKey + '/' + repositorySlug + '/' + pullRequestId;
        var storageAvailable = (function() {
            try {
                return typeof window !== 'undefined' && window.sessionStorage;
            } catch (e) {
                return false;
            }
        }());
        var cachedSnapshot = null;

        var $timelineWrap = $panel.find('.progress-timeline-wrapper');
        var $timeline = $panel.find('.progress-timeline');
        var $status = $panel.find('.progress-status');
        var $summaryBadge = $panel.find('#ai-reviewer-pr-progress-badge');
        var $summaryText = $panel.find('#ai-reviewer-pr-progress-summary');
        var $toggleBtn = $panel.find('#ai-reviewer-pr-progress-toggle');
        var $startBtn = $panel.find('#ai-reviewer-pr-progress-start');
        var $stopBtn = $panel.find('#ai-reviewer-pr-progress-stop');
        var $refreshBtn = $panel.find('#ai-reviewer-pr-progress-refresh');
        var $historySelect = $panel.find('#ai-reviewer-pr-history-select');
        var $historyRefreshBtn = $panel.find('#ai-reviewer-pr-history-refresh');
        var $historyMessage = $panel.find('#ai-reviewer-pr-history-message');

        var poller = null;
        var panelState = {
            collapsed: false,
            userCollapsed: false,
            autoCollapsed: false,
            currentRunId: null,
            viewMode: 'live',
            selectedHistoryId: null,
            historyEntries: [],
            lastHistoryRefreshRunId: null
        };

        function buildSnapshotPayload(response, events) {
            if (!response) {
                return null;
            }
            return {
                runId: response.runId || null,
                completed: response.completed === true,
                finalStatus: response.finalStatus || null,
                manual: response.manual === true,
                update: response.update === true,
                force: response.force === true,
                startedAt: response.startedAt || null,
                lastUpdatedAt: response.lastUpdatedAt || null,
                completedAt: response.completedAt || null,
                summary: response.summary || response.summaryText || null,
                events: Array.isArray(events) ? events : []
            };
        }

        function persistSnapshot(snapshot) {
            if (!snapshot) {
                return;
            }
            cachedSnapshot = snapshot;
            if (storageAvailable) {
                try {
                    sessionStorage.setItem(cacheKey, JSON.stringify(snapshot));
                } catch (e) {
                    // ignore storage failures
                }
            }
        }

        function loadSnapshotFromStorage(record) {
            if (!storageAvailable) {
                if (record) {
                    metrics.cacheMisses++;
                    emitMetrics('cache-miss');
                }
                return null;
            }
            try {
                var raw = sessionStorage.getItem(cacheKey);
                if (!raw) {
                    if (record) {
                        metrics.cacheMisses++;
                        emitMetrics('cache-miss');
                    }
                    return null;
                }
                var snapshot = JSON.parse(raw);
                if (record) {
                    metrics.cacheHits++;
                    emitMetrics('cache-hit');
                }
                return snapshot;
            } catch (e) {
                if (record) {
                    metrics.cacheMisses++;
                    emitMetrics('cache-miss');
                }
                return null;
            }
        }

        function setCollapsed(collapsed, userInitiated) {
            if (panelState.collapsed === collapsed && (!userInitiated || panelState.userCollapsed === collapsed)) {
                return;
            }
            panelState.collapsed = collapsed;
            if (userInitiated) {
                panelState.userCollapsed = collapsed;
            }
            $panel.toggleClass('is-collapsed', collapsed);
            $timelineWrap.toggle(!collapsed).attr('aria-hidden', collapsed ? 'true' : 'false');
            $toggleBtn
                .attr('aria-expanded', !collapsed)
                .attr('aria-label', collapsed ? 'Show AI review details' : 'Hide AI review details')
                .text(collapsed ? 'Show details' : 'Hide details');
        }

        function updateSummary(response, events) {
            var statusKey = 'waiting';
            var statusLabel = 'Waiting';
            var detail = 'Waiting for AI review...';

            if (response) {
                var customSummary = response.summary || response.summaryText;
                var completed = response.completed === true;
                if (!completed && response.finalStatus) {
                    completed = String(response.finalStatus).toLowerCase().indexOf('completed') !== -1;
                }
                if (completed) {
                    statusKey = statusKeyFromFinal(response.finalStatus);
                    statusLabel = response.finalStatus ? formatState(response.finalStatus) : 'Completed';
                    detail = customSummary ? customSummary : buildDetailSummary(response, events, true);
                } else {
                    statusKey = 'running';
                    statusLabel = 'Running';
                    detail = customSummary ? customSummary : buildDetailSummary(response, events, false);
                }
            }

            $summaryBadge
                .removeClass('status-running status-completed status-failed status-warning status-waiting')
                .addClass('status-' + statusKey)
                .text(statusLabel);
            $summaryText
                .text(detail)
                .attr('aria-label', detail);
        }

        var initialSnapshot = loadSnapshotFromStorage(true);
        if (initialSnapshot) {
            cachedSnapshot = initialSnapshot;
            renderProgress(initialSnapshot, { skipCache: true, forceRender: true, silent: true, source: 'cache', hydrationStartedAt: now() });
        } else {
            // Render placeholder timeline so the panel is never empty.
            Progress.renderTimeline($timeline, []);
            updateSummary(null, []);
        }

        $toggleBtn.on('click', function() {
            setCollapsed(!panelState.collapsed, true);
        });

        function updateButtons(active) {
            $startBtn.prop('disabled', !!active);
            $stopBtn.prop('disabled', !active);
        }

        function setStatus(type, message) {
            if (!type || !message) {
                $status
                    .hide()
                    .removeClass('info error')
                    .attr('aria-hidden', 'true')
                    .removeAttr('role');
                return;
            }
            var msg = Common && typeof Common.escapeHtml === 'function' ? Common.escapeHtml(message) : message;
            $status.removeClass('info error').addClass(type === 'error' ? 'error' : 'info');
            $status
                .attr('role', type === 'error' ? 'alert' : 'status')
                .attr('aria-hidden', 'false')
                .html(msg)
                .show();
        }

        function renderProgress(response, options) {
            var opts = options || {};
            var measurementStart = typeof opts.hydrationStartedAt === 'number' ? opts.hydrationStartedAt : now();
            var events = response && Array.isArray(response.events) ? response.events : [];
            if (response) {
                response.events = events;
                if (response.completed && !response.completedAt && response.lastUpdatedAt) {
                    response.completedAt = response.lastUpdatedAt;
                }
            }

            if (panelState.viewMode !== 'live' && !opts.forceRender) {
                if (!opts.skipCache) {
                    persistSnapshot(buildSnapshotPayload(response, events));
                }
                return;
            }

            if (!opts.silent) {
                setStatus(null);
            }

            Progress.renderTimeline($timeline, events);
            updateSummary(response, events);

            var runId = response && response.runId;
            var completed = response && response.completed === true;
            if (!completed && response && response.finalStatus) {
                completed = String(response.finalStatus).toLowerCase().indexOf('complete') !== -1;
            }

            if (runId && runId !== panelState.currentRunId) {
                panelState.currentRunId = runId;
                panelState.autoCollapsed = false;
                if (!panelState.userCollapsed) {
                    setCollapsed(false, false);
                }
            }

            if (completed) {
                if (!panelState.userCollapsed && !panelState.collapsed) {
                    setCollapsed(true, false);
                }
                panelState.autoCollapsed = true;
                if (runId && panelState.lastHistoryRefreshRunId !== runId) {
                    panelState.lastHistoryRefreshRunId = runId;
                    loadHistoryList(false);
                }
            } else {
                panelState.autoCollapsed = false;
                if (!panelState.userCollapsed && panelState.collapsed) {
                    setCollapsed(false, false);
                }
            }

            var endTick = now();
            var durationMs = Math.max(0, endTick - measurementStart);
            var source = opts.source || (panelState.viewMode === 'history' ? 'history' : 'poller');
            metrics.hydrationCount++;
            metrics.hydrationTotalMs += durationMs;
            metrics.hydrationLastMs = durationMs;
            metrics.hydrationLastSource = source;
            if (metrics.hydrationCount <= 3 || source !== 'poller' || metrics.hydrationCount % 5 === 0) {
                emitMetrics('hydration-' + source);
            }

            if (!opts.skipCache) {
                persistSnapshot(buildSnapshotPayload(response, events));
            }
        }

        function handleError(xhr, status, error, stop) {
            if (xhr && xhr.status === 404) {
                Progress.renderTimeline($timeline, []);
                updateSummary(null, []);
                setStatus(null);
                return;
            }
            if (Common && typeof Common.logAjaxError === 'function') {
                Common.logAjaxError('PR progress polling failed', xhr, status, error);
            }
            var fallback = error || status || 'Unable to fetch progress';
            var message = fallback;
            if (Common && typeof Common.composeErrorDetails === 'function') {
                message = Common.composeErrorDetails(xhr, fallback);
            }
            setStatus('error', message);
            if (stop && poller) {
                poller.stop();
                poller = null;
                updateButtons(false);
            }
        }

        function loadHistoryList(manual) {
            if (!$historySelect.length) {
                return;
            }
            recordAnalytics('history.list.requested', {
                manual: !!manual
            });
            $.ajax({
                url: historyUrl,
                type: 'GET',
                dataType: 'json',
                data: { limit: HISTORY_LIMIT, offset: 0 }
            }).done(function(data) {
                var entries = data && Array.isArray(data.entries) ? data.entries : [];
                panelState.historyEntries = entries;
                renderHistoryOptions();
                recordAnalytics('history.list.loaded', {
                    manual: !!manual,
                    count: entries.length
                });
                if (manual) {
                    if (entries.length) {
                        setStatus('info', 'Recent AI review runs refreshed.');
                    } else {
                        setStatus('info', 'No completed AI review runs found yet.');
                    }
                }
            }).fail(function(xhr, status, error) {
                if (Common && typeof Common.logAjaxError === 'function') {
                    Common.logAjaxError('Failed to load history list', xhr, status, error);
                }
                recordAnalytics('history.list.failed', {
                    manual: !!manual,
                    status: xhr ? xhr.status : null
                });
                if (manual) {
                    var message = error || status || 'Unable to load recent runs.';
                    if (Common && typeof Common.composeErrorDetails === 'function') {
                        message = Common.composeErrorDetails(xhr, message);
                    }
                    setStatus('error', message);
                }
            });
        }

        function renderHistoryOptions() {
            if (!$historySelect.length) {
                return;
            }
            var entries = panelState.historyEntries || [];
            $historySelect.empty();
            $historySelect.append($('<option value=""></option>').text('Live progress'));
            entries.forEach(function(entry) {
                var id = entry && entry.id != null ? String(entry.id) : '';
                if (!id) {
                    return;
                }
                var label = formatHistoryOption(entry);
                var $option = $('<option></option>')
                    .attr('value', id)
                    .text(label);
                $historySelect.append($option);
            });
            $historySelect.prop('disabled', entries.length === 0);
            if (panelState.viewMode === 'history' && panelState.selectedHistoryId != null) {
                $historySelect.val(String(panelState.selectedHistoryId));
            } else {
                $historySelect.val('');
            }
            if ($historyMessage.length) {
                if (!entries.length) {
                    $historyMessage
                        .text('No previous runs available yet.')
                        .attr('aria-hidden', 'false')
                        .show();
                } else {
                    $historyMessage
                        .attr('aria-hidden', 'true')
                        .hide();
                }
            }
        }

        function loadHistoryDetail(historyId) {
            var numericId = Number(historyId);
            if (!numericId) {
                switchToLive('Showing live progress.');
                return;
            }
            panelState.viewMode = 'history';
            panelState.selectedHistoryId = numericId;
            stopPolling('Live polling paused while viewing a completed run.');
            setStatus('info', 'Loading AI review run #' + numericId + '...');
            var requestStartedAt = now();
            recordAnalytics('history.detail.requested', {
                historyId: numericId
            });
            $.ajax({
                url: historyDetailUrl + encodeURIComponent(numericId),
                type: 'GET',
                dataType: 'json'
            }).done(function(data) {
                var events = data && Array.isArray(data.progress) ? data.progress : [];
                var entry = findHistoryEntry(numericId);
                renderHistoryView(entry, events, data, requestStartedAt);
            }).fail(function(xhr, status, error) {
                recordAnalytics('history.detail.failed', {
                    historyId: numericId,
                    status: xhr ? xhr.status : null
                });
                var message = error || status || 'Unable to load selected run.';
                if (Common && typeof Common.composeErrorDetails === 'function') {
                    message = Common.composeErrorDetails(xhr, message);
                }
                setStatus('error', message);
            });
        }

        function renderHistoryView(entry, events, raw, hydrationStartedAt) {
            panelState.viewMode = 'history';
            panelState.autoCollapsed = false;
            var summaryResponse = {
                completed: true,
                finalStatus: entry && (entry.reviewStatus || entry.reviewOutcome) ? (entry.reviewStatus || entry.reviewOutcome) : 'COMPLETED',
                summary: entry && entry.summary ? entry.summary : null,
                lastUpdatedAt: entry && entry.completedAt ? entry.completedAt : (entry && entry.startedAt ? entry.startedAt : Date.now()),
                completedAt: entry && entry.completedAt ? entry.completedAt : null,
                events: events
            };
            recordAnalytics('history.entry.viewed', {
                historyId: panelState.selectedHistoryId,
                status: summaryResponse.finalStatus,
                eventCount: Array.isArray(events) ? events.length : 0
            });
            renderProgress(summaryResponse, {
                source: 'history',
                forceRender: true,
                skipCache: true,
                hydrationStartedAt: typeof hydrationStartedAt === 'number' ? hydrationStartedAt : now(),
                silent: true
            });
            updateButtons(false);
            setCollapsed(false, false);
            setStatus('info', 'Viewing a completed AI review run. Select "Live progress" to resume live updates.');
        }

        function findHistoryEntry(historyId) {
            var numericId = Number(historyId);
            if (!numericId) {
                return null;
            }
            var entries = panelState.historyEntries || [];
            for (var i = 0; i < entries.length; i++) {
                var entry = entries[i];
                if (entry && Number(entry.id) === numericId) {
                    return entry;
                }
            }
            return null;
        }

        function switchToLive(message) {
            var previousMode = panelState.viewMode;
            panelState.viewMode = 'live';
            panelState.selectedHistoryId = null;
            if ($historySelect.length) {
                $historySelect.val('');
            }
            if (message) {
                setStatus('info', message);
            } else {
                setStatus(null);
            }
            var snapshot = cachedSnapshot || loadSnapshotFromStorage(false);
            if (snapshot) {
                renderProgress(snapshot, { skipCache: true, forceRender: true, silent: true, source: 'cache', hydrationStartedAt: now() });
            } else {
                Progress.renderTimeline($timeline, []);
                updateSummary(null, []);
            }
            recordAnalytics('history.view.live', {
                previousMode: previousMode,
                message: message || null
            });
            startPolling(false);
        }

        function formatHistoryOption(entry) {
            if (!entry) {
                return 'Completed run';
            }
            var label = entry.summary || '';
            if (!label) {
                var status = entry.reviewStatus || entry.reviewOutcome || 'COMPLETED';
                label = formatState(status);
                if (typeof entry.totalIssuesFound === 'number') {
                    if (entry.totalIssuesFound > 0) {
                        label += ' · ' + entry.totalIssuesFound + (entry.totalIssuesFound === 1 ? ' issue' : ' issues');
                    } else {
                        label += ' · No issues';
                    }
                }
            }
            var timestamp = entry.completedAt || entry.startedAt;
            if (timestamp) {
                label += ' · ' + formatDateTime(timestamp);
            }
            return truncate(label, 120);
        }

        function startPolling(manual) {
            panelState.viewMode = 'live';
            panelState.selectedHistoryId = null;
            if ($historySelect.length) {
                $historySelect.val('');
            }
            if (poller && typeof poller.isActive === 'function' && poller.isActive()) {
                if (manual) {
                    setStatus('info', 'Live progress polling already running.');
                }
                return;
            }
            if (poller) {
                poller.stop();
                poller = null;
            }
            recordAnalytics('live.polling.started', {
                manual: !!manual
            });
            poller = Progress.createPoller({
                url: progressUrl,
                onStart: function() {
                    updateButtons(true);
                    setStatus('info', manual
                        ? 'Resuming live progress updates...'
                        : 'Listening for AI review progress...');
                },
                onUpdate: function(response) {
                    renderProgress(response, { source: 'poller', hydrationStartedAt: now() });
                },
                onError: function(xhr, status, error) {
                    var shouldStop = !xhr || xhr.status === 401 || xhr.status === 403;
                    handleError(xhr, status, error, shouldStop);
                },
                onStop: function() {
                    updateButtons(false);
                }
            });
            poller.start();
        }

        function stopPolling(message) {
            if (poller) {
                poller.stop();
                poller = null;
            }
            recordAnalytics('live.polling.stopped', {
                message: message || null
            });
            updateButtons(false);
            if (message) {
                setStatus('info', message);
            }
        }

        function fetchOnce() {
            if (panelState.viewMode !== 'live') {
                switchToLive('Resuming live progress...');
                return;
            }
            setStatus('info', 'Fetching latest progress...');
            recordAnalytics('live.progress.manualFetch', {
                reason: 'manual-refresh'
            });
            var fetchStartedAt = now();
            $.ajax({
                url: progressUrl,
                type: 'GET',
                dataType: 'json'
            }).done(function(response) {
                renderProgress(response, { source: 'manual', hydrationStartedAt: fetchStartedAt });
            }).fail(function(xhr, status, error) {
                handleError(xhr, status, error, false);
            });
        }

        $startBtn.on('click', function() {
            if (panelState.viewMode !== 'live') {
                switchToLive('Resuming live progress...');
            } else {
                startPolling(true);
            }
        });

        $stopBtn.on('click', function() {
            stopPolling('Live polling paused.');
        });

        $refreshBtn.on('click', function() {
            if (panelState.viewMode !== 'live') {
                switchToLive('Resuming live progress...');
            } else {
                fetchOnce();
            }
        });

        if ($historySelect.length) {
            $historySelect.on('change', function() {
                var value = $(this).val();
                if (!value) {
                    recordAnalytics('history.dropdown.liveSelected', {
                        previousMode: panelState.viewMode
                    });
                    switchToLive('Showing live progress.');
                    return;
                }
                var historyId = parseInt(value, 10);
                if (isNaN(historyId)) {
                    recordAnalytics('history.dropdown.invalidSelected', {
                        inputLength: value ? String(value).length : 0
                    });
                    switchToLive('Showing live progress.');
                    return;
                }
                var entry = findHistoryEntry(historyId);
                recordAnalytics('history.dropdown.entrySelected', {
                    historyId: historyId,
                    status: entry ? (entry.reviewStatus || entry.reviewOutcome || null) : null,
                    hasSummary: !!(entry && entry.summary)
                });
                loadHistoryDetail(historyId);
            });
        }

        if ($historyRefreshBtn.length) {
            $historyRefreshBtn.on('click', function() {
                recordAnalytics('history.dropdown.refreshClicked', {
                    viewMode: panelState.viewMode
                });
                loadHistoryList(true);
            });
        }

        loadHistoryList(false);

        // Start polling as soon as the panel loads.
        setCollapsed(false, false);
        startPolling(false);
    }

    function resolvePullRequestContext() {
        var path = window.location.pathname || '';
        if (path.indexOf('/pull-requests/') === -1) {
            return null;
        }
        var segments = path.split('/').filter(function(part) { return part && part.length; });
        var projectsIdx = segments.indexOf('projects');
        if (projectsIdx === -1 || segments.length <= projectsIdx + 4) {
            return null;
        }
        if (segments[projectsIdx + 2] !== 'repos') {
            return null;
        }
        var prIdx = segments.indexOf('pull-requests');
        if (prIdx === -1 || segments.length <= prIdx + 1) {
            return null;
        }
        var projectKey = decodeURIComponent(segments[projectsIdx + 1]);
        var repositorySlug = decodeURIComponent(segments[projectsIdx + 3]);
        var pullRequestId = segments[prIdx + 1];
        if (!projectKey || !repositorySlug || !pullRequestId) {
            return null;
        }
        return {
            projectKey: projectKey,
            repositorySlug: repositorySlug,
            pullRequestId: pullRequestId
        };
    }

    function buildFloatingPanel(projectKey, repositorySlug, pullRequestId) {
        var timelineId = ('ai-reviewer-pr-progress-details-' + projectKey + '-' + repositorySlug + '-' + pullRequestId)
            .replace(/[^A-Za-z0-9_-]/g, '_');

        var $panel = $('<div id="ai-reviewer-pr-progress" class="ai-reviewer-pr-progress ai-reviewer-floating" role="region" aria-label="AI review progress"></div>')
            .attr('data-project-key', projectKey)
            .attr('data-repository-slug', repositorySlug)
            .attr('data-pr-id', pullRequestId);

        var $header = $('<div class="progress-panel-header"></div>')
            .append('<h3>AI Review Progress</h3>');

        var $actions = $('<div class="progress-panel-actions"></div>')
            .append('<button type="button" class="aui-button aui-button-subtle icon-only" id="ai-reviewer-pr-progress-start" aria-label="Resume live updates" title="Resume live updates"><span class="aui-icon aui-icon-small aui-iconfont-vid-play"></span></button>')
            .append('<button type="button" class="aui-button aui-button-subtle icon-only" id="ai-reviewer-pr-progress-stop" aria-label="Pause live updates" title="Pause live updates" disabled="disabled"><span class="aui-icon aui-icon-small aui-iconfont-pause"></span></button>')
            .append('<button type="button" class="aui-button aui-button-subtle icon-only" id="ai-reviewer-pr-progress-refresh" aria-label="Refresh progress" title="Refresh progress"><span class="aui-icon aui-icon-small aui-iconfont-refresh"></span></button>');

        $header.append($actions);

        var historySelectId = 'ai-reviewer-pr-history-select';
        var $summary = $('<div class="progress-summary" role="status" aria-live="polite"></div>')
            .append('<div class="progress-summary-body"><span class="status-badge status-waiting" id="ai-reviewer-pr-progress-badge">Waiting</span><div class="progress-summary-text" id="ai-reviewer-pr-progress-summary">Waiting for AI review...</div></div>')
            .append('<div class="progress-summary-controls"><button type="button" class="aui-button aui-button-link toggle-details" id="ai-reviewer-pr-progress-toggle" aria-expanded="true" aria-controls="' + timelineId + '">Hide details</button></div>');

        var $historyControls = $('<div class="progress-history-controls"></div>')
            .append('<label class="progress-history-label" for="' + historySelectId + '">Recent runs</label>')
            .append(
                $('<div class="history-select-wrapper"></div>')
                    .append('<select class="aui-select" id="' + historySelectId + '" disabled="disabled"><option value="">Live progress</option></select>')
                    .append('<button type="button" class="aui-button aui-button-subtle" id="ai-reviewer-pr-history-refresh"><span class="aui-icon aui-icon-small aui-iconfont-refresh"></span> Refresh</button>')
            )
            .append('<div class="progress-history-message" id="ai-reviewer-pr-history-message" aria-live="polite" aria-hidden="true" style="display:none;"></div>');

        var $status = $('<div class="progress-status aui-message info" role="status" aria-live="assertive" aria-hidden="true" style="display:none;"></div>');
        var $timeline = $('<div class="progress-timeline"></div>');
        var $timelineWrap = $('<div class="progress-timeline-wrapper"></div>')
            .attr('id', timelineId)
            .attr('aria-hidden', 'false')
            .append($timeline);

        $panel.append($header).append($summary).append($historyControls).append($status).append($timelineWrap);
        $('body').append($panel);
        return $panel;
    }

    function ensurePanelStructure($panel) {
        if (!$panel.find('.progress-summary').length || !$panel.find('.progress-timeline-wrapper').length || !$panel.find('.progress-history-controls').length) {
            var projectKey = $panel.data('projectKey');
            var repositorySlug = $panel.data('repositorySlug');
            var pullRequestId = $panel.data('prId');
            $panel.remove();
            return buildFloatingPanel(projectKey, repositorySlug, pullRequestId);
        }
        return $panel;
    }

    function statusKeyFromFinal(finalStatus) {
        if (!finalStatus) {
            return 'completed';
        }
        var normalized = String(finalStatus).toLowerCase();
        if (normalized.indexOf('fail') !== -1 || normalized.indexOf('error') !== -1) {
            return 'failed';
        }
        if (normalized.indexOf('block') !== -1 || normalized.indexOf('warn') !== -1) {
            return 'warning';
        }
        if (normalized.indexOf('complete') !== -1) {
            return 'completed';
        }
        return 'completed';
    }

    function buildDetailSummary(response, events, completed) {
        if (response) {
            var summary = response.summary || response.summaryText;
            if (summary) {
                return summary;
            }
        }
        var pieces = [];
        if (completed) {
            pieces.push(response.finalStatus ? formatState(response.finalStatus) : 'Completed');
        } else {
            pieces.push('Running');
        }
        if (response && response.lastUpdatedAt) {
            pieces.push('Updated ' + formatDateTime(response.lastUpdatedAt));
        }
        if (events && events.length) {
            pieces.push(events.length + ' events');
        }
        if (!completed && (!events || !events.length)) {
            pieces.push('Awaiting first milestone');
        }
        return pieces.join(' · ');
    }

    function truncate(value, maxLength) {
        if (value == null) {
            return '';
        }
        var str = String(value);
        if (str.length <= maxLength) {
            return str;
        }
        if (maxLength <= 3) {
            return str.substring(0, maxLength);
        }
        return str.substring(0, maxLength - 3) + '...';
    }

    function formatDateTime(value) {
        try {
            var date = new Date(value);
            if (isNaN(date.getTime())) {
                return '';
            }
            return date.toLocaleString();
        } catch (e) {
            return '';
        }
    }

    $(document).ready(init);
})(AJS.$);
