"""admin ml

Revision ID: 0004
Revises: 0003
Create Date: 2026-11-14
"""
from alembic import op
import sqlalchemy as sa

revision = '0004'
down_revision = '0003'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table('setting',
    sa.Column('key', sa.String(), nullable=False),
    sa.Column('value', sa.Integer(), nullable=False),
    sa.PrimaryKeyConstraint('key')
    )
    with op.batch_alter_table('patient', schema=None) as batch_op:
        batch_op.add_column(sa.Column('erased_at', sa.DateTime(), nullable=True))

    with op.batch_alter_table('stock_record', schema=None) as batch_op:
        batch_op.add_column(sa.Column('ml', sa.Integer(), nullable=True))

    with op.batch_alter_table('submission', schema=None) as batch_op:
        batch_op.add_column(sa.Column('ml', sa.Integer(), nullable=True))
        batch_op.add_column(sa.Column('confirmed_ml', sa.Integer(), nullable=True))


def downgrade() -> None:
    with op.batch_alter_table('submission', schema=None) as batch_op:
        batch_op.drop_column('confirmed_ml')
        batch_op.drop_column('ml')

    with op.batch_alter_table('stock_record', schema=None) as batch_op:
        batch_op.drop_column('ml')

    with op.batch_alter_table('patient', schema=None) as batch_op:
        batch_op.drop_column('erased_at')

    op.drop_table('setting')
