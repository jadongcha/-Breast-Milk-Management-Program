import base64
import hashlib
import ipaddress
import json
import sys
from datetime import UTC, datetime, timedelta

import pytest
from cryptography import x509
from cryptography.hazmat.primitives import serialization
from cryptography.x509 import load_pem_x509_certificate

from milkbank import setup_tls as setup_tls_cli
from milkbank import tls as tls_mod
from milkbank.tls import (
    TlsError,
    parse_host,
    qr_payload,
    server_url,
    setup_tls,
)

NOW = datetime(2026, 11, 10, tzinfo=UTC)


def _spki_pin(cert):
    der = cert.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    return "sha256/" + base64.b64encode(hashlib.sha256(der).digest()).decode()


def _san(cert):
    return cert.extensions.get_extension_for_class(x509.SubjectAlternativeName).value


def test_cert_has_all_hosts_and_10_years(tmp_path):
    r = setup_tls(tmp_path, ["192.168.0.10", "milkbank.local"], 8443, False, NOW)
    cert = load_pem_x509_certificate((tmp_path / "server.crt").read_bytes())
    san = _san(cert)
    assert ipaddress.ip_address("192.168.0.10") in san.get_values_for_type(x509.IPAddress)
    assert "milkbank.local" in san.get_values_for_type(x509.DNSName)
    assert cert.not_valid_after_utc - NOW == timedelta(days=3650)
    assert NOW - cert.not_valid_before_utc == timedelta(days=1)
    assert cert.subject.get_attributes_for_oid(x509.NameOID.COMMON_NAME)[0].value == "192.168.0.10"
    bc = cert.extensions.get_extension_for_class(x509.BasicConstraints).value
    assert bc.ca is False
    assert r.url == "https://192.168.0.10:8443"


def test_now_with_microseconds_is_truncated(tmp_path):
    now = NOW.replace(microsecond=123456)
    setup_tls(tmp_path, ["192.168.0.10"], 8443, False, now)
    cert = load_pem_x509_certificate((tmp_path / "server.crt").read_bytes())
    assert cert.not_valid_after_utc - NOW == timedelta(days=3650)


def test_pin_matches_public_key(tmp_path):
    r = setup_tls(tmp_path, ["192.168.0.10"], 8443, False, NOW)
    cert = load_pem_x509_certificate((tmp_path / "server.crt").read_bytes())
    assert r.pin == _spki_pin(cert)


def test_rerun_with_new_host_keeps_pin(tmp_path):
    r1 = setup_tls(tmp_path, ["192.168.0.10"], 8443, False, NOW)
    r2 = setup_tls(tmp_path, ["192.168.0.20"], 8443, False, NOW)
    assert r2.pin == r1.pin and r2.key_reused is True
    assert r1.key_reused is False
    cert = load_pem_x509_certificate((tmp_path / "server.crt").read_bytes())
    assert ipaddress.ip_address("192.168.0.20") in _san(cert).get_values_for_type(x509.IPAddress)


def test_reuse_leaves_key_file_untouched(tmp_path):
    setup_tls(tmp_path, ["192.168.0.10"], 8443, False, NOW)
    before = (tmp_path / "server.key").read_bytes()
    setup_tls(tmp_path, ["192.168.0.20"], 8443, False, NOW)
    assert (tmp_path / "server.key").read_bytes() == before


def test_new_key_changes_pin(tmp_path):
    r1 = setup_tls(tmp_path, ["192.168.0.10"], 8443, False, NOW)
    r2 = setup_tls(tmp_path, ["192.168.0.10"], 8443, True, NOW)
    assert r2.pin != r1.pin
    assert r2.key_reused is False


def test_creates_missing_tls_dir(tmp_path):
    d = tmp_path / "a" / "tls"
    setup_tls(d, ["192.168.0.10"], 8443, False, NOW)
    assert (d / "server.key").exists()


def test_qr_payload_exact():
    assert qr_payload("https://192.168.0.10:8443", "sha256/abc") == (
        '{"type":"milkbank-server","v":1,"url":"https://192.168.0.10:8443","pin":"sha256/abc"}'
    )


def test_qr_png_written(tmp_path):
    r = setup_tls(tmp_path, ["192.168.0.10", "milkbank.local"], 8443, False, NOW)
    assert (tmp_path / "server-qr.png").read_bytes().startswith(b"\x89PNG\r\n\x1a\n")
    assert r.qr_path == tmp_path / "server-qr.png"
    info = json.loads((tmp_path / "server.json").read_text(encoding="utf-8"))
    assert info == {
        "hosts": ["192.168.0.10", "milkbank.local"],
        "port": 8443,
        "url": r.url,
        "pin": r.pin,
    }


def test_ipv6_url():
    assert server_url("fd00::10", 8443) == "https://[fd00::10]:8443"


def test_ipv6_extra_host_in_cert(tmp_path):
    r = setup_tls(tmp_path, ["192.168.0.10", "fd00::10"], 8443, False, NOW)
    cert = load_pem_x509_certificate((tmp_path / "server.crt").read_bytes())
    assert ipaddress.ip_address("fd00::10") in _san(cert).get_values_for_type(x509.IPAddress)
    assert r.url == "https://192.168.0.10:8443"


