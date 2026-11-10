"""비밀번호 해시·정책, 임시 비밀번호, 세션 토큰 유틸."""
import hashlib
import secrets
import string

from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError

_hasher = PasswordHasher()  # argon2id 기본값

_LETTERS = string.ascii_letters
_DIGITS = string.digits
_TEMP_LENGTH = 10


def hash_password(pw: str) -> str:
    return _hasher.hash(pw)


def verify_password(password_hash: str, pw: str) -> bool:
    try:
        return _hasher.verify(password_hash, pw)
    except (VerificationError, InvalidHashError):
        return False


def check_password_policy(pw: str) -> str | None:
    if len(pw) < 8:
        return "비밀번호는 8자 이상이어야 합니다"
    has_letter = any(c in _LETTERS for c in pw)
    has_digit = any(c in _DIGITS for c in pw)
    if not (has_letter and has_digit):
        return "비밀번호에 영문과 숫자를 모두 포함해야 합니다"
    return None


def generate_temp_password() -> str:
    chars = [secrets.choice(_LETTERS), secrets.choice(_DIGITS)]
    pool = _LETTERS + _DIGITS
    chars += [secrets.choice(pool) for _ in range(_TEMP_LENGTH - 2)]
    secrets.SystemRandom().shuffle(chars)
    return "".join(chars)


def hash_token(raw: str) -> str:
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def new_token() -> tuple[str, str]:
    raw = secrets.token_urlsafe(32)
    return raw, hash_token(raw)
