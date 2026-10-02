"""Ensure client builds cannot embed private keys or pass incomplete release configuration."""
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
wrapper = root / ("gradlew.bat" if os.name == "nt" else "gradlew")
public_names = ("ROAM_API_URL", "SUPABASE_URL", "SUPABASE_PUBLISHABLE_KEY", "STRIPE_PUBLISHABLE_KEY")
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
