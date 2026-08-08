from __future__ import annotations

UNIT_SCALE_MM = {"mm": 1.0, "cm": 10.0, "m": 1000.0, "in": 25.4}


def from_mm(value: float, unit: str) -> float:
    return value / UNIT_SCALE_MM[unit]


def to_mm(value: float, unit: str) -> float:
    return value * UNIT_SCALE_MM[unit]


def precision_for(unit: str) -> int:
    return 4 if unit in ("m", "in") else 3
