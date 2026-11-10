from alembic import context

from milkbank.migrate import check_foreign_keys, foreign_keys_off
from milkbank.models import Base

target_metadata = Base.metadata


def run_migrations_online() -> None:
    connection = context.config.attributes["connection"]
    context.configure(
        connection=connection, target_metadata=target_metadata, render_as_batch=True
    )
    # 표 재생성(batch) 마이그레이션이 외래 키에 막히지 않도록 끄고, 끝나면 검사한다.
    with foreign_keys_off(connection):
        with context.begin_transaction():
            context.run_migrations()
            check_foreign_keys(connection)  # 깨져 있으면 이 마이그레이션이 되돌려진다


run_migrations_online()
