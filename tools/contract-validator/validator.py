"""Validador de eventos de UrbanMove contra los contratos de docs/contracts/asyncapi/.

Uso como librería (generador sintético, Lambda de ingesta raw -> trusted, tests de contrato):

    from validator import validate_event
    errors = validate_event(event_dict)   # lista vacía = evento válido

Uso desde línea de comandos (acepta .json con un evento o una lista, y .jsonl):

    python validator.py ../../docs/contracts/asyncapi/examples/*.json
"""
from __future__ import annotations

import json
import os
import sys
from functools import lru_cache
from pathlib import Path
from urllib.parse import urlparse
from urllib.request import url2pathname

from jsonschema import Draft202012Validator
from referencing import Registry, Resource

DEFAULT_CONTRACTS_DIR = Path(__file__).resolve().parents[2] / "docs" / "contracts" / "asyncapi"
CONTRACTS_DIR = Path(os.environ.get("URBANMOVE_CONTRACTS_DIR", DEFAULT_CONTRACTS_DIR)).resolve()
SCHEMAS_DIR = CONTRACTS_DIR / "schemas"


def _retrieve(uri: str) -> Resource:
    """Carga bajo demanda los esquemas referenciados con rutas relativas ($ref)."""
    path = Path(url2pathname(urlparse(uri).path))
    return Resource.from_contents(json.loads(path.read_text(encoding="utf-8")))


_REGISTRY = Registry(retrieve=_retrieve)


@lru_cache(maxsize=None)
def _validator_for(schema_path: Path) -> Draft202012Validator:
    return Draft202012Validator(
        {"$ref": schema_path.as_uri()},
        registry=_REGISTRY,
        format_checker=Draft202012Validator.FORMAT_CHECKER,
    )


@lru_cache(maxsize=None)
def known_event_types() -> tuple[str, ...]:
    envelope = json.loads((SCHEMAS_DIR / "envelope.schema.json").read_text(encoding="utf-8"))
    return tuple(envelope["properties"]["eventType"]["enum"])


def _format(errors) -> list[str]:
    out = []
    for err in sorted(errors, key=lambda e: list(map(str, e.absolute_path))):
        where = "/".join(str(p) for p in err.absolute_path) or "<raíz>"
        out.append(f"{where}: {err.message}")
    return out


def validate_event(event: dict) -> list[str]:
    """Devuelve la lista de errores del evento (vacía si es válido)."""
    errors = _format(_validator_for(SCHEMAS_DIR / "envelope.schema.json").iter_errors(event))
    if errors:
        return errors
    message_schema = SCHEMAS_DIR / "messages" / f"{event['eventType']}.message.schema.json"
    return _format(_validator_for(message_schema).iter_errors(event))


def _load_events(path: Path) -> list[dict]:
    text = path.read_text(encoding="utf-8")
    if path.suffix == ".jsonl":
        return [json.loads(line) for line in text.splitlines() if line.strip()]
    data = json.loads(text)
    return data if isinstance(data, list) else [data]


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 2
    total = invalid = 0
    for name in argv:
        for idx, event in enumerate(_load_events(Path(name))):
            total += 1
            errors = validate_event(event)
            if errors:
                invalid += 1
                label = event.get("eventId", f"#{idx}") if isinstance(event, dict) else f"#{idx}"
                print(f"[INVÁLIDO] {name} · {label}")
                for e in errors:
                    print(f"    - {e}")
    print(f"{total - invalid}/{total} eventos válidos")
    return 1 if invalid else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
