import base64
import hashlib
import ipaddress
import json
import os
import re
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path

import qrcode
from qrcode.image.pure import PyPNGImage
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

_LABEL = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")


class TlsError(Exception):
    pass


def _is_ip(host: str) -> bool:
    try:
        ipaddress.ip_address(host)
    except ValueError:
        return False
    return True


def parse_host(raw: str) -> str:
    host = raw.strip()
    if host and not _is_ip(host):
        labels = host.split(".")
        if len(host) <= 253 and all(_LABEL.match(label) for label in labels):
            return host
        host = ""
    if not host:
        raise TlsError(f"서버 주소가 올바르지 않습니다: {raw}")
    return host


def load_or_create_key(path: Path, new_key: bool) -> tuple[ec.EllipticCurvePrivateKey, bool]:
    if path.exists() and not new_key:
        key = serialization.load_pem_private_key(path.read_bytes(), password=None)
        if not isinstance(key, ec.EllipticCurvePrivateKey):
            raise TlsError(f"인증서 키 형식이 올바르지 않습니다: {path}")
        return key, True
    key = ec.generate_private_key(ec.SECP256R1())
    pem = key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(pem)
    return key, False


def build_certificate(
    key: ec.EllipticCurvePrivateKey, hosts: list[str], now: datetime
) -> x509.Certificate:
    now = now.astimezone(UTC).replace(microsecond=0)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, hosts[0])])
    names: list[x509.GeneralName] = [
        x509.IPAddress(ipaddress.ip_address(h)) if _is_ip(h) else x509.DNSName(h) for h in hosts
    ]
    return (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - timedelta(days=1))
        .not_valid_after(now + timedelta(days=3650))
        .add_extension(x509.SubjectAlternativeName(names), critical=False)
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .sign(key, hashes.SHA256())
    )


def spki_pin(key: ec.EllipticCurvePrivateKey) -> str:
    der = key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    return "sha256/" + base64.b64encode(hashlib.sha256(der).digest()).decode("ascii")


def server_url(host: str, port: int) -> str:
    if _is_ip(host) and ipaddress.ip_address(host).version == 6:
        return f"https://[{host}]:{port}"
    return f"https://{host}:{port}"


def qr_payload(url: str, pin: str) -> str:
    return json.dumps(
        {"type": "milkbank-server", "v": 1, "url": url, "pin": pin}, separators=(",", ":")
    )


def write_qr_png(payload: str, path: Path) -> None:
    img = qrcode.make(
        payload, image_factory=PyPNGImage, error_correction=qrcode.constants.ERROR_CORRECT_M
    )
    with path.open("wb") as f:
        img.save(f)


@dataclass
class SetupResult:
    url: str
    pin: str
    qr_path: Path
    key_reused: bool


def setup_tls(
    tls_dir: Path, hosts: list[str], port: int, new_key: bool, now: datetime
) -> SetupResult:
    if not hosts:
        raise TlsError("서버 주소(--host)를 하나 이상 입력해 주세요")
    if not 1 <= port <= 65535:
        raise TlsError("포트 번호가 올바르지 않습니다")
    hosts = [parse_host(h) for h in hosts]
    tls_dir.mkdir(parents=True, exist_ok=True)
    key, reused = load_or_create_key(tls_dir / "server.key", new_key)
    cert = build_certificate(key, hosts, now)
    pin = spki_pin(key)
    url = server_url(hosts[0], port)
    (tls_dir / "server.crt").write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    qr_path = tls_dir / "server-qr.png"
    write_qr_png(qr_payload(url, pin), qr_path)
    info = {"hosts": hosts, "port": port, "url": url, "pin": pin}
    (tls_dir / "server.json").write_text(
        json.dumps(info, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return SetupResult(url=url, pin=pin, qr_path=qr_path, key_reused=reused)
