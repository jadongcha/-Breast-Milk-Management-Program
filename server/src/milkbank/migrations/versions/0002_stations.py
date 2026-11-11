"""stations

Revision ID: 0002
Revises: 0001
Create Date: 2026-11-11
"""
from alembic import op
import sqlalchemy as sa


revision = '0002'
down_revision = '0001'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table('station',
    sa.Column('id', sa.Integer(), nullable=False),
    sa.Column('name', sa.String(), nullable=False),
    sa.Column('token_hash', sa.String(), nullable=False),
    sa.Column('active', sa.Boolean(), nullable=False),
    sa.Column('created_at', sa.DateTime(), nullable=False),
    sa.Column('created_by', sa.Integer(), nullable=False),
    sa.ForeignKeyConstraint(['created_by'], ['staff.id'], ),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('token_hash')
    )
    with op.batch_alter_table('station', schema=None) as batch_op:
        batch_op.create_index('uq_station_active_name', ['name'], unique=True, sqlite_where=sa.text('active = 1'))

    op.create_table('guardian_session',
    sa.Column('id', sa.Integer(), nullable=False),
    sa.Column('token_hash', sa.String(), nullable=False),
    sa.Column('phone', sa.String(), nullable=False),
    sa.Column('station_id', sa.Integer(), nullable=False),
    sa.Column('created_at', sa.DateTime(), nullable=False),
    sa.Column('last_seen_at', sa.DateTime(), nullable=False),
    sa.ForeignKeyConstraint(['station_id'], ['station.id'], ),
    sa.PrimaryKeyConstraint('id'),
    sa.UniqueConstraint('token_hash')
    )
    op.create_table('submission',
    sa.Column('id', sa.Integer(), nullable=False),
    sa.Column('patient_id', sa.Integer(), nullable=False),
    sa.Column('station_id', sa.Integer(), nullable=False),
    sa.Column('count', sa.Integer(), nullable=False),
    sa.Column('submitted_at', sa.DateTime(), nullable=False),
    sa.Column('status', sa.Enum('pending', 'confirmed', 'rejected', name='submissionstatus', native_enum=False, create_constraint=True, length=20), nullable=False),
    sa.Column('confirmed_count', sa.Integer(), nullable=True),
    sa.Column('handled_by', sa.Integer(), nullable=True),
    sa.Column('handled_at', sa.DateTime(), nullable=True),
    sa.Column('intake_record_id', sa.Integer(), nullable=True),
    sa.Column('reject_reason', sa.Enum('not_received', 'other', name='rejectreason', native_enum=False, create_constraint=True, length=20), nullable=True),
    sa.Column('memo', sa.String(), nullable=True),
    sa.ForeignKeyConstraint(['handled_by'], ['staff.id'], ),
    sa.ForeignKeyConstraint(['intake_record_id'], ['stock_record.id'], ),
    sa.ForeignKeyConstraint(['patient_id'], ['patient.id'], ),
    sa.ForeignKeyConstraint(['station_id'], ['station.id'], ),
    sa.PrimaryKeyConstraint('id')
    )
    with op.batch_alter_table('submission', schema=None) as batch_op:
        batch_op.create_index('ix_submission_patient_id_status', ['patient_id', 'status'], unique=False)
        batch_op.create_index('ix_submission_status_submitted_at', ['status', 'submitted_at'], unique=False)



def downgrade() -> None:
    with op.batch_alter_table('submission', schema=None) as batch_op:
        batch_op.drop_index('ix_submission_status_submitted_at')
        batch_op.drop_index('ix_submission_patient_id_status')

    op.drop_table('submission')
    op.drop_table('guardian_session')
    with op.batch_alter_table('station', schema=None) as batch_op:
        batch_op.drop_index('uq_station_active_name', sqlite_where=sa.text('active = 1'))

    op.drop_table('station')
