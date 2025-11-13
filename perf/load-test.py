#!/usr/bin/env python3
"""
Synthetic load generator for AI Review Guardrails.

This script calls a representative set of Guardrails REST endpoints in bursts
to validate scheduler/limiter behaviour before GA rollouts. It does *not*
enqueue real AI reviews; instead it exercises monitoring + automation APIs.

Usage:
    python perf/load-test.py --base-url https://bitbucket.example.com \
        --auth user:token --duration 120 --concurrency 4
"""

import argparse
import concurrent.futures
import os
import random
import string
import sys
import threading
import time
from typing import Dict, Tuple

import requests

# Endpoints we hammer during the perf run.
QUEUE_URL = "/rest/ai-reviewer/1.0/progress/admin/queue"
RUNTIME_URL = "/rest/ai-reviewer/1.0/monitoring/runtime"
ALERTS_URL = "/rest/ai-reviewer/1.0/alerts"
ROLLIN_URL = "/rest/ai-reviewer/1.0/automation/rollout/active"
ROLLOUT_URL = "/rest/ai-reviewer/1.0/automation/rollout/pause"

STATS_LOCK = threading.Lock()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Guardrails perf smoke test")
    parser.add_argument("--base-url", required=True, help="Bitbucket base URL")
    parser.add_argument(
        "--auth",
        default=os.getenv("GUARDRAILS_AUTH"),
        help="user:password (defaults to GUARDRAILS_AUTH env)",
    )
    parser.add_argument(
        "--duration",
        type=int,
        default=120,
        help="Test duration in seconds (default 120)",
    )
    parser.add_argument(
        "--concurrency",
        type=int,
        default=4,
        help="Number of worker threads (default 4)",
    )
    parser.add_argument(
        "--verify",
        action="store_true",
        help="Verify TLS certificates (default: off -> useful for localhost)",
    )
    return parser.parse_args()


def auth_tuple(value: str) -> Tuple[str, str]:
    if ":" not in value:
        raise ValueError("Auth must be user:token")
    user, token = value.split(":", 1)
    return user, token


def random_reason(prefix: str) -> str:
    suffix = "".join(random.choices(string.ascii_lowercase + string.digits, k=6))
    return f"{prefix}-{suffix}"


class LoadStats:
    def __init__(self) -> None:
        self.counts: Dict[str, int] = {}
        self.errors: Dict[str, int] = {}

    def record(self, name: str, success: bool) -> None:
        with STATS_LOCK:
            self.counts[name] = self.counts.get(name, 0) + 1
            if not success:
                self.errors[name] = self.errors.get(name, 0) + 1

    def summary(self) -> str:
        with STATS_LOCK:
            lines = ["=== Load Test Summary ==="]
            for op, total in sorted(self.counts.items()):
                err = self.errors.get(op, 0)
                lines.append(f"{op:20s} total={total:5d} errors={err}")
            return "\n".join(lines)


def call_endpoint(session: requests.Session, method: str, url: str, **kwargs) -> bool:
    try:
        resp = session.request(method, url, timeout=10, **kwargs)
        resp.raise_for_status()
        return True
    except requests.RequestException as exc:
        print(f"[WARN] {method} {url} failed: {exc}", file=sys.stderr)
        return False


def worker(
    session: requests.Session,
    base_url: str,
    stop_at: float,
    stats: LoadStats,
) -> None:
    while time.time() < stop_at:
        # Pull monitoring data
        for name, path in (
            ("queue", QUEUE_URL),
            ("runtime", RUNTIME_URL),
            ("alerts", ALERTS_URL),
        ):
            ok = call_endpoint(session, "GET", base_url + path)
            stats.record(name, ok)

        # Flip scheduler quickly (pause/resume)
        reason = random_reason("perf")
        for name, path in (("pause", ROLLOUT_URL), ("resume", ROLLIN_URL)):
            ok = call_endpoint(
                session,
                "POST",
                base_url + path,
                json={"reason": reason},
            )
            stats.record(name, ok)

        # Random sleep to mimic bursty load
        time.sleep(random.uniform(0.5, 1.5))


def main() -> None:
    args = parse_args()
    if not args.auth:
        print("GUARDRAILS_AUTH environment variable or --auth is required.", file=sys.stderr)
        sys.exit(1)

    base_url = args.base_url.rstrip("/")
    auth = auth_tuple(args.auth)
    stats = LoadStats()
    stop_at = time.time() + max(args.duration, 5)

    session_kwargs = {
        "auth": auth,
        "verify": args.verify,
        "headers": {"Content-Type": "application/json"},
    }

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = []
        for _ in range(args.concurrency):
            sess = requests.Session()
            sess.auth = auth
            sess.verify = args.verify
            sess.headers.update({"Content-Type": "application/json"})
            futures.append(pool.submit(worker, sess, base_url, stop_at, stats))

        for future in concurrent.futures.as_completed(futures):
            future.result()

    print(stats.summary())


if __name__ == "__main__":
    main()
