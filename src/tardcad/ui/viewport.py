from __future__ import annotations

import math

from PySide6.QtCore import QPoint, QPointF, Qt, Signal
from PySide6.QtGui import QColor, QMouseEvent, QPainter, QPainterPath, QPen, QPolygonF, QWheelEvent
from PySide6.QtWidgets import QWidget

from tardcad.core.document import CadDocument, Feature
from tardcad.core.geometry import GeometryService


class CadViewport(QWidget):
    selection_changed = Signal(object)

    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.document: CadDocument | None = None
        self.geometry = GeometryService()
        self._meshes: list[tuple[str, tuple[tuple[float, float, float], ...]]] = []
        self.kernel_errors: dict[str, str] = {}
        self._hit_regions: list[tuple[str, QPolygonF]] = []
        self.zoom = 1.0
        self.yaw = -35.0
        self.pitch = 25.0
        self._drag_origin: QPoint | None = None
        self._selected_id: str | None = None
        self.setMinimumSize(560, 360)
        self.setMouseTracking(True)
        self.setFocusPolicy(Qt.FocusPolicy.StrongFocus)

    def set_document(self, document: CadDocument) -> None:
        self.document = document
        document.subscribe(self.rebuild_scene)
        self.rebuild_scene()

    def rebuild_scene(self) -> None:
        self._meshes.clear()
        self.kernel_errors.clear()
        if self.document and self.geometry.available:
            shapes, self.kernel_errors = self.geometry.recompute(self.document)
            consumed = {
                feature.parameters[key]
                for feature in self.document.features
                for key in ("source_id", "first_id", "second_id")
                if key in feature.parameters
            }
            for feature_id, shape in shapes.items():
                feature = self.document.feature(feature_id)
                if feature and feature.visible and feature_id not in consumed:
                    try:
                        self._meshes.extend((feature_id, triangle) for triangle in self.geometry.tessellate(shape))
                    except Exception as error:
                        self.kernel_errors[feature_id] = str(error)
        self.update()

    def fit(self) -> None:
        self.zoom = 1.0
        self.update()

    def set_view(self, yaw: float, pitch: float) -> None:
        self.yaw, self.pitch = yaw, pitch
        self.update()

    def wheelEvent(self, event: QWheelEvent) -> None:
        self.zoom = max(0.3, min(4.0, self.zoom * (1.12 if event.angleDelta().y() > 0 else 0.89)))
        self.update()

    def mousePressEvent(self, event: QMouseEvent) -> None:
        if event.button() in (Qt.MouseButton.MiddleButton, Qt.MouseButton.LeftButton):
            self._drag_origin = event.position().toPoint()

    def mouseMoveEvent(self, event: QMouseEvent) -> None:
        if self._drag_origin and event.buttons() & Qt.MouseButton.MiddleButton:
            delta = event.position().toPoint() - self._drag_origin
            self.yaw += delta.x() * 0.45
            self.pitch = max(-85, min(85, self.pitch + delta.y() * 0.35))
            self._drag_origin = event.position().toPoint()
            self.update()

    def mouseReleaseEvent(self, event: QMouseEvent) -> None:
        if event.button() == Qt.MouseButton.LeftButton and self._drag_origin:
            if (event.position().toPoint() - self._drag_origin).manhattanLength() < 5:
                feature = None
                for feature_id, polygon in reversed(self._hit_regions):
                    if polygon.containsPoint(event.position(), Qt.FillRule.OddEvenFill):
                        feature = self.document.feature(feature_id) if self.document else None
                        break
                if feature is None and self.document and not self._meshes:
                    feature = self.document.features[-1] if self.document.features else None
                self._selected_id = feature.id if feature else None
                self.selection_changed.emit(feature)
                self.update()
        self._drag_origin = None

    def _project(self, x: float, y: float, z: float) -> QPointF:
        x1, yp, _depth = self._view_coordinates(x, y, z)
        scale = 3.5 * self.zoom
        return QPointF(self.width() / 2 + x1 * scale, self.height() / 2 + yp * scale)

    def _view_coordinates(self, x: float, y: float, z: float) -> tuple[float, float, float]:
        yaw = math.radians(self.yaw)
        pitch = math.radians(self.pitch)
        x1 = x * math.cos(yaw) - y * math.sin(yaw)
        y1 = x * math.sin(yaw) + y * math.cos(yaw)
        yp = y1 * math.sin(pitch) - z * math.cos(pitch)
        depth = y1 * math.cos(pitch) + z * math.sin(pitch)
        return x1, yp, depth

    def paintEvent(self, _event: object) -> None:
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        painter.fillRect(self.rect(), QColor("#11151a"))
        self._draw_grid(painter)
        if self.document and self.document.features:
            if self._meshes:
                self._draw_kernel_meshes(painter)
                for feature in self.document.features:
                    if feature.kind == "sketch" and feature.visible and not feature.suppressed:
                        self._draw_sketch(painter, feature)
            else:
                for index, feature in enumerate(self.document.features):
                    if feature.visible and not feature.suppressed:
                        self._draw_feature(painter, feature, index)
        else:
            painter.setPen(QColor("#77818d"))
            painter.drawText(self.rect(), Qt.AlignmentFlag.AlignCenter, "Select a plane or create a feature to begin")
        self._draw_axes(painter)

    def _draw_kernel_meshes(self, painter: QPainter) -> None:
        projected = []
        self._hit_regions.clear()
        for feature_id, triangle in self._meshes:
            view = [self._view_coordinates(*point) for point in triangle]
            screen = [self._project(*point) for point in triangle]
            a, b, c = triangle
            ux, uy, uz = b[0]-a[0], b[1]-a[1], b[2]-a[2]
            vx, vy, vz = c[0]-a[0], c[1]-a[1], c[2]-a[2]
            nx, ny, nz = uy*vz-uz*vy, uz*vx-ux*vz, ux*vy-uy*vx
            length = math.sqrt(nx*nx + ny*ny + nz*nz) or 1
            brightness = max(0.22, min(1.0, 0.38 + 0.62 * abs((nx*0.3 + ny*-0.4 + nz*0.85) / length)))
            projected.append((sum(point[2] for point in view) / 3, feature_id, screen, brightness))
        for _depth, feature_id, screen, brightness in sorted(projected, key=lambda item: item[0]):
            base = (240, 164, 60) if feature_id == self._selected_id else (77, 152, 209)
            color = QColor(*(int(channel * brightness) for channel in base))
            painter.setBrush(color)
            painter.setPen(QPen(QColor("#18232c"), 0.65))
            polygon = QPolygonF(screen)
            painter.drawPolygon(polygon)
            self._hit_regions.append((feature_id, polygon))

    def _draw_grid(self, painter: QPainter) -> None:
        painter.setPen(QPen(QColor("#27303a"), 1))
        for value in range(-100, 101, 10):
            painter.drawLine(self._project(value, -100, 0), self._project(value, 100, 0))
            painter.drawLine(self._project(-100, value, 0), self._project(100, value, 0))
        painter.setPen(QPen(QColor("#4b5663"), 1.3))
        painter.drawLine(self._project(-100, 0, 0), self._project(100, 0, 0))
        painter.drawLine(self._project(0, -100, 0), self._project(0, 100, 0))

    def _draw_feature(self, painter: QPainter, feature: Feature, index: int) -> None:
        p = feature.parameters
        if feature.kind == "box":
            self._draw_box(painter, p.get("length", 40), p.get("width", 30), p.get("height", 20), index, feature)
        elif feature.kind in ("cylinder", "sphere"):
            diameter = p.get("diameter", 30)
            height = p.get("height", diameter)
            self._draw_cylinder(painter, diameter, height, index, feature)
        elif feature.kind == "sketch":
            self._draw_sketch(painter, feature)
        elif feature.kind == "extrude":
            self._draw_extrude(painter, feature)
        else:
            self._draw_imported(painter, index, feature)

    def _draw_extrude(self, painter: QPainter, feature: Feature) -> None:
        if not self.document:
            return
        sketch = self.document.feature(feature.parameters.get("sketch_id", ""))
        if not sketch:
            return
        distance = float(feature.parameters.get("distance", 10))
        start_depth = -distance / 2 if feature.parameters.get("symmetric") else 0.0
        plane = sketch.parameters.get("plane", "XY")

        def point3(point, depth):
            if plane == "XZ":
                return point[0], depth, point[1]
            if plane == "YZ":
                return depth, point[0], point[1]
            return point[0], point[1], depth

        circles = [entity for entity in sketch.parameters.get("entities", []) if entity.get("type") == "circle" and not entity.get("construction")]
        if circles:
            circle = circles[0]
            ring = [
                (circle["center"][0] + circle["radius"] * math.cos(2 * math.pi * step / 48),
                 circle["center"][1] + circle["radius"] * math.sin(2 * math.pi * step / 48))
                for step in range(48)
            ]
            bottom = [self._project(*point3(point, start_depth)) for point in ring]
            top = [self._project(*point3(point, start_depth + distance)) for point in ring]
            painter.setBrush(QColor(77, 152, 209, 210))
            painter.setPen(QPen(QColor("#a9c8df"), 1.2))
            painter.drawPolygon(QPolygonF(top))
            for index in range(0, 48, 4):
                painter.drawLine(bottom[index], top[index])
            return

        lines = [entity for entity in sketch.parameters.get("entities", []) if entity.get("type") == "line" and not entity.get("construction")]
        if not lines:
            return
        points = [tuple(lines[0]["start"])] + [tuple(line["end"]) for line in lines]
        if points[0] == points[-1]:
            points.pop()

        bottom = [self._project(*point3(point, start_depth)) for point in points]
        top = [self._project(*point3(point, start_depth + distance)) for point in points]
        edge = QColor("#ffd092") if feature.id == self._selected_id else QColor("#a9c8df")
        for index in range(len(points)):
            next_index = (index + 1) % len(points)
            side = QPolygonF([bottom[index], bottom[next_index], top[next_index], top[index]])
            painter.setBrush(QColor(50, 126, 183, 180))
            painter.setPen(QPen(edge, 1.2))
            painter.drawPolygon(side)
        painter.setBrush(QColor(77, 152, 209, 210))
        painter.drawPolygon(QPolygonF(top))

    def _draw_sketch(self, painter: QPainter, feature: Feature) -> None:
        plane = feature.parameters.get("plane", "XY")
        selected = feature.id == self._selected_id
        for entity in feature.parameters.get("entities", []):
            if entity.get("type") == "circle":
                center = entity["center"]
                radius = entity["radius"]
                projected = []
                for step in range(49):
                    angle = 2 * math.pi * step / 48
                    first = center[0] + radius * math.cos(angle)
                    second = center[1] + radius * math.sin(angle)
                    point = (first, 0, second) if plane == "XZ" else (0, first, second) if plane == "YZ" else (first, second, 0)
                    projected.append(self._project(*point))
                painter.setPen(QPen(QColor("#f0a43c") if selected else QColor("#dce8ef"), 2.4))
                painter.drawPolyline(QPolygonF(projected))
                continue
            if entity.get("type") != "line":
                continue
            start_2d, end_2d = entity["start"], entity["end"]
            if plane == "XZ":
                start = (start_2d[0], 0, start_2d[1])
                end = (end_2d[0], 0, end_2d[1])
            elif plane == "YZ":
                start = (0, start_2d[0], start_2d[1])
                end = (0, end_2d[0], end_2d[1])
            else:
                start = (start_2d[0], start_2d[1], 0)
                end = (end_2d[0], end_2d[1], 0)
            color = QColor("#f0a43c") if selected else QColor("#dce8ef")
            pen = QPen(color, 2.4)
            if entity.get("construction"):
                pen.setStyle(Qt.PenStyle.DashLine)
            painter.setPen(pen)
            painter.drawLine(self._project(*start), self._project(*end))

    def _draw_box(self, painter: QPainter, length: float, width: float, height: float, index: int, feature: Feature) -> None:
        ox = index * 8
        points = {(x, y, z): self._project(x + ox, y, z) for x in (-length/2, length/2) for y in (-width/2, width/2) for z in (0, height)}
        faces = [
            [(-length/2,-width/2,height),(length/2,-width/2,height),(length/2,width/2,height),(-length/2,width/2,height)],
            [(-length/2,-width/2,0),(length/2,-width/2,0),(length/2,-width/2,height),(-length/2,-width/2,height)],
            [(length/2,-width/2,0),(length/2,width/2,0),(length/2,width/2,height),(length/2,-width/2,height)],
        ]
        colors = [QColor("#4d98d1"), QColor("#347eb7"), QColor("#276795")]
        if feature.id == self._selected_id:
            colors = [QColor("#f0a43c"), QColor("#d3862e"), QColor("#b86b1f")]
        for face, color in zip(faces, colors, strict=True):
            polygon = QPolygonF([points[key] for key in face])
            painter.setBrush(color)
            painter.setPen(QPen(QColor("#a9c8df"), 1.2))
            painter.drawPolygon(polygon)

    def _draw_cylinder(self, painter: QPainter, diameter: float, height: float, index: int, feature: Feature) -> None:
        center = self._project(index * 8, 0, height / 2)
        radius = diameter * self.zoom * 1.65
        color = QColor("#f0a43c") if feature.id == self._selected_id else QColor("#4d98d1")
        painter.setBrush(color)
        painter.setPen(QPen(QColor("#acd1e9"), 1.2))
        painter.drawEllipse(center, radius, max(8.0, radius * 0.58))
        top = self._project(index * 8, 0, height)
        painter.drawEllipse(top, radius, max(8.0, radius * 0.58))
        painter.drawLine(QPointF(center.x()-radius, center.y()), QPointF(top.x()-radius, top.y()))
        painter.drawLine(QPointF(center.x()+radius, center.y()), QPointF(top.x()+radius, top.y()))

    def _draw_imported(self, painter: QPainter, index: int, feature: Feature) -> None:
        center = self._project(index * 8, 0, 20)
        path = QPainterPath()
        path.addRoundedRect(center.x()-45, center.y()-32, 90, 64, 8, 8)
        painter.setBrush(QColor("#f0a43c") if feature.id == self._selected_id else QColor("#5b7990"))
        painter.setPen(QPen(QColor("#b7c7d3"), 1.2))
        painter.drawPath(path)
        painter.drawText(path.boundingRect(), Qt.AlignmentFlag.AlignCenter, "Imported\nBody")

    def _draw_axes(self, painter: QPainter) -> None:
        origin = QPointF(54, self.height() - 48)
        axes = [(QPointF(34, 10), QColor("#ef5350"), "X"), (QPointF(-28, 14), QColor("#66bb6a"), "Y"), (QPointF(0, -34), QColor("#42a5f5"), "Z")]
        for delta, color, label in axes:
            painter.setPen(QPen(color, 2.5))
            painter.drawLine(origin, origin + delta)
            painter.drawText(origin + delta + QPointF(3, 3), label)
