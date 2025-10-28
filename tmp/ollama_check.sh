#!/bin/bash
set -euo pipefail
curl -s -m 5 http://host.docker.internal:11434/api/tags >/tmp/ollama_tags.json && cat /tmp/ollama_tags.json | jq '.[].model'
