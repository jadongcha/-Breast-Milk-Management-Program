from datetime import date, timedelta

from milkbank.judge import judge

TODAY = date(2026, 11, 10)


def _j(**kw):
    base = dict(
        balance=4, first_use_day=None, recent_use_packs=0, today=TODAY,
        avg_days=3, days_threshold=2, packs_threshold=3,
    )
    return judge(**{**base, **kw})


def test_new_patient_by_packs():
    j = _j(balance=3)
    assert (j.status, j.daily_avg, j.days_left) == ("low", None, None)
    assert _j(balance=4).status == "enough"


def test_established_by_days():
    first = TODAY - timedelta(days=3)
    j = _j(first_use_day=first, recent_use_packs=6, balance=4)
    assert (j.daily_avg, j.days_left, j.status) == (2.0, 2.0, "low")
    j = _j(first_use_day=first, recent_use_packs=6, balance=5)
    assert (j.daily_avg, j.days_left, j.status) == (2.0, 2.5, "enough")


def test_two_days_of_use_is_still_new():
    j = _j(first_use_day=TODAY - timedelta(days=2), recent_use_packs=6, balance=4)
    assert (j.daily_avg, j.days_left, j.status) == (None, None, "enough")
    assert _j(first_use_day=TODAY - timedelta(days=2), recent_use_packs=6, balance=3).status == "low"


def test_zero_average_falls_back_to_packs():
    j = _j(first_use_day=TODAY - timedelta(days=5), recent_use_packs=0, balance=2)
    assert (j.daily_avg, j.days_left, j.status) == (0.0, None, "low")


def test_empty():
    assert _j(balance=0).status == "empty"
    j = _j(balance=0, first_use_day=TODAY - timedelta(days=5), recent_use_packs=6)
    assert j.status == "empty"


def test_exact_threshold_is_low_despite_float_rounding():
    # 17 / (17 / 7) == 7.000000000000001 이지만 정확히 7일치이므로 부족이다.
    j = _j(
        first_use_day=TODAY - timedelta(days=9), recent_use_packs=17, balance=17,
        avg_days=7, days_threshold=7,
    )
    assert j.status == "low"
