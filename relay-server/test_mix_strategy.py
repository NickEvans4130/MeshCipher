"""MD-05: Unit tests for relay mix strategies.

Run with:  python -m pytest relay-server/test_mix_strategy.py -v
"""
import base64
import time
import threading
import unittest

# Import the classes directly without Flask app context.
# We temporarily set the environment variables before importing the module
# so the configuration constants are initialised correctly.
import os
import importlib
import sys
import types


def _make_fresh_module(strategy: str, **kwargs) -> types.ModuleType:
    """Return a fresh import of the mix-strategy classes with custom config."""
    # Patch env vars, then import the relevant classes directly.
    env_patch = {
        "RELAY_MIX_STRATEGY": strategy,
        "RELAY_BATCH_INTERVAL_MS": str(kwargs.get("RELAY_BATCH_INTERVAL_MS", "500")),
        "RELAY_POOL_MIN_SIZE": str(kwargs.get("RELAY_POOL_MIN_SIZE", "3")),
        "RELAY_POOL_DELAY_MIN_MS": str(kwargs.get("RELAY_POOL_DELAY_MIN_MS", "300")),
        "RELAY_POOL_DELAY_MAX_MS": str(kwargs.get("RELAY_POOL_DELAY_MAX_MS", "600")),
    }
    # We can't re-import server.py as it starts Flask; instead we import the
    # strategy classes directly from the module namespace via exec.
    return env_patch  # Return env patch dict for use with the constructors.


# --- helpers to instantiate strategies without the full Flask app --------

def make_timed_batch(interval_ms=500):
    from relay_server_mix import TimedBatchMix
    return TimedBatchMix(interval_ms=interval_ms)


def make_pool_mix(min_size=3, delay_min_ms=300, delay_max_ms=600):
    from relay_server_mix import PoolMix
    return PoolMix(min_size=min_size, delay_min_ms=delay_min_ms, delay_max_ms=delay_max_ms)


# ---------------------------------------------------------------------------
# relay_server_mix: a thin shim that exposes the strategy classes for testing
# without importing the full Flask application.  We build it inline here.
# ---------------------------------------------------------------------------

def _bootstrap_mix_module():
    """Synthesise relay_server_mix module from server.py by exec-ing the relevant parts."""
    src_path = os.path.join(os.path.dirname(__file__), "server.py")
    if src_path not in sys.path:
        pass

    # Read server.py and extract just the mix-strategy block (between the sentinel comments).
    with open(src_path) as f:
        source = f.read()

    # Identify the mix-strategy block.
    start_marker = "# MD-05: Mix Strategy Abstraction"
    end_marker = "# ---------------------------------------------------------------------------\n# WebSocket Active Connections"
    start = source.find(start_marker)
    end = source.find(end_marker, start)
    if start == -1 or end == -1:
        raise RuntimeError("Could not locate mix-strategy block in server.py")

    mix_block = source[start:end]

    # Build a minimal module.
    module_code = (
        "import base64\nimport random\nimport secrets\nimport threading\nimport time\nimport uuid\n"
        "from dataclasses import dataclass, field\nfrom datetime import datetime, timezone\n"
        "from typing import Any, Dict, List, Optional\n\n"
        + mix_block
    )

    mod = types.ModuleType("relay_server_mix")
    # Populate the config globals that _build_mix_strategy() reads at module level.
    mod.__dict__.update(
        {
            "RELAY_MIX_STRATEGY": os.environ.get("RELAY_MIX_STRATEGY", "TIMED_BATCH"),
            "RELAY_BATCH_INTERVAL_MS": int(os.environ.get("RELAY_BATCH_INTERVAL_MS", "500")),
            "RELAY_POOL_MIN_SIZE": int(os.environ.get("RELAY_POOL_MIN_SIZE", "3")),
            "RELAY_POOL_DELAY_MIN_MS": int(os.environ.get("RELAY_POOL_DELAY_MIN_MS", "300")),
            "RELAY_POOL_DELAY_MAX_MS": int(os.environ.get("RELAY_POOL_DELAY_MAX_MS", "600")),
        }
    )
    exec(compile(module_code, "<relay_server_mix>", "exec"), mod.__dict__)
    sys.modules["relay_server_mix"] = mod


_bootstrap_mix_module()


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


