# contract-validator

Valida eventos de UrbanMove contra los contratos de `docs/contracts/asyncapi/`.

Se usa en tres sitios:

- **Tests de contrato** (CI): comprueban que los 13 eventos tienen esquema y ejemplo, y que los ejemplos son válidos.
- **Generador sintético** (`tools/synthetic-generator/`): valida cada evento que genera.
- **Lambda `analytics-ingestion`**: decide qué eventos pasan de la capa *raw* a la *trusted* del Data Lake.

## Instalación y uso

```bash
cd tools/contract-validator
pip install -r requirements.txt
python -m pytest -q                                              # tests de contrato
python validator.py ../../docs/contracts/asyncapi/examples/*.json   # validar ficheros .json o .jsonl
```

En PowerShell, el comodín `*.json` no se expande solo; usa:

```powershell
python validator.py (Get-ChildItem ..\..\docs\contracts\asyncapi\examples\*.json).FullName
```

Desde código:

```python
from validator import validate_event
errors = validate_event(event)   # [] si es válido; si no, lista de mensajes "ruta: error"
```

`rfc3339-validator` es necesario: sin él, `jsonschema` no comprueba el formato de las fechas y los acepta todos.
