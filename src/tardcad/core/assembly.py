from __future__ import annotations

from dataclasses import dataclass

from tardcad.core.document import CadDocument, Feature


@dataclass(slots=True)
class Placement:
    x: float = 0.0
    y: float = 0.0
    z: float = 0.0
    rx: float = 0.0
    ry: float = 0.0
    rz: float = 0.0

    @classmethod
    def from_feature(cls, feature: Feature) -> "Placement":
        values = list(feature.parameters.get("placement", [0, 0, 0, 0, 0, 0]))
        values += [0.0] * (6 - len(values))
        return cls(*(float(value) for value in values[:6]))

    def values(self) -> list[float]:
        return [self.x, self.y, self.z, self.rx, self.ry, self.rz]


class AssemblySolver:
    """Deterministic component-origin mate solver for the MVP assembly workflow."""

    def solve(self, document: CadDocument) -> tuple[dict[str, Placement], dict[str, str]]:
        placements = {
            feature.id: Placement.from_feature(feature)
            for feature in document.features
            if feature.kind == "component" and not feature.suppressed
        }
        errors: dict[str, str] = {}
        for mate in (feature for feature in document.features if feature.kind == "mate" and not feature.suppressed):
            p = mate.parameters
            first, second = placements.get(p.get("component_a")), placements.get(p.get("component_b"))
            if first is None or second is None:
                errors[mate.id] = "Mate references a missing component"
                continue
            axis = p.get("axis", "X").lower()
            offset = float(p.get("offset", 0.0))
            kind = p.get("mate_type", "coincident")
            if kind in {"coincident", "distance"}:
                setattr(second, axis, getattr(first, axis) + offset)
            elif kind == "concentric":
                for coordinate in ({"x", "y", "z"} - {axis}):
                    setattr(second, coordinate, getattr(first, coordinate))
            elif kind == "parallel":
                rotation = f"r{axis}"
                setattr(second, rotation, getattr(first, rotation) + offset)
            else:
                errors[mate.id] = f"Unsupported mate type: {kind}"
        return placements, errors
