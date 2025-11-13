#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLI="${SCRIPT_DIR}/guardrails-cli.sh"

usage() {
    cat <<'EOF'
Usage: guardrails-rollout.sh <enable|disable> [options]

Commands:
  enable   Resume the scheduler and update scope in a single, auditable step.
  disable  Pause (or drain) the scheduler to roll back guardrails quickly.

Enable options:
  --scope all|repositories    Scope mode to apply before resuming (default all).
  --repo PROJECT/slug         Repository allow-list entries (repeatable, required when scope=repositories).
  --reason "text"             Reason recorded in scheduler audit logs.

Disable options:
  --drain                     Use drain mode (let in-flight reviews finish) instead of pause.
  --reason "text"             Reason recorded in scheduler audit logs.

Environment variables:
  GUARDRAILS_BASE_URL   Bitbucket base URL (e.g., https://bitbucket.example.com)
  GUARDRAILS_AUTH       Credentials for basic auth (user:token or user:password)
EOF
}

require_env() {
    local name="$1" value="${2:-}"
    if [[ -z "${value}" ]]; then
        echo "Error: ${name} is not set." >&2
        usage
        exit 1
    fi
}

ensure_cli() {
    if [[ ! -x "${CLI}" ]]; then
        echo "Cannot find guardrails CLI at ${CLI}. Ensure scripts/guardrails-cli.sh is executable." >&2
        exit 1
    fi
}

enable_guardrails() {
    local scope="all"
    local reason="Automated guardrails rollout"
    local repos=()

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --scope)
                scope="${2:-}"
                shift || true
                ;;
            --scope=*)
                scope="${1#*=}"
                ;;
            --repo|--repository)
                repos+=("${2:-}")
                shift || true
                ;;
            --repo=*|--repository=*)
                repos+=("${1#*=}")
                ;;
            --reason)
                reason="${2:-}"
                shift || true
                ;;
            --reason=*)
                reason="${1#*=}"
                ;;
            --help|-h)
                usage
                exit 0
                ;;
            *)
                echo "Unknown enable option: $1" >&2
                usage
                exit 1
                ;;
        esac
        shift || true
    done

    scope="${scope,,}"
    if [[ "${scope}" != "all" && "${scope}" != "repositories" ]]; then
        echo "Invalid scope '${scope}'. Expected 'all' or 'repositories'." >&2
        exit 1
    fi

    if [[ "${scope}" == "repositories" && ${#repos[@]} -eq 0 ]]; then
        echo "At least one --repo PROJECT/slug is required when scope=repositories." >&2
        exit 1
    fi

    echo ">>> Applying scope (${scope})"
    local scope_args=(scope --mode "${scope}")
    if [[ "${scope}" == "repositories" ]]; then
        for repo in "${repos[@]}"; do
            scope_args+=("--repo" "${repo}")
        done
    fi
    "${CLI}" "${scope_args[@]}"

    echo ">>> Resuming scheduler"
    "${CLI}" resume "${reason}"
    echo ">>> Current scheduler state"
    "${CLI}" state
}

disable_guardrails() {
    local action="pause"
    local reason="Automated guardrails rollback"

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --drain)
                action="drain"
                ;;
            --reason)
                reason="${2:-}"
                shift || true
                ;;
            --reason=*)
                reason="${1#*=}"
                ;;
            --help|-h)
                usage
                exit 0
                ;;
            *)
                echo "Unknown disable option: $1" >&2
                usage
                exit 1
                ;;
        esac
        shift || true
    done

    echo ">>> Requesting scheduler ${action}"
    "${CLI}" "${action}" "${reason}"
    echo ">>> Current scheduler state"
    "${CLI}" state
}

main() {
    if [[ $# -lt 1 ]]; then
        usage
        exit 1
    fi

    require_env "GUARDRAILS_BASE_URL" "${GUARDRAILS_BASE_URL:-}"
    require_env "GUARDRAILS_AUTH" "${GUARDRAILS_AUTH:-}"
    ensure_cli

    local command="$1"
    shift || true

    case "${command}" in
        enable)
            enable_guardrails "$@"
            ;;
        disable)
            disable_guardrails "$@"
            ;;
        --help|-h)
            usage
            ;;
        *)
            echo "Unknown command: ${command}" >&2
            usage
            exit 1
            ;;
    esac
}

main "$@"
