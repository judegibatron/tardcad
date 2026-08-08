from __future__ import annotations

import json
import uuid
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Callable


@dataclass(slots=True)
class Feature:
    kind: str
    name: str
    parameters: dict[str, Any]
    id: str = field(default_factory=lambda: str(uuid.uuid4()))
    visible: bool = True
    suppressed: bool = False

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> Feature:
        return cls(
            kind=value["kind"],
            name=value["name"],
            parameters=dict(value.get("parameters", {})),
            id=value.get("id", str(uuid.uuid4())),
            visible=value.get("visible", True),
            suppressed=value.get("suppressed", False),
        )


class CadDocument:
    schema_version = 1

    def __init__(self, name: str = "Part 1", document_type: str = "part") -> None:
        if document_type not in ("part", "assembly"):
            raise ValueError(f"Unsupported document type: {document_type}")
        self.id = str(uuid.uuid4())
        self.name = name
        self.document_type = document_type
        self.units = "mm"
        self.features: list[Feature] = []
        self.path: Path | None = None
        self.dirty = False
        self._listeners: list[Callable[[], None]] = []

    def subscribe(self, callback: Callable[[], None]) -> None:
        self._listeners.append(callback)

    def _changed(self) -> None:
        self.dirty = True
        for callback in tuple(self._listeners):
            callback()

    def add_feature(self, kind: str, name: str, **parameters: Any) -> Feature:
        feature = Feature(kind=kind, name=name, parameters=parameters)
        self.features.append(feature)
        self._changed()
        return feature

    def remove_feature(self, feature_id: str) -> None:
        self.features = [feature for feature in self.features if feature.id != feature_id]
        self._changed()

    def feature(self, feature_id: str) -> Feature | None:
        return next((feature for feature in self.features if feature.id == feature_id), None)

    def update_parameter(self, feature_id: str, key: str, value: Any) -> None:
        feature = self.feature(feature_id)
        if feature is None:
            raise KeyError(feature_id)
        feature.parameters[key] = value
        self._changed()

    def set_units(self, units: str) -> None:
        if units not in ("mm", "cm", "m", "in"):
            raise ValueError(f"Unsupported units: {units}")
        if self.units != units:
            self.units = units
            self._changed()

    def to_dict(self) -> dict[str, Any]:
        return {
            "schema_version": self.schema_version,
            "id": self.id,
            "name": self.name,
            "document_type": self.document_type,
            "units": self.units,
            "features": [asdict(feature) for feature in self.features],
        }

    def restore(self, value: dict[str, Any]) -> None:
        """Restore a prior feature-graph state while preserving listeners/path."""
        self.id = value.get("id", self.id)
        self.name = value.get("name", self.name)
        self.document_type = value.get("document_type", self.document_type)
        self.units = value.get("units", self.units)
        self.features = [Feature.from_dict(item) for item in value.get("features", [])]
        self._changed()

    def save(self, path: str | Path | None = None) -> Path:
        destination = Path(path) if path else self.path
        if destination is None:
            raise ValueError("A destination path is required")
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_suffix(destination.suffix + ".tmp")
        temporary.write_text(json.dumps(self.to_dict(), indent=2), encoding="utf-8")
        temporary.replace(destination)
        self.path = destination
        self.dirty = False
        return destination

    @classmethod
    def load(cls, path: str | Path) -> CadDocument:
        source = Path(path)
        value = json.loads(source.read_text(encoding="utf-8"))
        if value.get("schema_version") != cls.schema_version:
            raise ValueError("Unsupported TardCAD document version")
        inferred_type = "assembly" if source.suffix.lower() == ".tasm" else "part"
        document = cls(value.get("name", source.stem), value.get("document_type", inferred_type))
        document.id = value.get("id", document.id)
        document.units = value.get("units", "mm")
        document.features = [Feature.from_dict(item) for item in value.get("features", [])]
        document.path = source
        document.dirty = False
        return document


class DocumentHistory:
    def __init__(self, document: CadDocument, limit: int = 100) -> None:
        self.document = document
        self.limit = limit
        self.undo_states: list[dict[str, Any]] = []
        self.redo_states: list[dict[str, Any]] = []
        self.current = document.to_dict()
        self.suspended = False
        document.subscribe(self._record)

    def _record(self) -> None:
        if self.suspended:
            return
        new_state = self.document.to_dict()
        if new_state != self.current:
            self.undo_states.append(self.current)
            self.undo_states = self.undo_states[-self.limit :]
            self.current = new_state
            self.redo_states.clear()

    def undo(self) -> bool:
        if not self.undo_states:
            return False
        self.redo_states.append(self.current)
        state = self.undo_states.pop()
        self._restore(state)
        return True

    def redo(self) -> bool:
        if not self.redo_states:
            return False
        self.undo_states.append(self.current)
        state = self.redo_states.pop()
        self._restore(state)
        return True

    def _restore(self, state: dict[str, Any]) -> None:
        self.suspended = True
        try:
            self.document.restore(state)
            self.current = self.document.to_dict()
        finally:
            self.suspended = False
