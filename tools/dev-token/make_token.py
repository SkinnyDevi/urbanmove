"""Genera un JWT de pruebas (HS256) firmado con el JWT_SECRET del fichero .env.

Solo para desarrollo local, mientras no exista el servicio identity (bloque A).
Uso:  python tools/dev-token/make_token.py maintenance usr-tech-01
      python tools/dev-token/make_token.py admin usr-admin-01
"""
import base64
import hashlib
import hmac
import json
import sys
import time
from pathlib import Path


def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def read_secret() -> str:
    env = Path(__file__).resolve().parents[2] / ".env"
    for line in env.read_text(encoding="utf-8").splitlines():
        if line.startswith("JWT_SECRET="):
            return line.split("=", 1)[1].strip()
    sys.exit("No hay JWT_SECRET en " + str(env))


def main() -> None:
    role = sys.argv[1] if len(sys.argv) > 1 else "maintenance"
    subject = sys.argv[2] if len(sys.argv) > 2 else "usr-tech-01"
    now = int(time.time())
    header = b64(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
    claims = b64(json.dumps({"sub": subject, "roles": [role], "iat": now, "exp": now + 8 * 3600}).encode())
    signing_input = f"{header}.{claims}".encode()
    signature = b64(hmac.new(read_secret().encode(), signing_input, hashlib.sha256).digest())
    print(f"{header}.{claims}.{signature}")


if __name__ == "__main__":
    main()
