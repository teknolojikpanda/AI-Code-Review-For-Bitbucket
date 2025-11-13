#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() {
    cat <<'EOF'
Usage: guardrails-cli.sh <command> [reason]

Commands:
  pause            Pause the scheduler immediately.
  drain            Allow active runs to finish but stop new dispatches.
  resume|active    Resume normal scheduling.
  state            Print the current scheduler state JSON.
  alerts           Fetch the latest guardrails alerts (and trigger outbound notifications).
  cleanup-status   Fetch cleanup schedule + recent runs.
  cleanup-run      Trigger an immediate cleanup run using saved schedule.
  cleanup-export   Download retention export (`--format`, `--days`, `--limit`, `--chunks`, `--output`, `--preview`).
  cleanup-integrity Run or repair retention integrity (`--days`, `--sample`, `--repair`).

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
        }
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
    } else {
        local response
        response=$(curl --fail -sS -u "${auth}" "${headers[@]}" "${url}")
        if command -v jq >/dev/null 2>&1; then
            printf '%s\n' "${response}" | jq '.'
        else
            printf '%s\n' "${response}"
        fi
    fi
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
