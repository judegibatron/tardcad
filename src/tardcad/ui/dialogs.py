from __future__ import annotations

from PySide6.QtWidgets import (
    QCheckBox,
    QComboBox,
    QDialog,
    QDialogButtonBox,
    QDoubleSpinBox,
    QFormLayout,
    QLineEdit,
    QMessageBox,
)

from tardcad.core.document import Feature
from tardcad.core.units import from_mm, precision_for, to_mm


class PrimitiveDialog(QDialog):
    def __init__(self, kind: str, unit: str = "mm", parent=None) -> None:
        super().__init__(parent)
        self.kind = kind
        self.unit = unit
        self.setWindowTitle(f"Create {kind.title()}")
        layout = QFormLayout(self)
        self.name = QLineEdit(kind.title())
        layout.addRow("Feature name", self.name)
        keys = {"box": ("length", "width", "height"), "cylinder": ("diameter", "height"), "sphere": ("diameter",)}[kind]
        self.fields: dict[str, QDoubleSpinBox] = {}
        for key in keys:
            field = QDoubleSpinBox()
            field.setRange(0.01, 1_000_000)
            field.setDecimals(precision_for(unit))
            field.setSuffix(f" {unit}")
            default_mm = 40 if key == "length" else 30 if key in ("width", "diameter") else 20
            field.setValue(from_mm(default_mm, unit))
            layout.addRow(key.title(), field)
            self.fields[key] = field
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addRow(buttons)

    def values(self) -> tuple[str, dict[str, float]]:
        return self.name.text().strip() or self.kind.title(), {
            key: to_mm(field.value(), self.unit) for key, field in self.fields.items()
        }


class ExtrudeDialog(QDialog):
    def __init__(self, sketches: list[Feature], selected_id: str | None = None, unit: str = "mm", parent=None) -> None:
        super().__init__(parent)
        self.unit = unit
        self.setWindowTitle("Extruded Boss/Base")
        layout = QFormLayout(self)
        self.name = QLineEdit("Extrude1")
        self.sketch = QComboBox()
        for feature in sketches:
            self.sketch.addItem(feature.name, feature.id)
        if selected_id:
            index = self.sketch.findData(selected_id)
            if index >= 0:
                self.sketch.setCurrentIndex(index)
        self.distance = QDoubleSpinBox()
        self.distance.setRange(0.01, 1_000_000)
        self.distance.setDecimals(precision_for(unit))
        self.distance.setValue(from_mm(10.0, unit))
        self.distance.setSuffix(f" {unit}")
        self.symmetric = QCheckBox("Extrude equally in both directions")
        layout.addRow("Feature name", self.name)
        layout.addRow("Profile", self.sketch)
        layout.addRow("Depth", self.distance)
        layout.addRow("Mid-plane", self.symmetric)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        buttons.accepted.connect(self._accept_if_valid)
        buttons.rejected.connect(self.reject)
        layout.addRow(buttons)

    def _accept_if_valid(self) -> None:
        if self.sketch.currentIndex() < 0:
            QMessageBox.warning(self, "No sketch", "Create a closed sketch before extruding.")
            return
        self.accept()

    def values(self) -> tuple[str, str, float, bool]:
        return (
            self.name.text().strip() or "Extrude",
            self.sketch.currentData(),
            to_mm(self.distance.value(), self.unit),
            self.symmetric.isChecked(),
        )


