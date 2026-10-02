"""Ensure client builds cannot embed private keys or pass incomplete release configuration."""
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
wrapper = root / ("gradlew.bat" if os.name == "nt" else "gradlew")
public_names = ("ROAM_API_URL", "ROAM_COMPARISON_API_URL", "SUPABASE_URL", "SUPABASE_PUBLISHABLE_KEY", "STRIPE_PUBLISHABLE_KEY")
valid = {
    "ROAM_API_URL": "https://api.release-fixture.invalid",
    "SUPABASE_URL": "https://auth.release-fixture.invalid",
    "SUPABASE_PUBLISHABLE_KEY": "sb_publishable_release_fixture",
    "STRIPE_PUBLISHABLE_KEY": "pk_live_release_fixture",
}
cases = (
    ("missing configuration", {}, "ROAM_API_URL must be a public HTTPS URL"),
    ("misplaced Stripe secret", {**valid, "STRIPE_PUBLISHABLE_KEY": "sk_test_misplaced_fixture"}, "accepts only a public pk_ key"),
    ("misplaced Supabase secret", {**valid, "SUPABASE_PUBLISHABLE_KEY": "sb_secret_misplaced_fixture"}, "accepts only a public sb_publishable_ key"),
    ("test key in release", {**valid, "STRIPE_PUBLISHABLE_KEY": "pk_test_release_fixture"}, "Release requires a Stripe live publishable key"),
    ("cleartext release endpoint", {**valid, "ROAM_API_URL": "http://api.release-fixture.invalid"}, "ROAM_API_URL must be a public HTTPS URL"),
    ("private IPv4 commerce endpoint", {**valid, "ROAM_API_URL": "https://192.168.1.20"}, "ROAM_API_URL must be a public HTTPS URL"),
    ("IPv6 auth endpoint", {**valid, "SUPABASE_URL": "https://[::1]"}, "SUPABASE_URL must be a public HTTPS URL"),
    ("credentials in comparison URL", {**valid, "ROAM_COMPARISON_API_URL": "https://api.release-fixture.invalid/?api_key=private_fixture"}, "ROAM_COMPARISON_API_URL must not contain credentials, queries, or fragments"),
    ("cleartext comparison endpoint", {**valid, "ROAM_COMPARISON_API_URL": "http://api.release-fixture.invalid"}, "ROAM_COMPARISON_API_URL must be a public HTTPS URL"),
)
for name, configuration, expected in cases:
    # Explicit empty env overrides also isolate tests from an ignored local properties file.
    environment = {**os.environ, **dict.fromkeys(public_names, ""), **configuration}
    result = subprocess.run(
        [str(wrapper), ":app:validateReleaseConfiguration", "--console=plain", "--max-workers=2"],
        cwd=root, env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, timeout=180, encoding="utf-8", errors="replace",
    )
    if result.returncode == 0 or expected not in result.stdout:
        raise AssertionError(f"Release guard failed for {name}:\n{result.stdout}")
    print(f"Rejected: {name}")

for name, value, expected_success in (
    ("missing comparison endpoint", "", False),
    ("local comparison endpoint", "http://127.0.0.1:8080", False),
    ("HTTPS unspecified comparison address", "https://0.0.0.0", False),
    ("HTTPS IPv6 comparison address", "https://[::1]", False),
    ("numeric comparison address", "https://2130706433", False),
    ("short comparison address", "https://127.1", False),
    ("local comparison hostname", "https://machine.local", False),
    ("localdomain comparison hostname", "https://machine.localdomain", False),
    ("trailing-dot localhost comparison hostname", "https://localhost.", False),
    ("comparison without commerce keys", "https://search.release-fixture.invalid", True),
):
    environment = {**os.environ, **dict.fromkeys(public_names, ""), "ROAM_COMPARISON_API_URL": value}
    result = subprocess.run(
        [str(wrapper), ":app:validateComparisonReleaseConfiguration", "--console=plain", "--max-workers=2"],
        cwd=root, env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, timeout=180, encoding="utf-8", errors="replace",
    )
    if (result.returncode == 0) != expected_success or (
        not expected_success and "ROAM_COMPARISON_API_URL must be a public HTTPS URL" not in result.stdout
    ):
        raise AssertionError(f"Comparison guard failed for {name}:\n{result.stdout}")
    print(f"{'Accepted' if expected_success else 'Rejected'}: {name}")
