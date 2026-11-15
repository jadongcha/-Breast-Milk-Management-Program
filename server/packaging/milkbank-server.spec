# -*- mode: python ; coding: utf-8 -*-
# PyInstaller onedir 설정. 실행: uv run --with pyinstaller pyinstaller packaging/milkbank-server.spec
from pathlib import Path

from PyInstaller.utils.hooks import collect_data_files, collect_submodules, copy_metadata

here = Path(SPECPATH)  # noqa: F821  (PyInstaller가 넣어 준다)
src = here.parent / "src"

hiddenimports = (
    ["milkbank.main"]
    + collect_submodules("milkbank")
    + collect_submodules("uvicorn")
    + collect_submodules("alembic")
    + ["sqlalchemy.dialects.sqlite"]
)
datas = (
    # migrate.py가 Path(__file__).parent / "migrations"로 찾으므로 같은 상대 위치에 둔다.
    [(str(src / "milkbank" / "migrations"), "milkbank/migrations")]
    + collect_data_files("tzdata")
    + copy_metadata("milkbank")
)

a = Analysis(  # noqa: F821
    [str(here / "entry.py")],
    pathex=[str(src)],
    binaries=[],
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
)
pyz = PYZ(a.pure)  # noqa: F821
exe = EXE(  # noqa: F821
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="milkbank-server",
    console=True,
    upx=False,
)
coll = COLLECT(  # noqa: F821
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    name="milkbank-server",
)
