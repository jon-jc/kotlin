"""Validate the platform-neutral fixture and fail-closed schema boundaries."""
import copy
import json
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker, ValidationError

root = Path(__file__).resolve().parents[1]
schema = json.loads((root / "contracts/receipt-v1.schema.json").read_text())
fixture = json.loads((root / "core/src/test/resources/receipt-v1.json").read_text())
Draft202012Validator.check_schema(schema)
validator = Draft202012Validator(schema, format_checker=FormatChecker())
validator.validate(fixture)
validator.validate({**fixture, "futureField": "additive"})

for change in (
    lambda data: data.pop("version"),
    lambda data: data.update(status="charged"),
    lambda data: data["total"].update(minor=54432),
    lambda data: data["total"].update(currency="EUR"),
    lambda data: data.update(checkIn="2026-02-30"),
):
    invalid = copy.deepcopy(fixture)
    change(invalid)
    try:
        validator.validate(invalid)
    except ValidationError:
        continue
    raise AssertionError(f"Invalid receipt was accepted: {invalid}")
print("Receipt schema: fixture, additive evolution, and five rejection cases passed.")
