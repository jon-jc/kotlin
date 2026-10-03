"""Verify the standalone search container without database, identity or payment credentials."""
from datetime import datetime, timedelta, timezone
import json
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

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

today = datetime.now(timezone.utc).date()
query = {
    "destination": "Kyoto, Japan",
    "checkIn": str(today + timedelta(days=7)),
    "checkOut": str(today + timedelta(days=10)),
    "adults": 2,
    "childAges": [],
    "currency": "JPY",
    "market": "us",
    "scope": "ALL",
}
request = Request(base + "/v1/comparison/search", data=json.dumps(query).encode(),
                  headers={"Content-Type": "application/json"}, method="POST")
with urlopen(request, timeout=5) as response:
    assert response.headers["Cache-Control"] == "no-store"
    result = json.load(response)
assert result["query"] == query
assert result["properties"] == []
assert {source["kind"] for source in result["sourceStatuses"]} == {"HOTEL", "RENTAL"}
assert all(source["status"] == "NOT_CONFIGURED" for source in result["sourceStatuses"])

try:
    urlopen(base + "/v1/account", timeout=3)
except HTTPError as response:
    assert response.code == 404
else:
    raise AssertionError("Standalone search unexpectedly exposed commerce routes")
print("Comparison runtime: no commerce credentials, honest unavailable sources, no cached/fake prices.")
