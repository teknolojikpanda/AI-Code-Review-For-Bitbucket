#!/usr/bin/env bash

set -euo pipefail

usage() {
    cat <<'EOF'
Usage: guardrails-cli.sh <command> [reason]

Commands:
  pause            Pause the scheduler immediately.
  drain            Allow active runs to finish but stop new dispatches.
  resume|active    Resume normal scheduling.
  state            Print the current scheduler state JSON.
  alerts           Fetch the latest guardrails alerts (and trigger outbound notifications).
  scope            View or update repository scope (use --help for options).
  cleanup-status   Fetch cleanup schedule + recent runs.
  cleanup-run      Trigger an immediate cleanup run using saved schedule.
  cleanup-export   Download retention export (`--format`, `--days`, `--limit`, `--chunks`, `--output`, `--preview`).
  cleanup-integrity Run or repair retention integrity (`--days`, `--sample`, `--repair`).
  burst-list       List burst credits (`--include-expired`).
  burst-grant      Grant a burst credit (`--scope`, `--project`, `--repo`, `--tokens`, `--duration`, `--reason`, `--note`).
  burst-revoke     Revoke a burst credit by id (`--note` optional).

Environment:
  GUARDRAILS_BASE_URL   Base Bitbucket URL (e.g. https://bitbucket.example.com).
  GUARDRAILS_AUTH       Credentials for basic auth (user:password or user:token).

Examples:
  GUARDRAILS_BASE_URL=https://bitbucket.example.com \
  GUARDRAILS_AUTH=admin:token \
    ./scripts/guardrails-cli.sh pause "Nightly maintenance"

EOF
}

require_env() {
    local name="$1" value="$2"
    if [[ -z "${value}" ]]; then
        echo "Error: ${name} is not set." >&2
        usage
        exit 1
    fi
}

print_json() {
    local payload="$1"
    if command -v jq >/dev/null 2>&1; then
        printf '%s\n' "${payload}" | jq '.'
    else
        printf '%s\n' "${payload}"
    fi
}

json_escape() {
    printf '%s' "${1:-}" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

cleanup_export() {
    local base_url="$1"
    local auth="$2"
    shift 2 || true

    local format="json"
    local retention_days=""
    local limit=""
    local include_chunks="false"
    local output=""
    local preview="false"

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --format=*)
                format="${1#*=}"
                ;;
            --format)
                format="${2:-json}"
                shift || true
                ;;
            --days=*|--retentionDays=*)
                retention_days="${1#*=}"
                ;;
            --days|--retentionDays)
                retention_days="${2:-}"
                shift || true
                ;;
            --limit=*)
                limit="${1#*=}"
                ;;
            --limit)
                limit="${2:-}"
                shift || true
                ;;
            --chunks|--include-chunks)
                include_chunks="true"
                ;;
            --no-chunks)
                include_chunks="false"
                ;;
            --output=*)
                output="${1#*=}"
                ;;
            --output)
                output="${2:-}"
                shift || true
                ;;
            --preview)
                preview="true"
                ;;
            --help|-h)
                cat <<'EOHELP'
cleanup-export options:
  --format json|csv         Output format (default json).
  --days N                  Retention window in days (default uses schedule).
  --limit N                 Max number of rows to export (default 100).
  --chunks                  Include chunk telemetry (larger payload).
  --output /path/file       Destination file (CSV only, defaults to guardrails-retention-export.csv).
  --preview                 Fetch JSON preview instead of downloading.
