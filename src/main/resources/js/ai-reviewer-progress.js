(function($) {
    'use strict';

    var Global = window.AIReviewer || (window.AIReviewer = {});
    var Progress = Global.Progress || (Global.Progress = {});
    var TOGGLE_SELECTOR = '.progress-event-toggle';
    var LABEL_SELECTOR = '.toggle-label';
    var HAS_DETAILS_CLASS = 'has-details';
    var EXPANDED_CLASS = 'is-expanded';
    var containerIdCounter = 0;
    var uniqueIdCounter = 0;

    var DEFAULT_INTERVAL = 4000;
    var MAX_INTERVAL = 20000;

    Progress.renderTimeline = function($container, events, options) {
        if (!$container || !$container.length) {
            return;
        }
        options = options || {};
        var data = Array.isArray(events) ? events : [];
        data = decorateEvents(data);
        var containerKey = ensureContainerId($container);

        if (!data.length) {
            $container.empty().append('<div class="progress-empty">No progress events recorded.</div>');
            bindToggleHandlers($container);
            return;
        }

        var items = data.map(function(event, index) {
            return buildEventItem(event, options, containerKey + '-' + index);
        }).join('');

        var timelineHtml = '<ul class="progress-timeline-list">' + items + '</ul>';
        $container.empty().append(timelineHtml);
        bindToggleHandlers($container);
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

    function decorateEvents(events) {
        if (!Array.isArray(events)) {
            return [];
        }
        var latestActivity = null;
        var transformed = [];
        events.forEach(function(event) {
            if (!event) {
                return;
            }
            if (event.stage === 'analysis.active') {
                if (event.details && typeof event.details === 'object' && event.details.currentlyAnalyzing) {
                    latestActivity = {
                        text: event.details.currentlyAnalyzing,
                        count: event.details.activeChunkCount
                    };
                }
                return;
            }
            transformed.push(cloneEvent(event));
        });

        if (latestActivity) {
            for (var i = transformed.length - 1; i >= 0; i--) {
                var event = transformed[i];
                if (event && event.stage === 'analysis.started') {
                    var details = Object.assign({}, event.details || {});
                    details.currentlyAnalyzing = latestActivity.text;
                    if (typeof latestActivity.count === 'number') {
                        details.activeChunkCount = latestActivity.count;
                    }
                    transformed[i] = Object.assign({}, event, { details: details });
                    break;
                }
            }
        }
        return transformed;
    }

    function cloneEvent(event) {
        var clone = Object.assign({}, event);
        if (event.details && typeof event.details === 'object') {
            clone.details = Object.assign({}, event.details);
        }
        return clone;
    }

    function formatDetailValue(key, value) {
        if (value === null || value === undefined) {
            return '—';
        }
        if (Array.isArray(value)) {
            return value.map(function(item) {
                if (item === null || item === undefined) {
                    return '';
                }
                if (typeof item === 'object') {
                    return Object.keys(item).map(function(childKey) {
                        return childKey + '=' + item[childKey];
                    }).join(', ');
                }
                if (typeof item === 'boolean') {
                    return item ? 'Yes' : 'No';
                }
                return String(item);
            }).filter(function(entry) {
                return entry && String(entry).trim().length;
            }).join('; ');
        }
        if (typeof value === 'object') {
            return Object.keys(value).map(function(childKey) {
                var child = value[childKey];
                if (child === null || child === undefined) {
                    return childKey + '=—';
                }
                if (typeof child === 'boolean') {
                    return childKey + '=' + (child ? 'Yes' : 'No');
                }
                return childKey + '=' + child;
            }).join(', ');
        }
        if (typeof value === 'boolean') {
            return value ? 'Yes' : 'No';
        }
        return String(value);
    }

    function buildEventItem(event, options, uid) {
        var stage = formatStage(event.stage || 'Unknown Stage');
        var percent = typeof event.percentComplete === 'number' ? event.percentComplete : null;
        var timestamp = event.timestamp ? formatTimestamp(event.timestamp) : '—';
        var details = event.details && typeof event.details === 'object' ? event.details : null;
        var hasDetails = details && Object.keys(details).length;
        var expanded = !!(options && options.expandAll);
        var idBase = uid || ('progress-event-' + nextUniqueId());

        var percentHtml = percent != null ? '<span class="progress-event-percent">' + percent + '%</span>' : '';
        var detailHtml = '';
        var toggleHtml = '';

        if (hasDetails) {
            var detailsId = idBase + '-details';
            var label = expanded ? 'Hide details' : 'Show details';
            toggleHtml = '<button type="button" class="progress-event-toggle" aria-expanded="' + (expanded ? 'true' : 'false') + '" aria-controls="' + detailsId + '">' +
                '<span class="toggle-label">' + label + '</span>' +
                '<span class="aui-icon aui-icon-small aui-iconfont-chevron-down toggle-icon" aria-hidden="true"></span>' +
                '</button>';
            detailHtml = '<dl class="progress-event-details" id="' + detailsId + '" aria-hidden="' + (expanded ? 'false' : 'true') + '">' + Object.keys(details).map(function(key) {
                return '<div><dt>' + escapeHtml(formatStage(key)) + '</dt><dd>' + escapeHtml(formatDetailValue(key, details[key])) + '</dd></div>';
            }).join('') + '</dl>';
        }

        var itemClasses = 'progress-event';
        if (hasDetails) {
            itemClasses += ' ' + HAS_DETAILS_CLASS;
            if (expanded) {
                itemClasses += ' ' + EXPANDED_CLASS;
            }
        }

        return '<li class="' + itemClasses + '">' +
            '<div class="progress-event-header">' +
                '<span class="progress-event-stage">' + escapeHtml(stage) + '</span>' +
                percentHtml +
                '<span class="progress-event-timestamp">' + escapeHtml(timestamp) + '</span>' +
                toggleHtml +
            '</div>' +
            detailHtml +
        '</li>';
    }

    function ensureContainerId($container) {
        var existing = $container.data('aiReviewerTimelineId');
        if (existing) {
            return existing;
        }
        containerIdCounter += 1;
        var id = 'ai-reviewer-timeline-' + containerIdCounter;
        $container.data('aiReviewerTimelineId', id);
        return id;
    }

    function nextUniqueId() {
        uniqueIdCounter += 1;
        return uniqueIdCounter;
    }

    function bindToggleHandlers($container) {
        $container.off('click', TOGGLE_SELECTOR);
        $container.on('click', TOGGLE_SELECTOR, function(event) {
            event.preventDefault();
            var $button = $(this);
            var expanded = $button.attr('aria-expanded') === 'true';
            var nextState = !expanded;
            $button.attr('aria-expanded', nextState ? 'true' : 'false');
            $button.find(LABEL_SELECTOR).text(nextState ? 'Hide details' : 'Show details');
            var $eventItem = $button.closest('.progress-event');
            $eventItem.toggleClass(EXPANDED_CLASS, nextState);
            var controls = $button.attr('aria-controls');
            var $details = controls ? $('#' + controls) : $eventItem.find('.progress-event-details').first();
            if ($details.length) {
                $details.attr('aria-hidden', nextState ? 'false' : 'true');
            }
        });
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