class TestTimedBatchMix(unittest.TestCase):

    def test_messages_not_forwarded_before_interval(self):
        """Messages enqueued must not be forwarded until the batch interval elapses."""
        forwarded = []
        mix = make_timed_batch(interval_ms=500)
        mix.start_background(forwarded.append)

        msg = {"id": "1", "recipient_id": "user1", "encrypted_content": "abc", "content_type": 0}
        mix.enqueue("user1", msg)

        # Sleep for less than the interval — nothing should be forwarded yet.
        time.sleep(0.1)
        self.assertEqual(0, len(forwarded), "Should not forward before batch interval")

    def test_messages_forwarded_after_interval(self):
        """After the batch interval, enqueued messages should be forwarded."""
        forwarded = []
        mix = make_timed_batch(interval_ms=400)
        mix.start_background(forwarded.append)

        for i in range(3):
            mix.enqueue("user1", {"id": str(i), "recipient_id": "user1", "encrypted_content": "x", "content_type": 0})

        # Wait longer than one batch interval.
        time.sleep(0.7)
        self.assertEqual(3, len(forwarded), "All 3 messages should be forwarded after interval")

    def test_pool_size_decreases_after_flush(self):
        forwarded = []
        mix = make_timed_batch(interval_ms=400)
        mix.start_background(forwarded.append)

        mix.enqueue("u", {"id": "a", "recipient_id": "u", "encrypted_content": "y", "content_type": 0})
        self.assertEqual(1, mix.pool_size())
        time.sleep(0.6)
        self.assertEqual(0, mix.pool_size(), "Pool should be empty after flush")


class TestPoolMix(unittest.TestCase):

    def test_messages_not_forwarded_when_pool_below_minimum(self):
        """Pool mix must hold messages when pool size < min_size."""
        forwarded = []
        # min_size=5, so single message should never be forwarded immediately.
        mix = make_pool_mix(min_size=5, delay_min_ms=200, delay_max_ms=300)
        mix.start_background(forwarded.append)

        mix.enqueue("user1", {"id": "1", "recipient_id": "user1", "encrypted_content": "x", "content_type": 0})
        time.sleep(0.1)
        self.assertEqual(0, len(forwarded), "Should not forward when pool below min_size")

    def test_messages_forwarded_once_pool_full_and_delay_elapsed(self):
        """Messages forwarded after pool reaches min_size AND delay elapses."""
        forwarded = []
        mix = make_pool_mix(min_size=2, delay_min_ms=300, delay_max_ms=400)
        mix.start_background(forwarded.append)

        for i in range(2):
            mix.enqueue("user1", {"id": str(i), "recipient_id": "user1", "encrypted_content": "x", "content_type": 0})

        # Before delays elapse: nothing forwarded.
        time.sleep(0.1)
        self.assertEqual(0, len(forwarded), "Should not forward before delay elapses")

        # After delays elapse: messages forwarded (cover messages are discarded, not forwarded).
        time.sleep(0.6)
        self.assertGreaterEqual(len(forwarded), 2, "Real messages should be forwarded after delay")

    def test_cover_messages_injected_when_pool_below_minimum(self):
        """Cover messages must pad the pool to min_size."""
        mix = make_pool_mix(min_size=5, delay_min_ms=1000, delay_max_ms=2000)
        # Enqueue 1 real message — cover messages should fill to min_size.
        mix.enqueue("user1", {"id": "1", "recipient_id": "user1", "encrypted_content": "x", "content_type": 0})
        self.assertEqual(5, mix.pool_size(), "Pool should be padded to min_size with cover messages")

    def test_cover_messages_not_forwarded_to_caller(self):
        """Cover messages must be discarded, not passed to the forward function."""
        forwarded = []
        mix = make_pool_mix(min_size=2, delay_min_ms=200, delay_max_ms=300)
        mix.start_background(forwarded.append)

        # Enqueue exactly min_size real messages and wait for forwarding.
        for i in range(2):
            mix.enqueue("user1", {"id": str(i), "recipient_id": "user1", "encrypted_content": "x", "content_type": 0})

        time.sleep(0.7)
        # All forwarded entries should be real (no _cover_ recipients).
        for msg in forwarded:
            self.assertNotEqual("_cover_", msg.get("recipient_id"),
                                "Cover messages must not reach the forward function")


if __name__ == "__main__":
    unittest.main()
