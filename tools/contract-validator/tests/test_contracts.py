import copy
import json
import sys
from pathlib import Path

import pytest
import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from validator import CONTRACTS_DIR, SCHEMAS_DIR, known_event_types, main, validate_event  # noqa: E402

EXAMPLES_DIR = CONTRACTS_DIR / "examples"
EXPECTED_EVENTS = {
    "UserRegistered", "VehiclePurchased", "VehicleReserved", "ReservationCancelled", "ReservationExpired",
    "RideStarted", "RideFinished", "VehicleBatteryLow", "VehicleWithdrawnForMaintenance",
    "VehicleReturnedToService", "DemandHotspotDetected", "RelocationOrderCreated", "RelocationOrderCompleted",
}


def load_example(event_type: str) -> dict:
    return json.loads((EXAMPLES_DIR / f"{event_type}.json").read_text(encoding="utf-8"))


# ------------------------------------------------------------------ catálogo cerrado
def test_catalog_has_exactly_the_13_official_events():
    assert set(known_event_types()) == EXPECTED_EVENTS


@pytest.mark.parametrize("event_type", sorted(EXPECTED_EVENTS))
def test_every_event_has_payload_message_and_example(event_type):
    assert (SCHEMAS_DIR / "payloads" / f"{event_type}.payload.schema.json").is_file()
    assert (SCHEMAS_DIR / "messages" / f"{event_type}.message.schema.json").is_file()
    assert (EXAMPLES_DIR / f"{event_type}.json").is_file()


def test_asyncapi_document_lists_the_same_events():
    doc = yaml.safe_load((CONTRACTS_DIR / "asyncapi.yaml").read_text(encoding="utf-8"))
    assert doc["asyncapi"].startswith("3.")
    assert set(doc["components"]["messages"]) == EXPECTED_EVENTS
    assert set(doc["channels"]["domainEvents"]["messages"]) == EXPECTED_EVENTS
    for name, message in doc["components"]["messages"].items():
        assert message["x-producer"].startswith("urbanmove.")
        assert "analytics-ingestion" in message["x-consumers"], f"{name} no llega al Data Lake"


# ------------------------------------------------------------------ ejemplos válidos
@pytest.mark.parametrize("event_type", sorted(EXPECTED_EVENTS))
def test_examples_are_valid(event_type):
    assert validate_event(load_example(event_type)) == []


# ------------------------------------------------------------------ casos inválidos
def mutated(event_type, mutate):
    event = copy.deepcopy(load_example(event_type))
    mutate(event)
    return event


def test_missing_correlation_id_is_rejected():
    event = mutated("VehicleReserved", lambda e: e.pop("correlationId"))
    assert any("correlationId" in err for err in validate_event(event))


def test_unknown_event_type_is_rejected():
    event = mutated("VehicleReserved", lambda e: e.update(eventType="VehicleTeleported"))
    assert validate_event(event)


def test_source_must_match_the_official_producer():
    event = mutated("VehicleReserved", lambda e: e.update(source="urbanmove.rides"))
    assert validate_event(event)


def test_extra_payload_fields_are_rejected():
    event = mutated("RideStarted", lambda e: e["payload"].update(isBroken=True))
    assert validate_event(event)


def test_battery_level_out_of_range_is_rejected():
    event = mutated("VehicleBatteryLow", lambda e: e["payload"].update(batteryLevel=150))
    assert validate_event(event)


def test_invalid_uuid_is_rejected():
    event = mutated("RideFinished", lambda e: e.update(eventId="not-a-uuid"))
    assert validate_event(event)


def test_invalid_timestamp_is_rejected():
    event = mutated("RideFinished", lambda e: e.update(timestamp="ayer por la tarde"))
    assert validate_event(event)


def test_unknown_vehicle_type_is_rejected():
    event = mutated("VehicleReserved", lambda e: e["payload"].update(vehicleType="SKATEBOARD"))
    assert validate_event(event)


def test_ride_without_reservation_is_allowed():
    event = mutated("RideStarted", lambda e: e["payload"].update(reservationId=None))
    assert validate_event(event) == []


# ------------------------------------------------------------------ CLI
def test_cli_validates_jsonl_files(tmp_path, capsys):
    good = load_example("VehicleReserved")
    bad = mutated("VehicleReserved", lambda e: e.pop("payload"))
    path = tmp_path / "events.jsonl"
    path.write_text(json.dumps(good) + "\n" + json.dumps(bad) + "\n", encoding="utf-8")
    assert main([str(path)]) == 1
    assert "1/2 eventos válidos" in capsys.readouterr().out
