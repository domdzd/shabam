"""email verification and password reset codes

Revision ID: 0002
Revises: 0001
Create Date: 2026-11-05 14:30:00
"""
from alembic import op
import sqlalchemy as sa


revision = '0002'
down_revision = '0001'
branch_labels = None
depends_on = None


def upgrade() -> None:
    with op.batch_alter_table('users', schema=None) as batch_op:
        batch_op.add_column(sa.Column('email_verified_at', sa.DateTime(timezone=True), nullable=True))

    op.create_table('email_codes',
    sa.Column('id', sa.String(length=36), nullable=False),
    sa.Column('user_id', sa.String(length=36), nullable=False),
    sa.Column('purpose', sa.String(length=16), nullable=False),
    sa.Column('code_hash', sa.String(length=64), nullable=False),
    sa.Column('expires_at', sa.DateTime(timezone=True), nullable=False),
    sa.Column('attempts', sa.Integer(), nullable=False),
    sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
    sa.ForeignKeyConstraint(['user_id'], ['users.id'], ondelete='CASCADE'),
    sa.PrimaryKeyConstraint('id')
    )
    with op.batch_alter_table('email_codes', schema=None) as batch_op:
        batch_op.create_index(batch_op.f('ix_email_codes_user_id'), ['user_id'], unique=False)


def downgrade() -> None:
    with op.batch_alter_table('email_codes', schema=None) as batch_op:
        batch_op.drop_index(batch_op.f('ix_email_codes_user_id'))
    op.drop_table('email_codes')
    with op.batch_alter_table('users', schema=None) as batch_op:
        batch_op.drop_column('email_verified_at')