EOHELP
                return 0
                ;;
            *)
                echo "Unknown cleanup-export option: $1" >&2
                usage
                exit 1
                ;;
        esac
        shift || true
    done

    format="${format,,}"
    if [[ "${preview}" == "true" && "${format}" != "json" ]]; then
        echo "Preview is only available for JSON; overriding format to json." >&2
        format="json"
    fi

    local query_params=()
    if [[ -n "${retention_days}" ]]; then
        query_params+=("retentionDays=${retention_days}")
    fi
    if [[ -n "${limit}" ]]; then
        query_params+=("limit=${limit}")
    fi
    if [[ "${include_chunks}" == "true" ]]; then
        query_params+=("includeChunks=true")
    fi
    local query_string=""
    if [[ ${#query_params[@]} -gt 0 ]]; then
        local IFS='&'
        query_string="${query_params[*]}"
    fi

    local headers=(-H "Content-Type: application/json")
    if [[ "${preview}" == "true" ]]; then
        local preview_endpoint="/rest/ai-reviewer/1.0/history/cleanup/export"
        local preview_url="${base_url}${preview_endpoint}"
        if [[ -n "${query_string}" ]]; then
            preview_url="${preview_url}?${query_string}"
        fi
        local response
        response=$(curl --fail -sS -u "${auth}" "${headers[@]}" "${preview_url}")
        if command -v jq >/dev/null 2>&1; then
            printf '%s\n' "${response}" | jq '.'
        else
            printf '%s\n' "${response}"
        fi
        return 0
    fi

    local download_endpoint="/rest/ai-reviewer/1.0/history/cleanup/export/download"
    local download_query="format=${format}"
    if [[ -n "${query_string}" ]]; then
        download_query="${download_query}&${query_string}"
    fi
    local download_url="${base_url}${download_endpoint}?${download_query}"

    if [[ "${format}" == "csv" ]]; then
        local file="${output:-guardrails-retention-export.csv}"
        curl --fail -sS -u "${auth}" \
            -H "Accept: text/csv" \
            "${download_url}" -o "${file}"
        echo "Retention export saved to ${file}"
    else
        local response
        response=$(curl --fail -sS -u "${auth}" "${headers[@]}" "${download_url}")
        if command -v jq >/dev/null 2>&1; then
            printf '%s\n' "${response}" | jq '.'
        else
            printf '%s\n' "${response}"
        fi
    fi
}

cleanup_integrity() {
    local base_url="$1"
    local auth="$2"
    shift 2 || true

    local retention_days=""
    local sample=""
    local repair="false"

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --days=*|--retentionDays=*)
                retention_days="${1#*=}"
                ;;
            --days|--retentionDays)
                retention_days="${2:-}"
                shift || true
                ;;
            --sample=*)
                sample="${1#*=}"
                ;;
            --sample)
                sample="${2:-}"
                shift || true
                ;;
            --repair)
                repair="true"
                ;;
            --help|-h)
                cat <<'EOHELP'
cleanup-integrity options:
  --days N          Retention window in days (defaults to schedule).
  --sample N        Sample size (default 100).
  --repair          Apply repairs instead of running a read-only report.
EOHELP
                return 0
                ;;
        *)
            echo "Unknown cleanup-integrity option: $1" >&2
            usage
            exit 1
            ;;
        esac
        shift || true
    done

    local query_params=()
    if [[ -n "${retention_days}" ]]; then
        query_params+=("retentionDays=${retention_days}")
    fi
    if [[ -n "${sample}" ]]; then
        query_params+=("sample=${sample}")
    fi
    local query_string=""
    if [[ ${#query_params[@]} -gt 0 ]]; then
        local IFS='&'
        query_string="${query_params[*]}"
    fi

    local endpoint="/rest/ai-reviewer/1.0/history/cleanup/integrity"
    local headers=(-H "Content-Type: application/json")
    local url="${base_url}${endpoint}"
    if [[ -n "${query_string}" ]]; then
        url="${url}?${query_string}"
    fi

    if [[ "${repair}" == "true" ]]; then
        local payload_parts=()
        if [[ -n "${retention_days}" ]]; then
            payload_parts+=("\"retentionDays\":${retention_days}")
        fi
        if [[ -n "${sample}" ]]; then
            payload_parts+=("\"sample\":${sample}")
        fi
        payload_parts+=("\"repair\":true")
        local IFS=','
        local payload="{${payload_parts[*]}}"
        local response
        response=$(curl --fail -sS -u "${auth}" "${headers[@]}" -X POST -d "${payload}" "${url}")
        if command -v jq >/dev/null 2>&1; then
            printf '%s\n' "${response}" | jq '.'
        else
            printf '%s\n' "${response}"
        fi
    else
        local response
        response=$(curl --fail -sS -u "${auth}" "${headers[@]}" "${url}")
        if command -v jq >/dev/null 2>&1; then
            printf '%s\n' "${response}" | jq '.'
        else
            printf '%s\n' "${response}"
        fi
    fi
}

burst_list() {
    local base_url="$1"
    local auth="$2"
    shift 2 || true

    local include_expired="false"

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --include-expired|--all)
                include_expired="true"
                ;;
            --help|-h)
                cat <<'EOHELP'
burst-list options:
  --include-expired   Display consumed/expired burst credits as well as active ones.
EOHELP
                return 0
                ;;
            *)
                echo "Unknown burst-list option: $1" >&2
                usage
                exit 1
                ;;
        esac
        shift || true
    done

    local url="${base_url}/rest/ai-reviewer/1.0/automation/burst-credits"
    if [[ "${include_expired}" == "true" ]]; then
        url="${url}?includeExpired=true"
    fi
    local response
    response=$(curl --fail -sS -u "${auth}" -H "Accept: application/json" "${url}")
    print_json "${response}"
}

