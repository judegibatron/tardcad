from __future__ import annotations

import json
from pathlib import Path

from tardcad.core.document import CadDocument


class RecoveryManager:
    def __init__(self, root: str | Path) -> None:
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)

    def path_for(self, document: CadDocument) -> Path:
        return self.root / f"{document.id}.tardcad.autosave"

    def write(self, document: CadDocument) -> Path:
        destination = self.path_for(document)
        temporary = destination.with_suffix(".tmp")
        temporary.write_text(json.dumps(document.to_dict(), indent=2), encoding="utf-8")
        temporary.replace(destination)
        return destination

    def clear(self, document: CadDocument) -> None:
        self.path_for(document).unlink(missing_ok=True)

    def available(self) -> list[Path]:
        return sorted(self.root.glob("*.tardcad.autosave"), key=lambda path: path.stat().st_mtime, reverse=True)

    def load(self, path: str | Path) -> CadDocument:
        source = Path(path)
        value = json.loads(source.read_text(encoding="utf-8"))
        document = CadDocument(value.get("name", "Recovered"), value.get("document_type", "part"))
        document.restore(value)
        document.path = None
        document.dirty = True
        return document