class SourceFeatureDialog(QDialog):
    """Compact parameter dialog for source-based solid features."""

    def __init__(self, title: str, sources: list[Feature], fields: list[tuple[str, str, float]], selected_id=None, unit="mm", axes=False, parent=None) -> None:
        super().__init__(parent)
        self.unit = unit
        self.setWindowTitle(title)
        layout = QFormLayout(self)
        self.name = QLineEdit(f"{title}1")
        self.source = QComboBox()
        for feature in sources:
            self.source.addItem(feature.name, feature.id)
        index = self.source.findData(selected_id)
        if index >= 0:
            self.source.setCurrentIndex(index)
        layout.addRow("Feature name", self.name)
        layout.addRow("Source", self.source)
        self.fields = {}
        for key, label, default in fields:
            field = QDoubleSpinBox()
            field.setRange(0.001, 1_000_000)
            field.setDecimals(precision_for(unit))
            if key in {"radius", "distance", "spacing"}:
                field.setValue(from_mm(default, unit))
                field.setSuffix(f" {unit}")
            else:
                field.setValue(default)
                field.setDecimals(2)
                field.setSuffix("°" if key == "angle" else "")
            layout.addRow(label, field)
            self.fields[key] = field
        self.axis = QComboBox()
        if axes:
            self.axis.addItems(["X", "Y", "Z"])
            layout.addRow("Axis", self.axis)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addRow(buttons)

    def values(self) -> tuple[str, str, dict]:
        values = {}
        for key, field in self.fields.items():
            value = field.value()
            values[key] = to_mm(value, self.unit) if key in {"radius", "distance", "spacing"} else value
        if self.axis.count():
            values["axis"] = self.axis.currentText()
        return self.name.text().strip() or self.windowTitle(), self.source.currentData(), values


class BooleanDialog(QDialog):
    def __init__(self, sources: list[Feature], parent=None) -> None:
        super().__init__(parent)
        self.setWindowTitle("Boolean")
        layout = QFormLayout(self)
        self.name = QLineEdit("Boolean1")
        self.first, self.second = QComboBox(), QComboBox()
        for feature in sources:
            self.first.addItem(feature.name, feature.id)
            self.second.addItem(feature.name, feature.id)
        if self.second.count() > 1:
            self.second.setCurrentIndex(1)
        self.operation = QComboBox()
        self.operation.addItem("Union", "fuse")
        self.operation.addItem("Subtract", "cut")
        self.operation.addItem("Intersect", "common")
        layout.addRow("Feature name", self.name)
        layout.addRow("Primary body", self.first)
        layout.addRow("Tool body", self.second)
        layout.addRow("Operation", self.operation)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addRow(buttons)

    def values(self) -> tuple[str, str, str, str]:
        return self.name.text().strip() or "Boolean", self.first.currentData(), self.second.currentData(), self.operation.currentData()


class MateDialog(QDialog):
    def __init__(self, components: list[Feature], unit="mm", parent=None) -> None:
        super().__init__(parent)
        self.unit = unit
        self.setWindowTitle("Add Mate")
        layout = QFormLayout(self)
        self.name = QLineEdit("Mate1")
        self.first, self.second = QComboBox(), QComboBox()
        for component in components:
            self.first.addItem(component.name, component.id)
            self.second.addItem(component.name, component.id)
        if self.second.count() > 1:
            self.second.setCurrentIndex(1)
        self.kind = QComboBox()
        for label, value in (("Coincident", "coincident"), ("Distance", "distance"), ("Concentric", "concentric"), ("Parallel", "parallel")):
            self.kind.addItem(label, value)
        self.axis = QComboBox()
        self.axis.addItems(["X", "Y", "Z"])
        self.offset = QDoubleSpinBox()
        self.offset.setRange(-1_000_000, 1_000_000)
        self.offset.setDecimals(precision_for(unit))
        self.offset.setSuffix(f" {unit}")
        layout.addRow("Mate name", self.name)
        layout.addRow("First component", self.first)
        layout.addRow("Second component", self.second)
        layout.addRow("Type", self.kind)
        layout.addRow("Axis", self.axis)
        layout.addRow("Offset", self.offset)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addRow(buttons)

    def values(self) -> tuple[str, dict]:
        return self.name.text().strip() or "Mate", {
            "component_a": self.first.currentData(),
            "component_b": self.second.currentData(),
            "mate_type": self.kind.currentData(),
            "axis": self.axis.currentText(),
            "offset": to_mm(self.offset.value(), self.unit),
        }