def test_ipv6_first_host_rejected(tmp_path):
    with pytest.raises(TlsError, match="첫 번째 서버 주소는 IPv4 주소나 이름이어야 합니다"):
        setup_tls(tmp_path, ["fd00::10", "192.168.0.10"], 8443, False, NOW)


def test_parse_host_strips():
    assert parse_host("  192.168.0.10 ") == "192.168.0.10"


@pytest.mark.parametrize("bad", ["", "  ", "192.168.0.10 x", "-bad.local", "a..b", "bad-.local", "fe80::1%a b,c", "fe80::1%]@evil.com:1#", "fe80::1%eth0", "192.168.0.256", "192.168.000.010", "10.0.0.1.5", "a" * 250 + ".com"])
def test_bad_host_rejected(bad):
    with pytest.raises(TlsError, match="서버 주소가 올바르지 않습니다"):
        parse_host(bad)


def test_no_hosts_rejected(tmp_path):
    with pytest.raises(TlsError, match=r"서버 주소\(--host\)를 하나 이상 입력해 주세요"):
        setup_tls(tmp_path, [], 8443, False, NOW)


@pytest.mark.parametrize("port", [0, 70000])
def test_bad_port_rejected(tmp_path, port):
    with pytest.raises(TlsError, match="포트 번호가 올바르지 않습니다"):
        setup_tls(tmp_path, ["192.168.0.10"], port, False, NOW)


def test_cli_success_and_error(tmp_path, monkeypatch, capsys):
    monkeypatch.setenv("MILKBANK_DB", str(tmp_path / "m.db"))
    monkeypatch.setattr(sys, "argv", ["milkbank-setup-tls", "--host", "192.168.0.10"])
    setup_tls_cli.main()
    out = capsys.readouterr().out
    assert "서버 등록 QR:" in out
    assert "서버 주소: https://192.168.0.10:8443" in out
    assert "인증서 확인값: sha256/" in out
    assert "이 QR을 출력해 신생아실에 보관하세요" in out
    assert "새로 만들었습니다" not in out
    assert (tmp_path / "tls" / "server.crt").exists()

    monkeypatch.setattr(sys, "argv", ["x", "--host", "192.168.0.10", "--new-key"])
    setup_tls_cli.main()
    assert "인증서 키를 새로 만들었습니다" in capsys.readouterr().out

    monkeypatch.setattr(sys, "argv", ["x", "--host", "bad host"])
    with pytest.raises(SystemExit) as e:
        setup_tls_cli.main()
    assert e.value.code == 1
    assert "서버 주소가 올바르지 않습니다" in capsys.readouterr().err


def test_corrupt_key_file_is_tls_error(tmp_path):
    (tmp_path / "server.key").write_text("garbage")
    with pytest.raises(TlsError, match="기존 인증서 키 파일을 읽을 수 없습니다. --new-key로 다시 만드세요"):
        setup_tls(tmp_path, ["192.168.0.10"], 8443, False, NOW)


def test_encrypted_key_file_is_tls_error(tmp_path):
    from cryptography.hazmat.primitives.asymmetric import ec

    pem = ec.generate_private_key(ec.SECP256R1()).private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.BestAvailableEncryption(b"pw"),
    )
    (tmp_path / "server.key").write_bytes(pem)
    with pytest.raises(TlsError, match="기존 인증서 키 파일"):
        setup_tls(tmp_path, ["192.168.0.10"], 8443, False, NOW)


def test_cli_corrupt_key_no_traceback(tmp_path, monkeypatch, capsys):
    monkeypatch.setenv("MILKBANK_DB", str(tmp_path / "m.db"))
    (tmp_path / "tls").mkdir()
    (tmp_path / "tls" / "server.key").write_text("garbage")
    monkeypatch.setattr(sys, "argv", ["x", "--host", "192.168.0.10"])
    with pytest.raises(SystemExit) as e:
        setup_tls_cli.main()
    assert e.value.code == 1
    err = capsys.readouterr().err
    assert "기존 인증서 키 파일을 읽을 수 없습니다" in err
    assert "Traceback" not in err


def test_long_dns_name_works_without_cn(tmp_path):
    host = ".".join(["a" * 20] * 3 + ["com"])  # 71 chars
    assert len(host) > 64
    setup_tls(tmp_path, [host], 8443, False, NOW)
    cert = load_pem_x509_certificate((tmp_path / "server.crt").read_bytes())
    assert host in _san(cert).get_values_for_type(x509.DNSName)
    assert cert.subject.get_attributes_for_oid(x509.NameOID.COMMON_NAME) == []


def test_name_over_253_rejected():
    host = ".".join(["a" * 60] * 5 + ["com"])
    assert len(host) > 253
    with pytest.raises(TlsError, match="서버 주소가 올바르지 않습니다"):
        parse_host(host)


def test_failed_new_key_run_keeps_old_key(tmp_path, monkeypatch):
    setup_tls(tmp_path, ["192.168.0.10"], 8443, False, NOW)
    before = (tmp_path / "server.key").read_bytes()

    def boom(*a, **k):
        raise TlsError("실패")

    monkeypatch.setattr(tls_mod, "build_certificate", boom)
    with pytest.raises(TlsError):
        setup_tls(tmp_path, ["192.168.0.10"], 8443, True, NOW)
    assert (tmp_path / "server.key").read_bytes() == before
