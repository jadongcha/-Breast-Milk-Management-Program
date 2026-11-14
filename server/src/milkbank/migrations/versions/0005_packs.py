"""packs

Revision ID: 0005
Revises: 0004
Create Date: 2026-11-15
"""
import json

from alembic import op
import sqlalchemy as sa

revision = '0005'
down_revision = '0004'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table('pack',
    sa.Column('id', sa.Integer(), nullable=False),
    sa.Column('patient_id', sa.Integer(), nullable=False),
    sa.Column('ml', sa.Integer(), nullable=True),
    sa.Column('in_record_id', sa.Integer(), nullable=False),
    sa.Column('out_record_id', sa.Integer(), nullable=True),
    sa.Column('created_at', sa.DateTime(), nullable=False),
    sa.ForeignKeyConstraint(['in_record_id'], ['stock_record.id'], ),
    sa.ForeignKeyConstraint(['out_record_id'], ['stock_record.id'], ),
    sa.ForeignKeyConstraint(['patient_id'], ['patient.id'], ),
    sa.PrimaryKeyConstraint('id')
    )
    with op.batch_alter_table('pack', schema=None) as batch_op:
        batch_op.create_index('ix_pack_in_record', ['in_record_id'], unique=False)
        batch_op.create_index('ix_pack_out_record', ['out_record_id'], unique=False)
        batch_op.create_index('ix_pack_patient_out', ['patient_id', 'out_record_id'], unique=False)

    with op.batch_alter_table('stock_record', schema=None) as batch_op:
        batch_op.add_column(sa.Column('ml_unknown', sa.Integer(), server_default=sa.text('0'), nullable=False))

    with op.batch_alter_table('submission', schema=None) as batch_op:
        batch_op.add_column(sa.Column('pack_mls', sa.String(), nullable=True))

    _move_stock_into_packs(op.get_bind())


def _split_ml(ml, count):
    # 나누어떨어지고 팩당 1~1000mL일 때만 나눈다. 아니면 지어내지 않고 모름(None)으로 둔다.
    if ml is None or not count or ml % count != 0:
        return None
    each = ml // count
    return each if 1 <= each <= 1000 else None


def _move_stock_into_packs(bind) -> None:
    # 모델을 import하지 않고 SQL만 쓴다(마이그레이션은 모델이 바뀌어도 그대로여야 한다).
    patient_ids = [r[0] for r in bind.exec_driver_sql('SELECT id FROM patient ORDER BY id')]
    for pid in patient_ids:
        records = bind.exec_driver_sql(
            "SELECT id, kind, count, ml, created_at FROM stock_record"
            " WHERE patient_id = ? AND kind != 'cancel'"
            " AND id NOT IN (SELECT target_id FROM stock_record"
            "                WHERE kind = 'cancel' AND target_id IS NOT NULL)"
            " ORDER BY id",
            (pid,),
        ).fetchall()
        stock: list[int] = []  # 재고 팩 id, 먼저 들어온 순
        # 3단계에서는 나중 입고가 있으면 앞선 입고를 취소할 수 있었으므로 재고가 모자랄 수 있다.
        # 모자란 수는 [기록 id, 남은 수]로 남겨 두고 뒤에 들어오는 팩으로 먼저 채운다.
        owed: list[list[int]] = []
        balance = 0
        for rec_id, kind, count, ml, created_at in records:
            if kind == 'in':
                balance += count
                each = _split_ml(ml, count)
                for _ in range(count):
                    cur = bind.exec_driver_sql(
                        'INSERT INTO pack (patient_id, ml, in_record_id, out_record_id, created_at)'
                        ' VALUES (?, ?, ?, NULL, ?)',
                        (pid, each, rec_id, created_at),
                    )
                    pack_id = cur.lastrowid
                    if owed:
                        bind.exec_driver_sql(
                            'UPDATE pack SET out_record_id = ? WHERE id = ?',
                            (owed[0][0], pack_id),
                        )
                        owed[0][1] -= 1
                        if owed[0][1] == 0:
                            owed.pop(0)
                    else:
                        stock.append(pack_id)
            else:  # use, discard, return
                balance -= count
                out, stock = stock[:count], stock[count:]
                for pack_id in out:
                    bind.exec_driver_sql(
                        'UPDATE pack SET out_record_id = ? WHERE id = ?', (rec_id, pack_id)
                    )
                if len(out) < count:
                    owed.append([rec_id, count - len(out)])
        if len(stock) != balance:
            raise RuntimeError(f'팩 옮기기 결과가 잔량과 다릅니다: 환자 {pid}')

    pending = bind.exec_driver_sql(
        "SELECT id, count, ml FROM submission WHERE status = 'pending'"
    ).fetchall()
    for sub_id, count, ml in pending:
        each = _split_ml(ml, count)
        bind.exec_driver_sql(
            'UPDATE submission SET pack_mls = ? WHERE id = ?',
            (json.dumps([each] * count), sub_id),
        )


def downgrade() -> None:
    with op.batch_alter_table('submission', schema=None) as batch_op:
        batch_op.drop_column('pack_mls')

    with op.batch_alter_table('stock_record', schema=None) as batch_op:
        batch_op.drop_column('ml_unknown')

    with op.batch_alter_table('pack', schema=None) as batch_op:
        batch_op.drop_index('ix_pack_patient_out')
        batch_op.drop_index('ix_pack_out_record')
        batch_op.drop_index('ix_pack_in_record')

    op.drop_table('pack')