burst_grant() {
    local base_url="$1"
    local auth="$2"
    shift 2 || true

    local scope="repository"
    local project=""
    local repo=""
    local tokens="5"
    local duration="60"
    local reason=""
    local note=""

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --scope=*)
                scope="${1#*=}"
                ;;
            --scope)
                scope="${2:-}"
                shift || true
                ;;
            --project=*)
                project="${1#*=}"
                ;;
            --project)
                project="${2:-}"
                shift || true
                ;;
            --repo=*|--repository=*)
                repo="${1#*=}"
                ;;
            --repo|--repository)
                repo="${2:-}"
                shift || true
                ;;
            --tokens=*)
                tokens="${1#*=}"
                ;;
            --tokens)
                tokens="${2:-}"
                shift || true
                ;;
            --duration=*|--duration-minutes=*)
                duration="${1#*=}"
                ;;
            --duration|--duration-minutes)
                duration="${2:-}"
                shift || true
                ;;
            --reason=*)
                reason="${1#*=}"
                ;;
            --reason)
                reason="${2:-}"
                shift || true
                ;;
            --note=*)
                note="${1#*=}"
                ;;
            --note)
                note="${2:-}"
                shift || true
                ;;
            --help|-h)
                cat <<'EOHELP'
burst-grant options:
  --scope repository|project   Scope for the credit (default repository).
  --repo PROJECT/slug          Target repository (required for repository scope).
  --project KEY                Target project key (required for project scope).
  --tokens N                   Extra review tokens granted (default 5).
  --duration minutes           Validity duration in minutes (default 60).
  --reason text                Short reason stored with the credit.
  --note text                  Optional operator note.
EOHELP
                return 0
                ;;
            *)
                echo "Unknown burst-grant option: $1" >&2
                usage
                exit 1
                ;;
        esac
        shift || true
    done

    local normalized_scope
    case "${scope,,}" in
        project)
            normalized_scope="project"
            ;;
        repo|repository|"")
            normalized_scope="repository"
            ;;
        *)
            echo "Invalid scope '${scope}'. Use 'project' or 'repository'." >&2
            exit 1
            ;;
    esac

    local project_key="${project}"
    local repo_slug=""
    if [[ "${normalized_scope}" == "repository" ]]; then
        if [[ -z "${repo}" ]]; then
            echo "--repo PROJECT/slug is required for repository scope." >&2
            exit 1
        fi
        if [[ "${repo}" == */* ]]; then
            local repo_project="${repo%%/*}"
            repo_slug="${repo##*/}"
            if [[ -z "${project_key}" ]]; then
                project_key="${repo_project}"
            fi
        else
            repo_slug="${repo}"
        fi
        repo_slug=$(printf '%s' "${repo_slug}" | tr '[:upper:]' '[:lower:]')
    else
        if [[ -z "${project_key}" ]]; then
            echo "--project KEY is required for project scope burst credits." >&2
            exit 1
        fi
    fi
    if [[ -n "${project_key}" ]]; then
        project_key=$(printf '%s' "${project_key}" | tr '[:lower:]' '[:upper:]')
    fi

    if ! [[ "${tokens}" =~ ^[0-9]+$ ]]; then
        echo "--tokens must be a positive integer." >&2
        exit 1
    fi
    if ! [[ "${duration}" =~ ^[0-9]+$ ]]; then
        echo "--duration must be a positive integer (minutes)." >&2
        exit 1
    fi

    local payload_parts=()
    payload_parts+=("\"scope\":\"${normalized_scope}\"")
    payload_parts+=("\"tokens\":${tokens}")
    payload_parts+=("\"durationMinutes\":${duration}")
    if [[ -n "${project_key}" ]]; then
        payload_parts+=("\"projectKey\":\"$(json_escape "${project_key}")\"")
    fi
    if [[ -n "${repo_slug}" ]]; then
        payload_parts+=("\"repositorySlug\":\"$(json_escape "${repo_slug}")\"")
    fi
    if [[ -n "${reason}" ]]; then
        payload_parts+=("\"reason\":\"$(json_escape "${reason}")\"")
    fi
    if [[ -n "${note}" ]]; then
        payload_parts+=("\"note\":\"$(json_escape "${note}")\"")
    fi
    local IFS=','
    local payload="{${payload_parts[*]}}"

    local response
    response=$(curl --fail -sS -u "${auth}" \
        -H "Content-Type: application/json" \
        -X POST \
        -d "${payload}" \
        "${base_url}/rest/ai-reviewer/1.0/automation/burst-credits")
    print_json "${response}"
}

