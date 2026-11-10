from alembic import context

from milkbank.models import Base

target_metadata = Base.metadata


def run_migrations_online() -> None:
    connection = context.config.attributes["connection"]
    context.configure(
        connection=connection, target_metadata=target_metadata, render_as_batch=True
    )
    with context.begin_transaction():
        context.run_migrations()


run_migrations_online()
