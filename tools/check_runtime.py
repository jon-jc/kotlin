"""Smoke-check the real container against an isolated, explicitly seeded test database."""
import json
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import urlopen

base = sys.argv[1].rstrip("/")
for attempt in range(30):
    try:
        with urlopen(base + "/health/ready", timeout=2) as response:
            assert json.load(response)["ok"] is True
        break
    except (OSError, URLError):
        if attempt == 29:
            raise
        time.sleep(1)

with urlopen(base + "/health/live", timeout=3) as response:
    assert json.load(response)["ok"] is True
with urlopen(base + "/v1/catalog", timeout=3) as response:
    stays = json.load(response)["stays"]
    assert len(stays) == 3 and all(stay["demo"] for stay in stays)
try:
    urlopen(base + "/v1/account", timeout=3)
except HTTPError as response:
    assert response.code == 401
    assert response.headers["Cache-Control"] == "no-store"
    assert json.load(response)["code"] == "UNAUTHENTICATED"
else:
    raise AssertionError("The container exposed an account without authentication")
print("Container smoke: live, ready, seeded catalog, and protected account passed.")
