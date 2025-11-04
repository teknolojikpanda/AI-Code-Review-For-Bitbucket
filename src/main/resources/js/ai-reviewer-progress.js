(function($) {
    'use strict';

    var Global = window.AIReviewer || (window.AIReviewer = {});
    var Progress = Global.Progress || (Global.Progress = {});

    var DEFAULT_INTERVAL = 4000;
    var MAX_INTERVAL = 20000;

    Progress.renderTimeline = function($container, events, options) {
        if (!$container || !$container.length) {
            return;
        }
        options = options || {};
        var data = Array.isArray(events) ? events : [];

        if (!data.length) {
            $container.empty().append('<div class="progress-empty">No progress events recorded.</div>');
            return;
        }

        var items = data.map(function(event) {
            return buildEventItem(event, options);
        }).join('');

        var timelineHtml = '<ul class="progress-timeline-list">' + items + '</ul>';
        $container.empty().append(timelineHtml);
    };

    Progress.createPoller = function(opts) {
        opts = opts || {};
        var url = opts.url;
        if (!url) {
            throw new Error('Progress poller requires a URL');
        }

        var interval = opts.initialInterval || DEFAULT_INTERVAL;
        var maxInterval = opts.maxInterval || MAX_INTERVAL;
        var active = false;
        var timeoutId = null;

        function resetInterval() {
            interval = opts.initialInterval || DEFAULT_INTERVAL;
        }

        function scheduleNext() {
            if (!active) {
                return;
            }
            timeoutId = window.setTimeout(execute, interval);
        }

        function execute() {
            if (!active) {
                return;
            }
            $.ajax({
                url: url,
                dataType: 'json'
            }).done(function(response) {
                resetInterval();
                if (typeof opts.onUpdate === 'function') {
                    opts.onUpdate(response);
                }
                scheduleNext();
            }).fail(function(xhr, status, error) {
                interval = Math.min(maxInterval, Math.floor(interval * 1.5));
                if (typeof opts.onError === 'function') {
                    opts.onError(xhr, status, error);
                }
                scheduleNext();
            });
        }

        return {
            start: function() {
                if (active) {
                    return;
                }
                active = true;
                resetInterval();
                if (typeof opts.onStart === 'function') {
                    opts.onStart();
                }
                execute();
            },
            stop: function() {
                if (!active) {
                    return;
                }
                active = false;
                if (timeoutId) {
                    window.clearTimeout(timeoutId);
                    timeoutId = null;
                }
                if (typeof opts.onStop === 'function') {
                    opts.onStop();
                }
            },
            isActive: function() {
                return active;
            }
        };
    };

    function buildEventItem(event, options) {
        var stage = formatStage(event.stage || 'Unknown Stage');
        var percent = typeof event.percentComplete === 'number' ? event.percentComplete : null;
        var timestamp = event.timestamp ? formatTimestamp(event.timestamp) : '—';
        var details = event.details && typeof event.details === 'object' ? event.details : null;

        var percentHtml = percent != null ? '<span class="progress-event-percent">' + percent + '%</span>' : '';
        var detailHtml = '';
        if (details && Object.keys(details).length) {
            detailHtml = '<dl class="progress-event-details">' + Object.keys(details).map(function(key) {
                return '<div><dt>' + escapeHtml(formatStage(key)) + '</dt><dd>' + escapeHtml(String(details[key])) + '</dd></div>';
            }).join('') + '</dl>';
        }

        return '<li class="progress-event">' +
            '<div class="progress-event-header">' +
                '<span class="progress-event-stage">' + escapeHtml(stage) + '</span>' +
                percentHtml +
                '<span class="progress-event-timestamp">' + escapeHtml(timestamp) + '</span>' +
            '</div>' +
            detailHtml +
        '</li>';
    }

    function formatStage(stage) {
        if (!stage) {
            return '';
        }
        return stage.replace(/[-_.]/g, ' ') 
            .replace(/\b([a-z])/g, function(match) { return match.toUpperCase(); })
            .trim();
    }

    function formatTimestamp(value) {
        try {
            return new Date(value).toLocaleString();
        } catch (e) {
            return '—';
        }
    }

    function escapeHtml(value) {
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

})(AJS.$);
