"""optional station

Revision ID: 0003
Revises: 0002
Create Date: 2026-11-13
"""
from alembic import op
import sqlalchemy as sa


revision = '0003'
down_revision = '0002'
branch_labels = None
depends_on = None


def upgrade() -> None:
    with op.batch_alter_table('guardian_session', schema=None) as batch_op:
        batch_op.alter_column('station_id',
               existing_type=sa.INTEGER(),
               nullable=True)

    with op.batch_alter_table('submission', schema=None) as batch_op:
        batch_op.alter_column('station_id',
               existing_type=sa.INTEGER(),
               nullable=True)


def downgrade() -> None:
    # 제출처 없는 행은 되돌릴 수 없으므로 먼저 지운다(보호자 세션은 다시 로그인하면 된다).
    # 제출 행은 지우면 기록이 사라지므로, 있으면 되돌리기를 거부한다.
    bind = op.get_bind()
    if bind.exec_driver_sql(
        'SELECT count(*) FROM submission WHERE station_id IS NULL'
    ).scalar():
        raise RuntimeError('제출처 없는 제출 기록이 있어 되돌릴 수 없습니다')
    op.execute('DELETE FROM guardian_session WHERE station_id IS NULL')
    with op.batch_alter_table('submission', schema=None) as batch_op:
        batch_op.alter_column('station_id',
               existing_type=sa.INTEGER(),
               nullable=False)

    with op.batch_alter_table('guardian_session', schema=None) as batch_op:
        batch_op.alter_column('station_id',
               existing_type=sa.INTEGER(),
               nullable=False)
