(function($) {
    'use strict';

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

    function ensureGlobalHelpers() {
        if (typeof window === 'undefined') {
            return;
        }
        var namespace = window.AIReviewer || (window.AIReviewer = {});
        namespace.PrProgress = namespace.PrProgress || {};
        namespace.PrProgress.formatState = formatState;
        namespace.PrProgress.humanizeState = humanizeState;
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

        var $timelineWrap = $panel.find('.progress-timeline-wrapper');
        var $timeline = $panel.find('.progress-timeline');
        var $status = $panel.find('.progress-status');
        var $summaryBadge = $panel.find('#ai-reviewer-pr-progress-badge');
        var $summaryText = $panel.find('#ai-reviewer-pr-progress-summary');
        var $toggleBtn = $panel.find('#ai-reviewer-pr-progress-toggle');
        var $startBtn = $panel.find('#ai-reviewer-pr-progress-start');
        var $stopBtn = $panel.find('#ai-reviewer-pr-progress-stop');
        var $refreshBtn = $panel.find('#ai-reviewer-pr-progress-refresh');

        var poller = null;
        var panelState = {
            collapsed: false,
            userCollapsed: false,
            autoCollapsed: false,
            currentRunId: null
        };

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
                var completed = response.completed === true;
                if (!completed && response.finalStatus) {
                    completed = String(response.finalStatus).toLowerCase().indexOf('completed') !== -1;
                }
                if (completed) {
                    statusKey = statusKeyFromFinal(response.finalStatus);
                    statusLabel = response.finalStatus ? formatState(response.finalStatus) : 'Completed';
                    detail = buildDetailSummary(response, events, true);
                } else {
                    statusKey = 'running';
                    statusLabel = 'Running';
                    detail = buildDetailSummary(response, events, false);
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

        // Render placeholder timeline so the panel is never empty.
        Progress.renderTimeline($timeline, []);
        updateSummary(null, []);

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

        function renderProgress(response) {
            setStatus(null);
            var events = response && Array.isArray(response.events) ? response.events : [];
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
            } else {
                panelState.autoCollapsed = false;
                if (!panelState.userCollapsed && panelState.collapsed) {
                    setCollapsed(false, false);
                }
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

        function startPolling(manual) {
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
            poller = Progress.createPoller({
                url: progressUrl,
                onStart: function() {
                    updateButtons(true);
                    setStatus('info', manual
                        ? 'Resuming live progress updates...'
                        : 'Listening for AI review progress...');
                },
                onUpdate: function(response) {
                    renderProgress(response);
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
            updateButtons(false);
            if (message) {
                setStatus('info', message);
            }
        }

        function fetchOnce() {
            setStatus('info', 'Fetching latest progress...');
            $.ajax({
                url: progressUrl,
                type: 'GET',
                dataType: 'json'
            }).done(function(response) {
                renderProgress(response);
            }).fail(function(xhr, status, error) {
                handleError(xhr, status, error, false);
            });
        }

        $startBtn.on('click', function() {
            startPolling(true);
        });

        $stopBtn.on('click', function() {
            stopPolling('Live polling paused.');
        });

        $refreshBtn.on('click', function() {
            fetchOnce();
        });

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
            .append('<button type="button" class="aui-button" id="ai-reviewer-pr-progress-start"><span class="aui-icon aui-icon-small aui-iconfont-time"></span> Resume</button>')
            .append('<button type="button" class="aui-button" id="ai-reviewer-pr-progress-stop" disabled="disabled"><span class="aui-icon aui-icon-small aui-iconfont-close-dialog"></span> Pause</button>')
            .append('<button type="button" class="aui-button aui-button-subtle" id="ai-reviewer-pr-progress-refresh"><span class="aui-icon aui-icon-small aui-iconfont-refresh"></span> Refresh</button>');

        $header.append($actions);

        var $summary = $('<div class="progress-summary" role="status" aria-live="polite"></div>')
            .append('<div class="progress-summary-body"><span class="status-badge status-waiting" id="ai-reviewer-pr-progress-badge">Waiting</span><div class="progress-summary-text" id="ai-reviewer-pr-progress-summary">Waiting for AI review...</div></div>')
            .append('<div class="progress-summary-controls"><button type="button" class="aui-button aui-button-link toggle-details" id="ai-reviewer-pr-progress-toggle" aria-expanded="true" aria-controls="' + timelineId + '">Hide details</button></div>');

        var $status = $('<div class="progress-status aui-message info" role="status" aria-live="assertive" aria-hidden="true" style="display:none;"></div>');
        var $timeline = $('<div class="progress-timeline"></div>');
        var $timelineWrap = $('<div class="progress-timeline-wrapper"></div>')
            .attr('id', timelineId)
            .attr('aria-hidden', 'false')
            .append($timeline);

        $panel.append($header).append($summary).append($status).append($timelineWrap);
        $('body').append($panel);
        return $panel;
    }

    function ensurePanelStructure($panel) {
        if (!$panel.find('.progress-summary').length || !$panel.find('.progress-timeline-wrapper').length) {
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
