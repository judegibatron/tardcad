from __future__ import annotations

import math

from PySide6.QtCore import QPointF, Qt, Signal
from PySide6.QtGui import QAction, QActionGroup, QColor, QKeyEvent, QKeySequence, QMouseEvent, QPainter, QPen
from PySide6.QtWidgets import (
    QComboBox,
    QDialog,
    QDialogButtonBox,
    QHBoxLayout,
    QInputDialog,
    QLabel,
    QListWidget,
    QPushButton,
    QSpinBox,
    QToolBar,
    QVBoxLayout,
    QWidget,
)

from tardcad.core.units import from_mm, to_mm


class SketchCanvas(QWidget):
    changed = Signal()
    status_changed = Signal(str)

    def __init__(self, entities: list[dict] | None = None, relations: list[dict] | None = None, unit: str = "mm", parent=None) -> None:
        super().__init__(parent)
        self.entities = [dict(entity) for entity in (entities or [])]
        self.relations = [dict(relation) for relation in (relations or [])]
        self.unit = unit
        self.scale = 5.0
        self.grid_size = 5.0
        self.snap_enabled = True
        self.construction = False
        self.mode = "line"
        self.polygon_sides = 6
        self.chain_start: tuple[float, float] | None = None
        self.shape_center: tuple[float, float] | None = None
        self.cursor_model: tuple[float, float] | None = None
        self.selected_indices: list[int] = []
        self.setMinimumSize(760, 520)
        self.setMouseTracking(True)
        self.setFocusPolicy(Qt.FocusPolicy.StrongFocus)

    def model_to_screen(self, point: tuple[float, float]) -> QPointF:
        return QPointF(self.width() / 2 + point[0] * self.scale, self.height() / 2 - point[1] * self.scale)

    def screen_to_model(self, point: QPointF) -> tuple[float, float]:
        raw_x = (point.x() - self.width() / 2) / self.scale
        raw_y = (self.height() / 2 - point.y()) / self.scale
        for entity in self.entities:
            endpoints = (entity["center"],) if entity["type"] == "circle" else (entity["start"], entity["end"])
            for endpoint in endpoints:
                if math.hypot(raw_x - endpoint[0], raw_y - endpoint[1]) * self.scale <= 10:
                    return float(endpoint[0]), float(endpoint[1])
        x, y = raw_x, raw_y
        if self.snap_enabled:
            x = round(x / self.grid_size) * self.grid_size
            y = round(y / self.grid_size) * self.grid_size
        return x, y

    def mouseMoveEvent(self, event: QMouseEvent) -> None:
        self.cursor_model = self.screen_to_model(event.position())
        x = from_mm(self.cursor_model[0], self.unit)
        y = from_mm(self.cursor_model[1], self.unit)
        self.status_changed.emit(f"X {x:.4g} {self.unit}   Y {y:.4g} {self.unit}")
        self.update()

    def mousePressEvent(self, event: QMouseEvent) -> None:
        if event.button() == Qt.MouseButton.RightButton:
            self.chain_start = None
            self.shape_center = None
            self.update()
            return
        if event.button() != Qt.MouseButton.LeftButton:
            return
        point = self.screen_to_model(event.position())
        if self.mode == "select":
            self._select_nearest(point, bool(event.modifiers() & Qt.KeyboardModifier.ControlModifier))
            return
        if self.mode == "circle":
            if self.shape_center is None:
                self.shape_center = point
            else:
                radius = math.dist(self.shape_center, point)
                if radius > 1e-7:
                    self.entities.append({"type": "circle", "center": list(self.shape_center), "radius": radius, "construction": self.construction})
                    self.shape_center = None
                    self.changed.emit()
            self.update()
            return
        if self.mode == "polygon":
            if self.shape_center is None:
                self.shape_center = point
            else:
                self._add_polygon(self.shape_center, point)
                self.shape_center = None
                self.changed.emit()
            self.update()
            return
        if self.chain_start is None:
            self.chain_start = point
        elif point != self.chain_start:
            self.entities.append(
                {
                    "type": "line",
                    "start": list(self.chain_start),
                    "end": list(point),
                    "construction": self.construction,
                }
            )
            self.chain_start = point
            self._infer_line_relations(len(self.entities) - 1)
            self.changed.emit()
        self.update()

    def keyPressEvent(self, event: QKeyEvent) -> None:
        if event.key() == Qt.Key.Key_Escape:
            self.chain_start = None
            self.shape_center = None
            self.update()
        elif event.matches(QKeySequence.StandardKey.Undo):
            self.undo()
        else:
            super().keyPressEvent(event)

    def undo(self) -> None:
        if self.entities:
            entity = self.entities.pop()
            self.chain_start = tuple(entity["start"])
            self.changed.emit()
            self.update()

    def clear(self) -> None:
        self.entities.clear()
        self.relations.clear()
        self.selected_indices.clear()
        self.chain_start = None
        self.changed.emit()
        self.update()

    def _add_polygon(self, center: tuple[float, float], corner: tuple[float, float]) -> None:
        radius = math.dist(center, corner)
        if radius <= 1e-7:
            return
        start_angle = math.atan2(corner[1] - center[1], corner[0] - center[0])
        points = [
            (center[0] + radius * math.cos(start_angle + 2 * math.pi * index / self.polygon_sides),
             center[1] + radius * math.sin(start_angle + 2 * math.pi * index / self.polygon_sides))
            for index in range(self.polygon_sides)
        ]
        for index, start in enumerate(points):
            end = points[(index + 1) % len(points)]
            self.entities.append({"type": "line", "start": list(start), "end": list(end), "construction": self.construction})

    def _select_nearest(self, point: tuple[float, float], extend: bool) -> None:
        best_index, best_distance = None, 12 / self.scale
        for index, entity in enumerate(self.entities):
            if entity["type"] == "circle":
                distance = abs(math.dist(point, tuple(entity["center"])) - entity["radius"])
            else:
                distance = self._point_line_distance(point, tuple(entity["start"]), tuple(entity["end"]))
            if distance < best_distance:
                best_index, best_distance = index, distance
        if not extend:
            self.selected_indices.clear()
        if best_index is not None and best_index not in self.selected_indices:
            self.selected_indices.append(best_index)
        self.update()

    @staticmethod
    def _point_line_distance(point, start, end) -> float:
        dx, dy = end[0] - start[0], end[1] - start[1]
        if dx == dy == 0:
            return math.dist(point, start)
        t = max(0.0, min(1.0, ((point[0]-start[0])*dx + (point[1]-start[1])*dy) / (dx*dx+dy*dy)))
        return math.dist(point, (start[0] + t*dx, start[1] + t*dy))

    def _infer_line_relations(self, index: int) -> None:
        entity = self.entities[index]
        dx = entity["end"][0] - entity["start"][0]
        dy = entity["end"][1] - entity["start"][1]
        if abs(dy) < 1e-7:
            self.relations.append({"type": "horizontal", "entities": [index]})
        elif abs(dx) < 1e-7:
            self.relations.append({"type": "vertical", "entities": [index]})
        if index and tuple(self.entities[index - 1].get("end", ())) == tuple(entity["start"]):
            self.relations.append({"type": "coincident", "entities": [index - 1, index]})

    def apply_relation(self, relation_type: str) -> bool:
        required = 2 if relation_type in ("equal", "coincident") else 1
        if len(self.selected_indices) < required:
            self.status_changed.emit(f"Select {required} {'entities' if required > 1 else 'line'} first")
            return False
        indices = self.selected_indices[-required:]
        entities = [self.entities[index] for index in indices]
        if relation_type in ("horizontal", "vertical") and entities[0]["type"] != "line":
            self.status_changed.emit("Horizontal and vertical relations require a line")
            return False
        if relation_type == "horizontal":
            entities[0]["end"][1] = entities[0]["start"][1]
        elif relation_type == "vertical":
            entities[0]["end"][0] = entities[0]["start"][0]
        elif relation_type == "equal":
            first, second = entities
            if first["type"] == second["type"] == "circle":
                second["radius"] = first["radius"]
            elif first["type"] == second["type"] == "line":
                length = math.dist(first["start"], first["end"])
                angle = math.atan2(second["end"][1]-second["start"][1], second["end"][0]-second["start"][0])
                second["end"] = [second["start"][0] + length*math.cos(angle), second["start"][1] + length*math.sin(angle)]
            else:
                self.status_changed.emit("Equal requires two lines or two circles")
                return False
        elif relation_type == "coincident":
            entities[1]["start"] = list(entities[0].get("end", entities[0].get("center")))
        self.relations.append({"type": relation_type, "entities": indices})
        self.changed.emit()
        self.update()
        return True

    def apply_dimension(self, value_mm: float) -> bool:
        if not self.selected_indices:
            self.status_changed.emit("Select a line or circle first")
            return False
        index = self.selected_indices[-1]
        entity = self.entities[index]
        if value_mm <= 0:
            self.status_changed.emit("Dimension must be greater than zero")
            return False
        if entity["type"] == "circle":
            entity["radius"] = value_mm
            dimension_type = "radius"
        else:
            start, end = entity["start"], entity["end"]
            angle = math.atan2(end[1]-start[1], end[0]-start[0])
            entity["end"] = [start[0] + value_mm*math.cos(angle), start[1] + value_mm*math.sin(angle)]
            dimension_type = "length"
        self.relations = [relation for relation in self.relations if not (relation["type"] == dimension_type and relation["entities"] == [index])]
        self.relations.append({"type": dimension_type, "entities": [index], "value": value_mm})
        self.changed.emit()
        self.update()
        return True

    def degrees_of_freedom(self) -> int:
        degrees = sum(3 if entity["type"] == "circle" else 4 for entity in self.entities if not entity.get("construction"))
        reductions = {"horizontal": 1, "vertical": 1, "equal": 1, "coincident": 2, "length": 1, "radius": 1, "fixed": 4}
        for relation in self.relations:
            reductions_count = reductions.get(relation["type"], 0)
            if relation["type"] == "fixed" and self.entities[relation["entities"][0]]["type"] == "circle":
                reductions_count = 3
            degrees -= reductions_count
        return max(0, degrees)

    def constraint_status(self) -> str:
        degrees = self.degrees_of_freedom()
        return "Fully defined" if degrees == 0 else f"Under defined ({degrees} DOF)"

    def fit(self) -> None:
        points = []
        for entity in self.entities:
            if entity["type"] == "circle":
                center, radius = entity["center"], entity["radius"]
                points.extend([(center[0] - radius, center[1] - radius), (center[0] + radius, center[1] + radius)])
            else:
                points.extend([tuple(entity["start"]), tuple(entity["end"])])
        if points:
            extent_x = max(abs(point[0]) for point in points) or 10
            extent_y = max(abs(point[1]) for point in points) or 10
            self.scale = max(0.5, min(20.0, min(self.width() / (extent_x * 2.4), self.height() / (extent_y * 2.4))))
        self.update()

    def wheelEvent(self, event) -> None:
        self.scale = max(0.5, min(30.0, self.scale * (1.15 if event.angleDelta().y() > 0 else 0.87)))
        self.update()

    def paintEvent(self, _event) -> None:
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        painter.fillRect(self.rect(), QColor("#101419"))
        spacing = self.grid_size * self.scale
        if spacing >= 8:
            painter.setPen(QPen(QColor("#26303a"), 1))
            x = self.width() / 2 % spacing
            while x < self.width():
                painter.drawLine(QPointF(x, 0), QPointF(x, self.height()))
                x += spacing
            y = self.height() / 2 % spacing
            while y < self.height():
                painter.drawLine(QPointF(0, y), QPointF(self.width(), y))
                y += spacing
        painter.setPen(QPen(QColor("#b64b4b"), 1.5))
        painter.drawLine(QPointF(0, self.height() / 2), QPointF(self.width(), self.height() / 2))
        painter.setPen(QPen(QColor("#4b9d63"), 1.5))
        painter.drawLine(QPointF(self.width() / 2, 0), QPointF(self.width() / 2, self.height()))
        for entity in self.entities:
            index = self.entities.index(entity)
            color = QColor("#f1b74a") if index in self.selected_indices else QColor("#5faee3") if entity.get("construction") else QColor("#f2f5f7")
            pen = QPen(color, 2)
            if entity.get("construction"):
                pen.setStyle(Qt.PenStyle.DashLine)
            painter.setPen(pen)
            if entity["type"] == "circle":
                center = self.model_to_screen(tuple(entity["center"]))
                radius = entity["radius"] * self.scale
                painter.drawEllipse(center, radius, radius)
            else:
                start = self.model_to_screen(tuple(entity["start"]))
                end = self.model_to_screen(tuple(entity["end"]))
                painter.drawLine(start, end)
                painter.setBrush(QColor("#f1b74a"))
                painter.setPen(Qt.PenStyle.NoPen)
                painter.drawEllipse(start, 3.5, 3.5)
                painter.drawEllipse(end, 3.5, 3.5)
        anchor = self.chain_start or self.shape_center
        if anchor and self.cursor_model:
            painter.setPen(QPen(QColor("#f1b74a"), 1.5, Qt.PenStyle.DashLine))
            if self.mode == "circle":
                center = self.model_to_screen(anchor)
                radius = math.dist(anchor, self.cursor_model) * self.scale
                painter.drawEllipse(center, radius, radius)
            else:
                painter.drawLine(self.model_to_screen(anchor), self.model_to_screen(self.cursor_model))
        painter.setPen(QColor("#8794a1"))
        painter.drawText(12, 22, "LINE  •  Left-click points  •  Right-click or Esc ends chain  •  Wheel zooms")


