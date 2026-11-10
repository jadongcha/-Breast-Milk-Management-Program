import pytest

from milkbank.security import (
    check_password_policy,
    generate_temp_password,
    hash_password,
    hash_token,
    new_token,
    verify_password,
)


def test_hash_and_verify():
    h = hash_password("abcd1234")
    assert h != "abcd1234"
    assert verify_password(h, "abcd1234") and not verify_password(h, "abcd12345")


def test_verify_garbage_hash_is_false():
    assert verify_password("not-a-hash", "x") is False


@pytest.mark.parametrize("pw,msg", [
    ("abc123", "비밀번호는 8자 이상이어야 합니다"),
    ("abcdefgh", "비밀번호에 영문과 숫자를 모두 포함해야 합니다"),
    ("12345678", "비밀번호에 영문과 숫자를 모두 포함해야 합니다"),
    ("가나다라마바사12", "비밀번호에 영문과 숫자를 모두 포함해야 합니다"),
    ("abcd1234", None),
])
def test_password_policy(pw, msg):
    assert check_password_policy(pw) == msg


def test_temp_password_passes_policy():
    for _ in range(200):
        p = generate_temp_password()
        assert len(p) == 10 and check_password_policy(p) is None


def test_token_hash_matches():
    raw, h = new_token()
    assert hash_token(raw) == h and len(raw) >= 32