burst_revoke() {
    local base_url="$1"
    local auth="$2"
    shift 2 || true

    local note=""
    local id=""

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --note=*)
                note="${1#*=}"
                ;;
            --note)
                note="${2:-}"
                shift || true
                ;;
            --help|-h)
                cat <<'EOHELP'
burst-revoke usage:
  guardrails-cli.sh burst-revoke <id> [--note "reason"]
EOHELP
                return 0
                ;;
            --*)
                echo "Unknown burst-revoke option: $1" >&2
                usage
                exit 1
                ;;
            *)
                id="$1"
                shift || true
                break
                ;;
        esac
        shift || true
    done

    if [[ -z "${id}" ]]; then
        echo "burst-revoke requires a burst credit id." >&2
        usage
        exit 1
    fi
    if ! [[ "${id}" =~ ^[0-9]+$ ]]; then
        echo "Burst credit id must be numeric." >&2
        exit 1
    fi

    local payload="{}"
    if [[ -n "${note}" ]]; then
        payload=$(printf '{"note":"%s"}' "$(json_escape "${note}")")
    fi

    curl --fail -sS -u "${auth}" \
        -H "Content-Type: application/json" \
        -X DELETE \
        -d "${payload}" \
        "${base_url}/rest/ai-reviewer/1.0/automation/burst-credits/${id}"
    echo "Burst credit ${id} revoked."
}

scope_command() {
    local base_url="$1"
    local auth="$2"
    shift 2 || true

    local mode=""
    local repos=()
    local list_only="false"

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --mode=*)
                mode="${1#*=}"
                ;;
            --mode)
                mode="${2:-}"
                shift || true
                ;;
            --repo=*)
                repos+=("${1#*=}")
                ;;
            --repo|--repository)
                repos+=("${2:-}")
                shift || true
                ;;
            --list|--show)
                list_only="true"
                ;;
            --help|-h)
                cat <<'EOHELP'
scope options:
  --list            Print the current scope selection.
  --mode all        Enable AI reviews for every repository (clears allow-list).
  --mode repositories --repo PRJ/repo --repo PRJ/repo2
                    Restrict scope to the listed repositories (repeat --repo).
