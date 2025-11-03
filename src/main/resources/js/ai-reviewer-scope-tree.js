(function($) {
    'use strict';

    var Global = window.AIReviewer || (window.AIReviewer = {});
    if (Global.ScopeTree) {
        return;
    }

    function buildRepoKey(projectKey, repositorySlug) {
        if (!projectKey || !repositorySlug) {
            return null;
        }
        return projectKey + '/' + repositorySlug;
    }

    function splitRepoKey(key) {
        if (!key || typeof key !== 'string') {
            return null;
        }
        var parts = key.split('/');
        if (parts.length !== 2) {
            return null;
        }
        return {
            projectKey: parts[0],
            repositorySlug: parts[1]
        };
    }

    function buildCatalogIndex(catalog) {
        var projectIndex = new Map();
        var repositoryIndex = new Map();
        var total = 0;
        (Array.isArray(catalog) ? catalog : []).forEach(function(project) {
            if (!project || !project.projectKey) {
                return;
            }
            projectIndex.set(project.projectKey, project);
            var repos = Array.isArray(project.repositories) ? project.repositories : [];
            repos.forEach(function(repo) {
                if (!repo || !repo.repositorySlug) {
                    return;
                }
                total += 1;
                repositoryIndex.set(buildRepoKey(project.projectKey, repo.repositorySlug), {
                    projectKey: project.projectKey,
                    projectName: project.projectName,
                    repositorySlug: repo.repositorySlug,
                    repositoryName: repo.repositoryName
                });
            });
        });
        return {
            projects: projectIndex,
            repositories: repositoryIndex,
            totalRepositories: total
        };
    }

    function setMessage($container, messageHtml, type) {
        if (!$container || !$container.length) {
            return;
        }
        if (!messageHtml) {
            $container.empty();
            return;
        }
        var extraClass = type ? ' ' + type : '';
        $container.html('<div class="loading-message' + extraClass + '">' + messageHtml + '</div>');
    }

    function renderTree($container, catalog, options) {
        if (!$container || !$container.length) {
            return;
        }
        var projects = Array.isArray(catalog) ? catalog : [];
        if (!projects.length) {
            $container.html('<div class="loading-message">No repositories available.</div>');
            return;
        }

        options = options || {};
        var allLabel = options.allLabel || 'All repositories (current and future)';
        var projectGroupLabel = options.projectGroupLabel || 'All project repositories';
        var personalGroupLabel = options.personalGroupLabel || 'All personal repositories';

        var partitioned = partitionProjects(projects);
        var $tree = $('<div class="scope-tree"></div>');
        var $rootList = $('<ul class="scope-tree-list"></ul>');
        $rootList.append(buildAllNode(allLabel));

        if (partitioned.projects.length) {
            $rootList.append(buildGroupNode('projects', projectGroupLabel, partitioned.projects));
        }
        if (partitioned.personal.length) {
            $rootList.append(buildGroupNode('personal', personalGroupLabel, partitioned.personal));
        }

        $tree.append($rootList);
        $container.empty().append($tree);
    }

    function partitionProjects(catalog) {
        var projects = [];
        var personal = [];
        catalog.forEach(function(project) {
            if (!project) {
                return;
            }
            var isPersonal = project.personal === true ||
                (project.projectType && typeof project.projectType === 'string' && project.projectType.toUpperCase() === 'PERSONAL');
            (isPersonal ? personal : projects).push(project);
        });

        var comparator = function(a, b) {
            var left = (a.projectKey || '').toLowerCase();
            var right = (b.projectKey || '').toLowerCase();
            return left.localeCompare(right);
        };
        projects.sort(comparator);
        personal.sort(comparator);
        return { projects: projects, personal: personal };
    }

    function buildAllNode(labelText) {
        var $checkbox = $('<input type="checkbox" class="scope-checkbox">')
            .attr('id', 'scope-checkbox-all')
            .attr('data-node-type', 'all');
        var $label = $('<label for="scope-checkbox-all"></label>').text(labelText);
        var $row = $('<div class="node-row"></div>')
            .append('<span class="scope-node-toggle spacer"></span>')
            .append($checkbox)
            .append($label);
        return $('<li class="scope-node scope-node-root"></li>').append($row);
    }

    function buildGroupNode(groupType, label, projects) {
        var groupId = 'scope-group-' + groupType;
        var $checkbox = $('<input type="checkbox" class="scope-checkbox">')
            .attr('id', groupId)
            .attr('data-node-type', 'group')
            .attr('data-group-type', groupType);
        var $label = $('<label></label>').attr('for', groupId).text(label);
        var repoCount = 0;
        projects.forEach(function(project) {
            repoCount += Array.isArray(project.repositories) ? project.repositories.length : 0;
        });
        var $count = $('<span class="count-badge"></span>').text(repoCount);

        var $row = $('<div class="node-row"></div>')
            .append('<button type="button" class="scope-node-toggle" aria-expanded="true"></button>')
            .append($checkbox)
            .append($label)
            .append($count);

        var $children = $('<ul class="scope-children"></ul>');
        projects.forEach(function(project) {
            $children.append(buildProjectNode(project, groupType));
        });

        var $node = $('<li class="scope-node scope-node-group scope-node-expandable"></li>')
            .attr('data-node-type', 'group')
            .attr('data-group-type', groupType)
            .append($row)
            .append($children);

        if (!repoCount) {
            $node.removeClass('scope-node-expandable');
        }
        return $node;
    }

    function buildProjectNode(project, groupType) {
        var projectKey = project.projectKey;
        var safeId = projectKey ? projectKey.replace(/[^A-Za-z0-9_-]/g, '_') : 'project';
        var projectId = 'scope-project-' + safeId;

        var labelText = projectKey || '';
        if (project.projectName && project.projectName !== projectKey) {
            labelText += ' · ' + project.projectName;
        }

        var $checkbox = $('<input type="checkbox" class="scope-checkbox">')
            .attr('id', projectId)
            .attr('data-node-type', 'project')
            .attr('data-project-key', projectKey)
            .attr('data-group-type', groupType);

        var $label = $('<label></label>').attr('for', projectId).text(labelText);
        var repoList = Array.isArray(project.repositories) ? project.repositories : [];
        var $count = $('<span class="count-badge"></span>').text(repoList.length);

        var $row = $('<div class="node-row"></div>')
            .append('<button type="button" class="scope-node-toggle" aria-expanded="true"></button>')
            .append($checkbox)
            .append($label)
            .append($count);

        var $children = $('<ul class="scope-children"></ul>');
        repoList.forEach(function(repo) {
            $children.append(buildRepositoryNode(projectKey, repo));
        });

        var $node = $('<li class="scope-node scope-node-project scope-node-expandable"></li>')
            .attr('data-node-type', 'project')
            .attr('data-project-key', projectKey)
            .attr('data-group-type', groupType)
            .append($row)
            .append($children);

        if (!repoList.length) {
            $node.removeClass('scope-node-expandable');
        }
        return $node;
    }

    function buildRepositoryNode(projectKey, repository) {
        if (!repository || !repository.repositorySlug) {
            return $('<li></li>');
        }
        var repoKey = buildRepoKey(projectKey, repository.repositorySlug);
        var safeId = repoKey ? repoKey.replace(/[^A-Za-z0-9_-]/g, '_') : 'repo';
        var checkboxId = 'scope-repo-' + safeId;

        var $checkbox = $('<input type="checkbox" class="scope-checkbox">')
            .attr('id', checkboxId)
            .attr('data-node-type', 'repository')
            .attr('data-project-key', projectKey)
            .attr('data-repository-slug', repository.repositorySlug);

        var labelText = repository.repositoryName || repository.repositorySlug;
        var $label = $('<label></label>').attr('for', checkboxId).text(labelText);

        var $row = $('<div class="node-row"></div>')
            .append('<span class="scope-node-toggle spacer"></span>')
            .append($checkbox)
            .append($label);

        return $('<li class="scope-node scope-node-repository"></li>')
            .attr('data-node-type', 'repository')
            .attr('data-project-key', projectKey)
            .attr('data-repository-slug', repository.repositorySlug)
            .append($row);
    }

    function applySelectionState($container, scopeState) {
        if (!$container || !$container.length) {
            return;
        }
        scopeState = scopeState || {};
        var isGlobal = scopeState.mode === 'all';
        var $allCheckbox = $container.find('#scope-checkbox-all');

        if ($allCheckbox.length) {
            $allCheckbox.prop('checked', isGlobal).prop('indeterminate', false);
        }

        var $otherCheckboxes = $container.find('.scope-checkbox').not($allCheckbox);
        if (isGlobal) {
            $otherCheckboxes.each(function() {
                $(this)
                    .prop('checked', true)
                    .prop('indeterminate', false)
                    .prop('disabled', true);
            });
            return;
        }

        $otherCheckboxes.prop('disabled', false);
        var selection = scopeState.selectedRepositories || new Set();
        $container.find('.scope-checkbox[data-node-type="repository"]').each(function() {
            var $checkbox = $(this);
            var key = buildRepoKey($checkbox.data('projectKey'), $checkbox.data('repositorySlug'));
            var checked = selection.has(key);
            $checkbox.prop('checked', checked).prop('indeterminate', false);
        });
        updateProjectCheckboxStates($container);
        updateGroupCheckboxStates($container);
        updateGlobalCheckboxIndicator($container);
    }

    function updateProjectCheckboxStates($container) {
        $container.find('.scope-node[data-node-type="project"]').each(function() {
            var $projectNode = $(this);
            var $repoCheckboxes = $projectNode.find('> .scope-children .scope-checkbox[data-node-type="repository"]');
            if (!$repoCheckboxes.length) {
                return;
            }
            var checkedCount = $repoCheckboxes.filter(':checked').length;
            var $projectCheckbox = $projectNode.find('> .node-row .scope-checkbox[data-node-type="project"]');
            if (checkedCount === 0) {
                $projectCheckbox.prop('checked', false).prop('indeterminate', false);
            } else if (checkedCount === $repoCheckboxes.length) {
                $projectCheckbox.prop('checked', true).prop('indeterminate', false);
            } else {
                $projectCheckbox.prop('checked', false).prop('indeterminate', true);
            }
        });
    }

    function updateGroupCheckboxStates($container) {
        $container.find('.scope-node[data-node-type="group"]').each(function() {
            var $groupNode = $(this);
            var $projectCheckboxes = $groupNode.find('> .scope-children .scope-checkbox[data-node-type="project"]');
            if (!$projectCheckboxes.length) {
                return;
            }
            var checkedCount = $projectCheckboxes.filter(':checked').length;
            var indeterminateCount = $projectCheckboxes.filter(function() {
                return $(this).prop('indeterminate');
            }).length;
            var $groupCheckbox = $groupNode.find('> .node-row .scope-checkbox[data-node-type="group"]');
            if (checkedCount === 0 && indeterminateCount === 0) {
                $groupCheckbox.prop('checked', false).prop('indeterminate', false);
            } else if (checkedCount === $projectCheckboxes.length) {
                $groupCheckbox.prop('checked', true).prop('indeterminate', false);
            } else {
                $groupCheckbox.prop('checked', false).prop('indeterminate', true);
            }
        });
    }

    function updateGlobalCheckboxIndicator($container) {
        var $allCheckbox = $container.find('#scope-checkbox-all');
        if (!$allCheckbox.length) {
            return;
        }
        var $repoCheckboxes = $container.find('.scope-checkbox[data-node-type="repository"]');
        if (!$repoCheckboxes.length) {
            $allCheckbox.prop('checked', true).prop('indeterminate', false);
            return;
        }
        var checkedCount = $repoCheckboxes.filter(':checked').length;
        if (checkedCount === 0) {
            $allCheckbox.prop('checked', false).prop('indeterminate', false);
        } else if (checkedCount === $repoCheckboxes.length) {
            $allCheckbox.prop('checked', true).prop('indeterminate', false);
        } else {
            $allCheckbox.prop('checked', false).prop('indeterminate', true);
        }
    }

    function toggleGroupSelection($container, scopeState, $checkbox) {
        var checked = $checkbox.is(':checked');
        var groupType = $checkbox.data('groupType');
        var selector = '.scope-node[data-group-type="' + groupType + '"] .scope-checkbox[data-node-type="repository"]';
        var selection = scopeState.selectedRepositories || new Set();
        $container.find(selector).each(function() {
            var $repoCheckbox = $(this);
            var key = buildRepoKey($repoCheckbox.data('projectKey'), $repoCheckbox.data('repositorySlug'));
            if (!key) {
                return;
            }
            if (checked) {
                selection.add(key);
            } else {
                selection.delete(key);
            }
            $repoCheckbox.prop('checked', checked);
        });
        scopeState.selectedRepositories = selection;
    }

    function toggleProjectSelection($container, scopeState, $checkbox) {
        var checked = $checkbox.is(':checked');
        var projectKey = $checkbox.data('projectKey');
        var selector = '.scope-node[data-project-key="' + projectKey + '"] .scope-checkbox[data-node-type="repository"]';
        var selection = scopeState.selectedRepositories || new Set();
        $container.find(selector).each(function() {
            var $repoCheckbox = $(this);
            var key = buildRepoKey($repoCheckbox.data('projectKey'), $repoCheckbox.data('repositorySlug'));
            if (!key) {
                return;
            }
            if (checked) {
                selection.add(key);
            } else {
                selection.delete(key);
            }
            $repoCheckbox.prop('checked', checked);
        });
        scopeState.selectedRepositories = selection;
    }

    function toggleRepositorySelection(scopeState, $checkbox) {
        var checked = $checkbox.is(':checked');
        var selection = scopeState.selectedRepositories || new Set();
        var key = buildRepoKey($checkbox.data('projectKey'), $checkbox.data('repositorySlug'));
        if (!key) {
            return;
        }
        if (checked) {
            selection.add(key);
        } else {
            selection.delete(key);
        }
        scopeState.selectedRepositories = selection;
    }

    Global.ScopeTree = {
        buildRepoKey: buildRepoKey,
        splitRepoKey: splitRepoKey,
        buildCatalogIndex: buildCatalogIndex,
        setMessage: setMessage,
        render: renderTree,
        applySelectionState: applySelectionState,
        updateProjectCheckboxStates: updateProjectCheckboxStates,
        updateGroupCheckboxStates: updateGroupCheckboxStates,
        updateGlobalCheckboxIndicator: updateGlobalCheckboxIndicator,
        toggleGroupSelection: toggleGroupSelection,
        toggleProjectSelection: toggleProjectSelection,
        toggleRepositorySelection: toggleRepositorySelection
    };
})(AJS.$);
