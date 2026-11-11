from alembic import context

from milkbank.migrate import check_foreign_keys, foreign_keys_off
from milkbank.models import Base

target_metadata = Base.metadata


def _check_after_step(**kwargs) -> None:
    # 버전 기록 직후, 그 마이그레이션의 트랜잭션이 닫히기 전에 검사한다.
    check_foreign_keys(context.get_bind())


def run_migrations_online() -> None:
    connection = context.config.attributes["connection"]
    # SQLite의 alembic 기본값은 transactional_ddl=False라 단계마다 바로 커밋된다.
    # True로 켜서 DDL과 버전 기록을 한 트랜잭션으로 묶고, 실패하면 함께 되돌린다.
    # 마이그레이션 한 개마다 트랜잭션을 나눠 앞 단계 성공분은 남긴다.
    context.configure(
        connection=connection,
        target_metadata=target_metadata,
        render_as_batch=True,
        transactional_ddl=True,
        transaction_per_migration=True,
        on_version_apply=_check_after_step,
    )
    # 표 재생성(batch) 마이그레이션이 외래 키에 막히지 않도록 끄고, 끝나면 검사한다.
    with foreign_keys_off(connection):
        with context.begin_transaction():
            context.run_migrations()
            check_foreign_keys(connection)  # 커밋 전에 외래 키 위반 행이 없는지 한 번 더 확인, 있으면 RuntimeError로 전부 되돌림(단계별 검사는 on_version_apply)


run_migrations_online()