class SketchDialog(QDialog):
    def __init__(self, name: str = "Sketch1", plane: str = "XY", entities=None, relations=None, unit="mm", parent=None) -> None:
        super().__init__(parent)
        self.setWindowTitle(f"TardCAD Sketcher — {name}")
        self.resize(1050, 720)
        layout = QVBoxLayout(self)
        toolbar = QToolBar()
        toolbar.setToolButtonStyle(Qt.ToolButtonStyle.ToolButtonTextBesideIcon)
        tools = QActionGroup(self)
        tools.setExclusive(True)
        select = QAction("Select", self, checkable=True)
        line = QAction("Line", self, checkable=True)
        circle = QAction("Circle", self, checkable=True)
        polygon = QAction("Polygon", self, checkable=True)
        line.setChecked(True)
        for action in (select, line, circle, polygon):
            tools.addAction(action)
            toolbar.addAction(action)
        toolbar.addSeparator()
        undo = QAction("Undo", self)
        undo.setShortcut("Ctrl+Z")
        construction = QAction("Construction", self)
        construction.setCheckable(True)
        snap = QAction("Grid Snap", self)
        snap.setCheckable(True)
        snap.setChecked(True)
        clear = QAction("Clear", self)
        fit = QAction("Fit", self)
        for action in (undo, construction, snap, clear, fit):
            toolbar.addAction(action)
        toolbar.addSeparator()
        toolbar.addWidget(QLabel(" Polygon sides  "))
        sides = QSpinBox()
        sides.setRange(3, 24)
        sides.setValue(6)
        toolbar.addWidget(sides)
        toolbar.addSeparator()
        toolbar.addWidget(QLabel(" Plane  "))
        self.plane = QComboBox()
        self.plane.addItems(["XY", "XZ", "YZ"])
        self.plane.setCurrentText(plane)
        toolbar.addWidget(self.plane)
        layout.addWidget(toolbar)
        self.canvas = SketchCanvas(entities, relations, unit)
        content = QHBoxLayout()
        content.addWidget(self.canvas, 1)
        relation_panel = QVBoxLayout()
        relation_panel.addWidget(QLabel("Sketch Relations"))
        self.relation_list = QListWidget()
        relation_panel.addWidget(self.relation_list, 1)
        for label, kind in (("Horizontal", "horizontal"), ("Vertical", "vertical"), ("Coincident", "coincident"), ("Equal", "equal"), ("Fix", "fixed")):
            button = QPushButton(label)
            button.clicked.connect(lambda _checked=False, value=kind: self.add_relation(value))
            relation_panel.addWidget(button)
        dimension = QPushButton("Dimension")
        dimension.clicked.connect(self.add_dimension)
        relation_panel.addWidget(dimension)
        relation_widget = QWidget()
        relation_widget.setLayout(relation_panel)
        relation_widget.setMaximumWidth(190)
        content.addWidget(relation_widget)
        layout.addLayout(content, 1)
        footer = QHBoxLayout()
        self.status = QLabel("Click to place the first line point")
        footer.addWidget(self.status, 1)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Save | QDialogButtonBox.StandardButton.Cancel)
        buttons.button(QDialogButtonBox.StandardButton.Save).setText("Finish Sketch")
        footer.addWidget(buttons)
        layout.addLayout(footer)
        undo.triggered.connect(self.canvas.undo)
        clear.triggered.connect(self.canvas.clear)
        fit.triggered.connect(self.canvas.fit)
        construction.toggled.connect(lambda checked: setattr(self.canvas, "construction", checked))
        snap.toggled.connect(lambda checked: setattr(self.canvas, "snap_enabled", checked))
        self.canvas.status_changed.connect(self.status.setText)
        self.canvas.changed.connect(self.refresh_relations)
        select.triggered.connect(lambda: setattr(self.canvas, "mode", "select"))
        line.triggered.connect(lambda: setattr(self.canvas, "mode", "line"))
        circle.triggered.connect(lambda: setattr(self.canvas, "mode", "circle"))
        polygon.triggered.connect(lambda: setattr(self.canvas, "mode", "polygon"))
        sides.valueChanged.connect(lambda value: setattr(self.canvas, "polygon_sides", value))
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        self.refresh_relations()

    def add_relation(self, relation_type: str) -> None:
        if self.canvas.apply_relation(relation_type):
            self.refresh_relations()

    def refresh_relations(self) -> None:
        self.relation_list.clear()
        for relation in self.canvas.relations:
            numbers = ", ".join(str(index + 1) for index in relation["entities"])
            value = f" = {from_mm(relation['value'], self.canvas.unit):.4g} {self.canvas.unit}" if "value" in relation else ""
            self.relation_list.addItem(f"{relation['type'].title()}  ({numbers}){value}")
        self.status.setText(self.canvas.constraint_status())

    def add_dimension(self) -> None:
        if not self.canvas.selected_indices:
            self.status.setText("Select a line or circle first")
            return
        entity = self.canvas.entities[self.canvas.selected_indices[-1]]
        current_mm = entity["radius"] if entity["type"] == "circle" else math.dist(entity["start"], entity["end"])
        value, accepted = QInputDialog.getDouble(
            self,
            "Driving Dimension",
            f"{'Radius' if entity['type'] == 'circle' else 'Length'} ({self.canvas.unit})",
            from_mm(current_mm, self.canvas.unit),
            0.0001,
            1_000_000,
            4,
        )
        if accepted and self.canvas.apply_dimension(to_mm(value, self.canvas.unit)):
            self.refresh_relations()

    def result_data(self) -> tuple[str, list[dict], list[dict], str]:
        return self.plane.currentText(), self.canvas.entities, self.canvas.relations, self.canvas.constraint_status()
