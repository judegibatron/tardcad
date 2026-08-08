from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import QStandardPaths, Qt, QSize
from PySide6.QtGui import QAction, QCloseEvent, QColor, QKeySequence
from PySide6.QtWidgets import (
    QAbstractItemView,
    QComboBox,
    QDockWidget,
    QFileDialog,
    QInputDialog,
    QLabel,
    QMainWindow,
    QMessageBox,
    QPlainTextEdit,
    QSplitter,
    QTableWidget,
    QTableWidgetItem,
    QTabWidget,
    QToolBar,
    QTreeWidget,
    QTreeWidgetItem,
    QVBoxLayout,
    QWidget,
)

from tardcad.core.document import CadDocument, DocumentHistory, Feature
from tardcad.core.geometry import GeometryService, GeometryUnavailable
from tardcad.core.recovery import RecoveryManager
from tardcad.core.scripting import ScriptSession
from tardcad.core.units import from_mm, precision_for, to_mm
from tardcad.ui.dialogs import BooleanDialog, ExtrudeDialog, MateDialog, PrimitiveDialog, SourceFeatureDialog
from tardcad.ui.sketcher import SketchDialog
from tardcad.ui.theme import STYLE_SHEET
from tardcad.ui.viewport import CadViewport


class MainWindow(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle("TardCAD — Untitled")
        self.resize(1440, 900)
        self.setMinimumSize(1000, 650)
        self.setStyleSheet(STYLE_SHEET)
        self.geometry = GeometryService()
        recovery_root = Path(QStandardPaths.writableLocation(QStandardPaths.StandardLocation.AppDataLocation)) / "Recovery"
        self.recovery = RecoveryManager(recovery_root)
        self.document = CadDocument()
        self.script_session = ScriptSession(self.document, self.refresh_all)
        self._create_actions()
        self._create_menu()
        self._create_ribbon()
        self._create_workspace()
        self._create_docks()
        self._create_status_toolbar()
        self._bind_document(self.document)
        self._offer_recovery()
        self.statusBar().showMessage("Ready")

    def _action(self, text: str, slot, shortcut: str | QKeySequence | None = None, tip: str = "") -> QAction:
        action = QAction(text, self)
        action.triggered.connect(slot)
        if shortcut:
            action.setShortcut(shortcut)
        action.setToolTip(tip or text)
        return action

    def _create_actions(self) -> None:
        self.actions = {
            "new": self._action("New Part", self.new_document, QKeySequence.StandardKey.New),
            "new_assembly": self._action("New Assembly", self.new_assembly, "Ctrl+Shift+N"),
            "open": self._action("Open", self.open_document, QKeySequence.StandardKey.Open),
            "save": self._action("Save", self.save_document, QKeySequence.StandardKey.Save),
            "save_as": self._action("Save As…", self.save_document_as, QKeySequence.StandardKey.SaveAs),
            "import": self._action("Import STEP/STL…", self.import_geometry, "Ctrl+I"),
            "export": self._action("Export STEP/STL…", self.export_geometry, "Ctrl+E"),
            "exit": self._action("Exit", self.close, QKeySequence.StandardKey.Quit),
            "undo": self._action("Undo", self.undo, QKeySequence.StandardKey.Undo),
            "redo": self._action("Redo", self.redo, QKeySequence.StandardKey.Redo),
            "box": self._action("Box", lambda: self.add_primitive("box"), tip="Create a parametric box"),
            "cylinder": self._action("Cylinder", lambda: self.add_primitive("cylinder")),
            "sphere": self._action("Sphere", lambda: self.add_primitive("sphere")),
            "sketch": self._action("Sketch", self.add_sketch),
            "extrude": self._action("Extrude", self.add_extrude),
            "revolve": self._action("Revolve", self.add_revolve),
            "fillet": self._action("Fillet", lambda: self.add_source_operation("fillet")),
            "chamfer": self._action("Chamfer", lambda: self.add_source_operation("chamfer")),
            "linear": self._action("Linear Pattern", lambda: self.add_source_operation("linear_pattern")),
            "circular": self._action("Circular Pattern", lambda: self.add_source_operation("circular_pattern")),
            "boolean": self._action("Boolean", self.add_boolean),
            "mate": self._action("Mate", self.add_mate),
            "component": self._action("Insert Component", self.insert_component),
            "fit": self._action("Fit", lambda: self.viewport.fit(), "F"),
            "iso": self._action("Isometric", lambda: self.viewport.set_view(-35, 25), "0"),
            "front": self._action("Front", lambda: self.viewport.set_view(0, 0), "1"),
            "top": self._action("Top", lambda: self.viewport.set_view(0, 90), "2"),
            "delete": self._action("Delete", self.delete_selected, QKeySequence.StandardKey.Delete),
            "about": self._action("About TardCAD", self.about),
        }

    def _create_menu(self) -> None:
        file_menu = self.menuBar().addMenu("&File")
        for key in ("new", "new_assembly", "open", "save", "save_as", "import", "export", "exit"):
            file_menu.addAction(self.actions[key])
        edit_menu = self.menuBar().addMenu("&Edit")
        edit_menu.addAction(self.actions["undo"])
        edit_menu.addAction(self.actions["redo"])
        edit_menu.addSeparator()
        edit_menu.addAction(self.actions["delete"])
        view_menu = self.menuBar().addMenu("&View")
        for key in ("fit", "iso", "front", "top"):
            view_menu.addAction(self.actions[key])
        help_menu = self.menuBar().addMenu("&Help")
        help_menu.addAction(self.actions["about"])

    def _create_ribbon(self) -> None:
        ribbon = QToolBar("Command Manager")
        ribbon.setObjectName("CommandManager")
        ribbon.setMovable(False)
        ribbon.setIconSize(QSize(28, 28))
        ribbon.setToolButtonStyle(Qt.ToolButtonStyle.ToolButtonTextUnderIcon)
        self.addToolBar(Qt.ToolBarArea.TopToolBarArea, ribbon)
        for key in ("sketch", "extrude", "revolve", "fillet", "chamfer", "linear", "circular", "boolean"):
            ribbon.addAction(self.actions[key])
        ribbon.addSeparator()
        for key in ("box", "cylinder", "sphere"):
            ribbon.addAction(self.actions[key])
        ribbon.addSeparator()
        for key in ("component", "mate"):
            ribbon.addAction(self.actions[key])
        ribbon.addSeparator()
        for key in ("fit", "iso"):
            ribbon.addAction(self.actions[key])

    def _create_workspace(self) -> None:
        self.viewport = CadViewport()
        self.viewport.selection_changed.connect(self.select_feature)
        self.documents = QTabWidget()
        self.documents.setTabsClosable(False)
        self.documents.addTab(self.viewport, "Part 1")
        self.setCentralWidget(self.documents)

    def _create_docks(self) -> None:
        left = QDockWidget("FeatureManager", self)
        left.setObjectName("FeatureManagerDock")
        left_panel = QWidget()
        left_layout = QVBoxLayout(left_panel)
        left_layout.setContentsMargins(4, 4, 4, 4)
        self.tree = QTreeWidget()
        self.tree.setHeaderHidden(True)
        self.tree.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        self.tree.itemSelectionChanged.connect(self._tree_selection_changed)
        self.tree.itemDoubleClicked.connect(self._tree_item_double_clicked)
        left_layout.addWidget(self.tree)
        left.setWidget(left_panel)
        self.addDockWidget(Qt.DockWidgetArea.LeftDockWidgetArea, left)

        right = QDockWidget("PropertyManager", self)
        right.setObjectName("PropertyManagerDock")
        self.properties = QTableWidget(0, 2)
        self.properties.setHorizontalHeaderLabels(["Property", "Value"])
        self.properties.horizontalHeader().setStretchLastSection(True)
        self.properties.verticalHeader().hide()
        self.properties.itemChanged.connect(self._property_changed)
        right.setWidget(self.properties)
        self.addDockWidget(Qt.DockWidgetArea.RightDockWidgetArea, right)

        bottom = QDockWidget("Python Console", self)
        bottom.setObjectName("PythonConsoleDock")
        console_split = QSplitter(Qt.Orientation.Vertical)
        self.console_output = QPlainTextEdit("TardCAD Python console\n'doc' is the active document.\n")
        self.console_output.setReadOnly(True)
        self.console_input = QPlainTextEdit()
        self.console_input.setPlaceholderText("Enter Python, then press Ctrl+Enter to run…")
        self.console_input.setMaximumHeight(85)
        shortcut = QAction(self)
        shortcut.setShortcut("Ctrl+Return")
        shortcut.triggered.connect(self.run_script)
        self.console_input.addAction(shortcut)
        console_split.addWidget(self.console_output)
        console_split.addWidget(self.console_input)
        bottom.setWidget(console_split)
        self.addDockWidget(Qt.DockWidgetArea.BottomDockWidgetArea, bottom)
        bottom.hide()
        self.menuBar().addAction(bottom.toggleViewAction())

    def _create_status_toolbar(self) -> None:
        self.statusBar().setSizeGripEnabled(True)
        self.statusBar().addPermanentWidget(QLabel("Units:"))
        self.unit_selector = QComboBox()
        self.unit_selector.addItems(["mm", "cm", "m", "in"])
        self.unit_selector.setToolTip("Document display units; geometry remains physically unchanged")
        self.unit_selector.currentTextChanged.connect(self.change_units)
        self.statusBar().addPermanentWidget(self.unit_selector)
        backend = "Open CASCADE BREP" if self.geometry.available else "Geometry backend unavailable"
        self.backend_label = QLabel(f"  |  {backend}  ")
        self.statusBar().addPermanentWidget(self.backend_label)

    def _bind_document(self, document: CadDocument) -> None:
        self.document = document
        self.viewport.set_document(document)
        self.document.subscribe(self.refresh_all)
        self.script_session = ScriptSession(document, self.refresh_all)
        self.history = DocumentHistory(document)
        self.document.subscribe(self._autosave)
        self.unit_selector.blockSignals(True)
        self.unit_selector.setCurrentText(document.units)
        self.unit_selector.blockSignals(False)
        self.refresh_all()

    def _autosave(self) -> None:
        if self.document.dirty:
            try:
                self.recovery.write(self.document)
            except OSError:
                self.statusBar().showMessage("Recovery autosave failed", 4000)

    def _offer_recovery(self) -> None:
        recoveries = self.recovery.available()
        if not recoveries:
            return
        latest = recoveries[0]
        answer = QMessageBox.question(
            self,
            "Recover document",
            "TardCAD found an autosaved document from an earlier session. Recover it?",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
        )
        if answer == QMessageBox.StandardButton.Yes:
            try:
                self._bind_document(self.recovery.load(latest))
            except (OSError, ValueError) as error:
                QMessageBox.warning(self, "Recovery failed", str(error))
        latest.unlink(missing_ok=True)

    def undo(self) -> None:
        if self.history.undo():
            self.statusBar().showMessage("Undo", 2000)

    def redo(self) -> None:
        if self.history.redo():
            self.statusBar().showMessage("Redo", 2000)

    def change_units(self, unit: str) -> None:
        self.document.set_units(unit)
        feature = self.document.feature(self.selected_feature_id() or "")
        self.select_feature(feature)
        self.statusBar().showMessage(f"Dimensions converted for display in {unit}", 3500)

    def refresh_all(self) -> None:
        selected = self.selected_feature_id()
        self.tree.blockSignals(True)
        self.tree.clear()
        root = QTreeWidgetItem([self.document.name])
        root.setExpanded(True)
        root.addChild(QTreeWidgetItem(["Origin"]))
        for feature in self.document.features:
            item = QTreeWidgetItem([feature.name])
            item.setData(0, Qt.ItemDataRole.UserRole, feature.id)
            if feature.suppressed:
                item.setForeground(0, QColor("#77818d"))
            if feature.id in self.viewport.kernel_errors:
                item.setForeground(0, QColor("#ef5350"))
                item.setToolTip(0, self.viewport.kernel_errors[feature.id])
            root.addChild(item)
            if feature.id == selected:
                self.tree.setCurrentItem(item)
        self.tree.addTopLevelItem(root)
        root.setExpanded(True)
        self.tree.blockSignals(False)
        self.documents.setTabText(0, f"{self.document.name}{'*' if self.document.dirty else ''}")
        self.setWindowTitle(f"TardCAD — {self.document.name}{'*' if self.document.dirty else ''}")
        self.viewport.update()

    def selected_feature_id(self) -> str | None:
        item = self.tree.currentItem() if hasattr(self, "tree") else None
        return item.data(0, Qt.ItemDataRole.UserRole) if item else None

    def _tree_selection_changed(self) -> None:
        feature = self.document.feature(self.selected_feature_id() or "")
        self.select_feature(feature)

    def select_feature(self, feature: Feature | None) -> None:
        self.viewport._selected_id = feature.id if feature else None
        self.viewport.update()
        self.properties.blockSignals(True)
        self.properties.setRowCount(0)
        if feature:
            values = {"Name": feature.name, "Type": feature.kind, **feature.parameters}
            for key, value in values.items():
                row = self.properties.rowCount()
                self.properties.insertRow(row)
                key_item = QTableWidgetItem(str(key))
                key_item.setFlags(key_item.flags() & ~Qt.ItemFlag.ItemIsEditable)
                if key in {"length", "width", "height", "diameter", "distance", "radius", "spacing"} and isinstance(value, (int, float)):
                    shown = from_mm(float(value), self.document.units)
                    value_item = QTableWidgetItem(f"{shown:.{precision_for(self.document.units)}f}")
                    value_item.setToolTip(f"{self.document.units}; stored internally in mm")
                else:
                    value_item = QTableWidgetItem(str(value))
                if key == "Type":
                    value_item.setFlags(value_item.flags() & ~Qt.ItemFlag.ItemIsEditable)
                if key not in {"Name", "length", "width", "height", "diameter", "distance", "radius", "spacing", "angle", "count"}:
                    value_item.setFlags(value_item.flags() & ~Qt.ItemFlag.ItemIsEditable)
                value_item.setData(Qt.ItemDataRole.UserRole, feature.id)
                self.properties.setItem(row, 0, key_item)
                self.properties.setItem(row, 1, value_item)
        self.properties.blockSignals(False)

    def _property_changed(self, item: QTableWidgetItem) -> None:
        if item.column() != 1:
            return
        feature = self.document.feature(item.data(Qt.ItemDataRole.UserRole) or "")
        key_item = self.properties.item(item.row(), 0)
        if not feature or not key_item:
            return
        key = key_item.text()
        try:
            if key == "Name":
                feature.name = item.text()
                self.document._changed()
            elif key not in ("Type",):
                value = float(item.text())
                if key in {"length", "width", "height", "diameter", "distance", "radius", "spacing"}:
                    value = to_mm(value, self.document.units)
                elif key == "count":
                    value = int(value)
                self.document.update_parameter(feature.id, key, value)
        except ValueError:
            QMessageBox.warning(self, "Invalid value", f"{key} must be a number.")
            self.select_feature(feature)

    def add_primitive(self, kind: str) -> None:
        dialog = PrimitiveDialog(kind, self.document.units, self)
        if dialog.exec():
            name, values = dialog.values()
            feature = self.document.add_feature(kind, name, **values)
            self.refresh_all()
            self._select_tree_feature(feature.id)
            self.statusBar().showMessage(f"Created {name}", 3000)

    def add_sketch(self) -> None:
        name, accepted = QInputDialog.getText(self, "New Sketch", "Sketch name:", text="Sketch1")
        if accepted:
            dialog = SketchDialog(name or "Sketch", unit=self.document.units, parent=self)
            if dialog.exec():
                plane, entities, relations, constraint_status = dialog.result_data()
                feature = self.document.add_feature(
                    "sketch",
                    name or "Sketch",
                    plane=plane,
                    entities=entities,
                    relations=relations,
                    constraint_status=constraint_status,
                )
                self._select_tree_feature(feature.id)

    def add_extrude(self) -> None:
        sketches = [feature for feature in self.document.features if feature.kind == "sketch" and not feature.suppressed]
        selected = self.document.feature(self.selected_feature_id() or "")
        dialog = ExtrudeDialog(
            sketches,
            selected.id if selected and selected.kind == "sketch" else None,
            self.document.units,
            self,
        )
        if not sketches:
            QMessageBox.information(self, "Extrude", "Create a closed sketch before extruding a solid.")
            return
        if dialog.exec():
            name, sketch_id, distance, symmetric = dialog.values()
            feature = Feature(
                kind="extrude",
                name=name,
                parameters={"sketch_id": sketch_id, "distance": distance, "symmetric": symmetric},
            )
            try:
                shape = self.geometry.build_feature(feature, self.document)
                volume = self.geometry.volume(shape)
            except (GeometryUnavailable, ValueError) as error:
                QMessageBox.warning(self, "Extrude failed", str(error))
                return
            self.document.features.append(feature)
            self.document._changed()
            self._select_tree_feature(feature.id)
            self.statusBar().showMessage(f"Open CASCADE solid created — volume {volume:.3f} mm³", 6000)

    def _solid_sources(self) -> list[Feature]:
        return [
            feature
            for feature in self.document.features
            if feature.kind not in {"sketch"} and not feature.suppressed
        ]

    def _commit_solid_feature(self, feature: Feature) -> None:
        shapes, errors = self.geometry.recompute(self.document)
        try:
            shape = self.geometry.build_feature(feature, self.document, shapes)
            volume = self.geometry.volume(shape)
        except (GeometryUnavailable, ValueError, RuntimeError) as error:
            QMessageBox.warning(self, f"{feature.name} failed", str(error))
            return
        self.document.features.append(feature)
        self.document._changed()
        self._select_tree_feature(feature.id)
        self.statusBar().showMessage(f"{feature.name} recomputed — volume {volume:.3f} mm³", 6000)

    def add_revolve(self) -> None:
        sketches = [feature for feature in self.document.features if feature.kind == "sketch" and not feature.suppressed]
        if not sketches:
            QMessageBox.information(self, "Revolve", "Create a closed sketch before revolving a solid.")
            return
        selected = self.document.feature(self.selected_feature_id() or "")
        dialog = SourceFeatureDialog("Revolve", sketches, [("angle", "Angle", 360.0)], selected.id if selected else None, self.document.units, True, self)
        if dialog.exec():
            name, sketch_id, values = dialog.values()
            self._commit_solid_feature(Feature("revolve", name, {"sketch_id": sketch_id, **values}))

    def add_source_operation(self, kind: str) -> None:
        sources = self._solid_sources()
        if not sources:
            QMessageBox.information(self, kind.replace("_", " ").title(), "Create a solid feature first.")
            return
        selected_id = self.selected_feature_id()
        definitions = {
            "fillet": ("Fillet", [("radius", "Radius", 2.0)], False),
            "chamfer": ("Chamfer", [("distance", "Distance", 2.0)], False),
            "linear_pattern": ("Linear Pattern", [("count", "Instances", 3), ("spacing", "Spacing", 20.0)], True),
            "circular_pattern": ("Circular Pattern", [("count", "Instances", 4), ("angle", "Total angle", 360.0)], True),
        }
        title, fields, axes = definitions[kind]
        dialog = SourceFeatureDialog(title, sources, fields, selected_id, self.document.units, axes, self)
        if dialog.exec():
            name, source_id, values = dialog.values()
            if "count" in values:
                values["count"] = int(values["count"])
            self._commit_solid_feature(Feature(kind, name, {"source_id": source_id, **values}))

    def add_boolean(self) -> None:
        sources = self._solid_sources()
        if len(sources) < 2:
            QMessageBox.information(self, "Boolean", "Create at least two solid features first.")
            return
        dialog = BooleanDialog(sources, self)
        if dialog.exec():
            name, first_id, second_id, operation = dialog.values()
            if first_id == second_id:
                QMessageBox.warning(self, "Boolean", "Choose two different solid features.")
                return
            self._commit_solid_feature(Feature("boolean", name, {"first_id": first_id, "second_id": second_id, "operation": operation}))

    def insert_component(self) -> None:
        if self.document.document_type != "assembly":
            QMessageBox.information(self, "Insert Component", "Create or open an assembly document first.")
            return
        path, _ = QFileDialog.getOpenFileName(self, "Insert TardCAD Part", "", "TardCAD Part (*.tprt)")
        if not path:
            return
        try:
            part = CadDocument.load(path)
            if part.document_type != "part":
                raise ValueError("Only .tprt part documents can be inserted")
            if not self.geometry.build_document(part):
                raise ValueError("The selected part contains no solid bodies")
        except (OSError, ValueError, RuntimeError) as error:
            QMessageBox.warning(self, "Insert Component", str(error))
            return
        feature = self.document.add_feature(
            "component",
            Path(path).stem,
            source=str(Path(path).resolve()),
            placement=[0, 0, 0, 0, 0, 0],
            fixed=not any(item.kind == "component" for item in self.document.features),
        )
        self._select_tree_feature(feature.id)

    def add_mate(self) -> None:
        if self.document.document_type != "assembly":
            QMessageBox.information(self, "Mate", "Mates are available in assembly documents.")
            return
        components = [feature for feature in self.document.features if feature.kind == "component" and not feature.suppressed]
        if len(components) < 2:
            QMessageBox.information(self, "Mate", "Insert at least two components before adding a mate.")
            return
        dialog = MateDialog(components, self.document.units, self)
        if dialog.exec():
            name, parameters = dialog.values()
            if parameters["component_a"] == parameters["component_b"]:
                QMessageBox.warning(self, "Mate", "Choose two different components.")
                return
            self.document.add_feature("mate", name, **parameters)

    def _tree_item_double_clicked(self, item: QTreeWidgetItem) -> None:
        feature = self.document.feature(item.data(0, Qt.ItemDataRole.UserRole) or "")
        if feature and feature.kind == "sketch":
            self.edit_sketch(feature)

    def edit_sketch(self, feature: Feature) -> None:
        dialog = SketchDialog(
            feature.name,
            feature.parameters.get("plane", "XY"),
            feature.parameters.get("entities", []),
            feature.parameters.get("relations", []),
            self.document.units,
            self,
        )
        if dialog.exec():
            plane, entities, relations, constraint_status = dialog.result_data()
            feature.parameters["plane"] = plane
            feature.parameters["entities"] = entities
            feature.parameters["relations"] = relations
            feature.parameters["constraint_status"] = constraint_status
            self.document._changed()

    def _select_tree_feature(self, feature_id: str) -> None:
        iterator = self.tree.findItems("*", Qt.MatchFlag.MatchWildcard | Qt.MatchFlag.MatchRecursive)
        for item in iterator:
            if item.data(0, Qt.ItemDataRole.UserRole) == feature_id:
                self.tree.setCurrentItem(item)
                break

    def delete_selected(self) -> None:
        feature_id = self.selected_feature_id()
        if feature_id:
            self.document.remove_feature(feature_id)
            self.properties.setRowCount(0)

    def new_document(self) -> None:
        if self._confirm_discard():
            self._bind_document(CadDocument("Part 1", "part"))

    def new_assembly(self) -> None:
        if self._confirm_discard():
            self._bind_document(CadDocument("Assembly 1", "assembly"))

    def open_document(self) -> None:
        if not self._confirm_discard():
            return
        path, _ = QFileDialog.getOpenFileName(
            self,
            "Open TardCAD Document",
            "",
            "TardCAD Documents (*.tprt *.tasm);;TardCAD Parts (*.tprt);;TardCAD Assemblies (*.tasm);;Legacy Prototype (*.tardcad)",
        )
        if path:
            try:
                self._bind_document(CadDocument.load(path))
            except (OSError, ValueError) as error:
                QMessageBox.critical(self, "Open failed", str(error))

    def save_document(self) -> bool:
        if not self.document.path:
            return self.save_document_as()
        try:
            self.document.save()
            self.recovery.clear(self.document)
            self.refresh_all()
            self.statusBar().showMessage(f"Saved {self.document.path}", 3000)
            return True
        except OSError as error:
            QMessageBox.critical(self, "Save failed", str(error))
            return False

    def save_document_as(self) -> bool:
        extension = ".tasm" if self.document.document_type == "assembly" else ".tprt"
        file_filter = "TardCAD Assembly (*.tasm)" if extension == ".tasm" else "TardCAD Part (*.tprt)"
        path, _ = QFileDialog.getSaveFileName(
            self, "Save TardCAD Document", f"{self.document.name}{extension}", file_filter
        )
        if not path:
            return False
        if not path.lower().endswith(extension):
            path += extension
        self.document.name = Path(path).stem
        self.document.save(path)
        self.recovery.clear(self.document)
        self.refresh_all()
        return True

    def import_geometry(self) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "Import geometry", "", "CAD geometry (*.step *.stp *.stl)")
        if not path:
            return
        try:
            if path.lower().endswith(".stl"):
                self.geometry.import_stl(path)
            else:
                self.geometry.import_step(path)
            feature = self.document.add_feature("imported", Path(path).stem, source=str(Path(path).resolve()))
            self.refresh_all()
        except (GeometryUnavailable, OSError, ValueError) as error:
            QMessageBox.warning(self, "Import unavailable", str(error))

    def export_geometry(self) -> None:
        path, _ = QFileDialog.getSaveFileName(self, "Export geometry", f"{self.document.name}.step", "STEP (*.step);;STL (*.stl)")
        if path:
            try:
                self.geometry.export(self.document, path)
                self.statusBar().showMessage(f"Exported {path}", 4000)
            except (GeometryUnavailable, OSError, ValueError) as error:
                QMessageBox.warning(self, "Export unavailable", str(error))

    def run_script(self) -> None:
        source = self.console_input.toPlainText().strip()
        if source:
            self.console_output.appendPlainText(f">>> {source}")
            output = self.script_session.execute(source)
            if output:
                self.console_output.appendPlainText(output.rstrip())
            self.console_input.clear()

    def _confirm_discard(self) -> bool:
        if not self.document.dirty:
            return True
        answer = QMessageBox.question(self, "Unsaved changes", "Save changes before continuing?", QMessageBox.StandardButton.Save | QMessageBox.StandardButton.Discard | QMessageBox.StandardButton.Cancel)
        if answer == QMessageBox.StandardButton.Save:
            return self.save_document()
        return answer == QMessageBox.StandardButton.Discard

    def closeEvent(self, event: QCloseEvent) -> None:
        if self._confirm_discard():
            self.recovery.clear(self.document)
            event.accept()
        else:
            event.ignore()

    def about(self) -> None:
        QMessageBox.about(self, "About TardCAD", "<h2>TardCAD 0.2 MVP</h2><p>Open mechanical solid modeling.</p><p>GPL-3.0-or-later · Qt 6 · Open CASCADE</p>")
