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