EOHELP
                return 0
                ;;
            *)
                echo "Unknown scope option: $1" >&2
                usage
                exit 1
                ;;
        esac
        shift || true
    done

    local scope_url="${base_url}/rest/ai-reviewer/1.0/config/scope"
    if [[ "${list_only}" == "true" || -z "${mode}" ]]; then
        curl --fail -sS -u "${auth}" -H "Content-Type: application/json" "${scope_url}" | jq '.'
        return 0
    fi

    mode="${mode,,}"
    if [[ "${mode}" != "all" && "${mode}" != "repositories" ]]; then
        echo "Invalid scope mode: ${mode}. Expected 'all' or 'repositories'." >&2
        exit 1
    fi

    local payload="{\"mode\":\"${mode}\""
    if [[ "${mode}" == "repositories" ]]; then
        if [[ ${#repos[@]} -eq 0 ]]; then
            echo "At least one --repo is required when mode=repositories." >&2
            exit 1
        fi
        local repo_entries=()
        for repo in "${repos[@]}"; do
            IFS='/' read -r projectKey repositorySlug <<<"${repo}"
            if [[ -z "${projectKey}" || -z "${repositorySlug}" ]]; then
                echo "Invalid repo format (expected PROJECT/repo): ${repo}" >&2
                exit 1
            fi
            repo_entries+=("{\"projectKey\":\"${projectKey}\",\"repositorySlug\":\"${repositorySlug}\"}")
        done
        local IFS=','
        payload+=" ,\"repositories\":[${repo_entries[*]}]"
    fi
    payload+="}"

    curl --fail -sS -u "${auth}" -H "Content-Type: application/json" \
        -X POST -d "${payload}" "${scope_url}" | jq '.'
}
main() {
    if [[ $# -lt 1 ]]; then
        usage
        exit 1
    fi

    local base_url="${GUARDRAILS_BASE_URL:-}"
    local auth="${GUARDRAILS_AUTH:-}"
    require_env "GUARDRAILS_BASE_URL" "${base_url}"
    require_env "GUARDRAILS_AUTH" "${auth}"

    local command="$1"
    shift || true
    local reason="${1:-}"

    local endpoint=""
    local method="POST"
    local payload="{}"
    local custom_payload="false"

    case "${command}" in
        pause|PAUSE)
            endpoint="/rest/ai-reviewer/1.0/automation/rollout/pause"
            ;;
        drain|DRAIN)
            endpoint="/rest/ai-reviewer/1.0/automation/rollout/drain"
            ;;
        resume|active|RESUME|ACTIVE)
            endpoint="/rest/ai-reviewer/1.0/automation/rollout/active"
            ;;
        state|STATE)
            endpoint="/rest/ai-reviewer/1.0/automation/rollout/state"
            method="GET"
            ;;
        alerts|ALERTS)
            endpoint="/rest/ai-reviewer/1.0/alerts"
            method="GET"
            ;;
        scope)
            scope_command "${base_url}" "${auth}" "$@"
            return 0
            ;;
        cleanup-status)
            endpoint="/rest/ai-reviewer/1.0/history/cleanup/status"
            method="GET"
            ;;
        cleanup-run)
            endpoint="/rest/ai-reviewer/1.0/history/cleanup"
            method="POST"
            payload='{"runNow":true}'
            custom_payload="true"
            ;;
        cleanup-export)
            cleanup_export "${base_url}" "${auth}" "$@"
            return 0
            ;;
        cleanup-integrity)
            cleanup_integrity "${base_url}" "${auth}" "$@"
            return 0
            ;;
        burst-list)
            burst_list "${base_url}" "${auth}" "$@"
            return 0
            ;;
        burst-grant)
            burst_grant "${base_url}" "${auth}" "$@"
            return 0
            ;;
        burst-revoke)
            burst_revoke "${base_url}" "${auth}" "$@"
            return 0
            ;;
        *)
            echo "Unknown command: ${command}" >&2
            usage
            exit 1
            ;;
    esac

    if [[ "${method}" == "POST" ]]; then
        local body="${payload}"
        if [[ "${custom_payload}" != "true" && -n "${reason}" ]]; then
            body=$(printf '{"reason":"%s"}' "$(printf "%s" "${reason}" | sed 's/"/\\"/g')")
        fi
        curl --fail -sS -u "${auth}" \
            -H "Content-Type: application/json" \
            -X POST \
            -d "${body}" \
            "${base_url}${endpoint}" | jq '.'
    else
        curl --fail -sS -u "${auth}" \
            -H "Content-Type: application/json" \
            "${base_url}${endpoint}" | jq '.'
    fi
}

main "$@"
