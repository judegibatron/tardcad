from __future__ import annotations

from pathlib import Path
from typing import Any

from tardcad.core.document import CadDocument, Feature
from tardcad.core.assembly import AssemblySolver, Placement

try:
    from OCP.BRep import BRep_Builder
    from OCP.BRep import BRep_Tool
    from OCP.BRepMesh import BRepMesh_IncrementalMesh
    from OCP.BRepBuilderAPI import BRepBuilderAPI_MakeFace, BRepBuilderAPI_MakePolygon
    from OCP.BRepBuilderAPI import BRepBuilderAPI_Transform
    from OCP.BRepAlgoAPI import BRepAlgoAPI_Common, BRepAlgoAPI_Cut, BRepAlgoAPI_Fuse
    from OCP.BRepFilletAPI import BRepFilletAPI_MakeChamfer, BRepFilletAPI_MakeFillet
    from OCP.BRepGProp import BRepGProp
    from OCP.BRepPrimAPI import (
        BRepPrimAPI_MakeBox,
        BRepPrimAPI_MakeCylinder,
        BRepPrimAPI_MakePrism,
        BRepPrimAPI_MakeRevol,
        BRepPrimAPI_MakeSphere,
    )
    from OCP.GProp import GProp_GProps
    from OCP.IFSelect import IFSelect_RetDone
    from OCP.STEPControl import STEPControl_AsIs, STEPControl_Reader, STEPControl_Writer
    from OCP.StlAPI import StlAPI_Reader, StlAPI_Writer
    from OCP.TopoDS import TopoDS_Compound, TopoDS_Shape
    from OCP.TopAbs import TopAbs_EDGE
    from OCP.TopAbs import TopAbs_FACE
    from OCP.TopExp import TopExp_Explorer
    from OCP.TopoDS import TopoDS
    from OCP.TopLoc import TopLoc_Location
    from OCP.gp import gp_Ax1, gp_Ax2, gp_Dir, gp_Pnt, gp_Trsf, gp_Vec

    OCCT_AVAILABLE = True
except ImportError:  # pragma: no cover - depends on optional native package
    OCCT_AVAILABLE = False


class GeometryUnavailable(RuntimeError):
    pass


class InvalidProfile(ValueError):
    pass


class RecomputeError(RuntimeError):
    pass


class GeometryService:
    """Non-graphical BREP modeling service backed directly by Open CASCADE."""

    @property
    def available(self) -> bool:
        return OCCT_AVAILABLE

    def _require_backend(self) -> None:
        if not OCCT_AVAILABLE:
            raise GeometryUnavailable("Install cadquery-ocp to enable Open CASCADE solid modeling")

    def build_feature(
        self,
        feature: Feature,
        document: CadDocument | None = None,
        cache: dict[str, Any] | None = None,
    ) -> Any:
        self._require_backend()
        p = feature.parameters
        if feature.kind == "box":
            return BRepPrimAPI_MakeBox(p["length"], p["width"], p["height"]).Shape()
        if feature.kind == "cylinder":
            return BRepPrimAPI_MakeCylinder(p["diameter"] / 2, p["height"]).Shape()
        if feature.kind == "sphere":
            return BRepPrimAPI_MakeSphere(p["diameter"] / 2).Shape()
        if feature.kind == "extrude":
            if document is None:
                raise ValueError("Extrude recompute requires its source document")
            sketch = document.feature(p["sketch_id"])
            if sketch is None or sketch.kind != "sketch":
                raise InvalidProfile("The extrusion's source sketch is missing")
            return self._extrude_sketch(sketch, float(p["distance"]), bool(p.get("symmetric", False)))
        if feature.kind == "revolve":
            if document is None:
                raise ValueError("Revolve recompute requires its source document")
            sketch = document.feature(p["sketch_id"])
            if sketch is None:
                raise InvalidProfile("The revolve's source sketch is missing")
            return self._revolve_sketch(sketch, float(p.get("angle", 360.0)), p.get("axis", "X"))
        if feature.kind in {"fillet", "chamfer", "linear_pattern", "circular_pattern"}:
            source = self._source_shape(p["source_id"], document, cache)
            if feature.kind == "fillet":
                return self._fillet(source, float(p["radius"]))
            if feature.kind == "chamfer":
                return self._chamfer(source, float(p["distance"]))
            if feature.kind == "linear_pattern":
                return self._linear_pattern(source, int(p["count"]), float(p["spacing"]), p.get("axis", "X"))
            return self._circular_pattern(source, int(p["count"]), float(p.get("angle", 360.0)), p.get("axis", "Z"))
        if feature.kind == "boolean":
            first = self._source_shape(p["first_id"], document, cache)
            second = self._source_shape(p["second_id"], document, cache)
            operation = p.get("operation", "fuse")
            builder = BRepAlgoAPI_Cut(first, second) if operation == "cut" else BRepAlgoAPI_Common(first, second) if operation == "common" else BRepAlgoAPI_Fuse(first, second)
            builder.Build()
            if not builder.IsDone():
                raise RecomputeError(f"Boolean {operation} failed")
            return builder.Shape()
        if feature.kind == "component":
            return self._component_shape(feature, Placement.from_feature(feature))
        if feature.kind == "imported":
            source = Path(p["source"])
            return self.import_stl(source) if source.suffix.lower() == ".stl" else self.import_step(source)
        raise ValueError(f"Feature '{feature.kind}' does not produce a solid")

    def _source_shape(self, source_id: str, document: CadDocument | None, cache: dict[str, Any] | None) -> Any:
        if document is None:
            raise RecomputeError("Source feature requires a document")
        if cache is not None and source_id in cache:
            return cache[source_id]
        source = document.feature(source_id)
        if source is None:
            raise RecomputeError(f"Source feature {source_id} is missing")
        return self.build_feature(source, document, cache)

    def _component_shape(self, feature: Feature, placement: Placement) -> Any:
        source = Path(feature.parameters["source"])
        if not source.exists():
            raise RecomputeError(f"Linked part not found: {source}")
        part = CadDocument.load(source)
        shapes = self.build_document(part)
        if not shapes:
            raise RecomputeError(f"Linked part contains no solids: {source}")
        shape = shapes[0] if len(shapes) == 1 else self._compound(shapes)
        for axis, angle in (("X", placement.rx), ("Y", placement.ry), ("Z", placement.rz)):
            if angle:
                direction = gp_Dir(1, 0, 0) if axis == "X" else gp_Dir(0, 1, 0) if axis == "Y" else gp_Dir(0, 0, 1)
                transform = gp_Trsf()
                transform.SetRotation(gp_Ax1(gp_Pnt(0, 0, 0), direction), angle * 3.141592653589793 / 180.0)
                shape = BRepBuilderAPI_Transform(shape, transform, True).Shape()
        transform = gp_Trsf()
        transform.SetTranslation(gp_Vec(placement.x, placement.y, placement.z))
        return BRepBuilderAPI_Transform(shape, transform, True).Shape()

    def _ordered_profile(self, sketch: Feature) -> list[tuple[float, float]]:
        lines = [entity for entity in sketch.parameters.get("entities", []) if entity.get("type") == "line" and not entity.get("construction")]
        if len(lines) < 3:
            raise InvalidProfile("A solid extrusion requires at least three non-construction lines")
        points = [tuple(float(value) for value in lines[0]["start"])]
        current = tuple(float(value) for value in lines[0]["end"])
        points.append(current)
        for line in lines[1:]:
            start = tuple(float(value) for value in line["start"])
            end = tuple(float(value) for value in line["end"])
            if self._same_point(start, current):
                current = end
            elif self._same_point(end, current):
                current = start
            else:
                raise InvalidProfile("Sketch lines must form one connected profile")
            points.append(current)
        if not self._same_point(points[0], points[-1]):
            raise InvalidProfile("Sketch profile is open; connect the final endpoint to the first")
        return points[:-1]

    @staticmethod
    def _same_point(first: tuple[float, float], second: tuple[float, float]) -> bool:
        return abs(first[0] - second[0]) < 1e-7 and abs(first[1] - second[1]) < 1e-7

    def _extrude_sketch(self, sketch: Feature, distance: float, symmetric: bool) -> Any:
        profiles = [entity for entity in sketch.parameters.get("entities", []) if not entity.get("construction")]
        circles = [entity for entity in profiles if entity.get("type") == "circle"]
        if circles:
            if len(profiles) != 1:
                raise InvalidProfile("Circle extrusion currently requires one standalone circle")
            circle = circles[0]
            first, second = (float(value) for value in circle["center"])
            plane = sketch.parameters.get("plane", "XY")
            offset = -distance / 2 if symmetric else 0.0
            if plane == "XZ":
                axis = gp_Ax2(gp_Pnt(first, offset, second), gp_Dir(0, 1, 0))
            elif plane == "YZ":
                axis = gp_Ax2(gp_Pnt(offset, first, second), gp_Dir(1, 0, 0))
            else:
                axis = gp_Ax2(gp_Pnt(first, second, offset), gp_Dir(0, 0, 1))
            return BRepPrimAPI_MakeCylinder(axis, float(circle["radius"]), distance).Shape()
        points = self._ordered_profile(sketch)
        plane = sketch.parameters.get("plane", "XY")
        polygon = BRepBuilderAPI_MakePolygon()
        offset = -distance / 2 if symmetric else 0.0
        for first, second in points:
            if plane == "XZ":
                point = gp_Pnt(first, offset, second)
            elif plane == "YZ":
                point = gp_Pnt(offset, first, second)
            else:
                point = gp_Pnt(first, second, offset)
            polygon.Add(point)
        polygon.Close()
        face = BRepBuilderAPI_MakeFace(polygon.Wire()).Face()
        vector = gp_Vec(distance, 0, 0) if plane == "YZ" else gp_Vec(0, distance, 0) if plane == "XZ" else gp_Vec(0, 0, distance)
        return BRepPrimAPI_MakePrism(face, vector).Shape()

    def _profile_face(self, sketch: Feature) -> Any:
        points = self._ordered_profile(sketch)
        plane = sketch.parameters.get("plane", "XY")
        polygon = BRepBuilderAPI_MakePolygon()
        for first, second in points:
            point = gp_Pnt(first, 0, second) if plane == "XZ" else gp_Pnt(0, first, second) if plane == "YZ" else gp_Pnt(first, second, 0)
            polygon.Add(point)
        polygon.Close()
        return BRepBuilderAPI_MakeFace(polygon.Wire()).Face()

    def _revolve_sketch(self, sketch: Feature, angle: float, axis_name: str) -> Any:
        if any(entity.get("type") == "circle" for entity in sketch.parameters.get("entities", [])):
            raise InvalidProfile("Circle profiles are not supported by Revolve; use Extrude")
        face = self._profile_face(sketch)
        plane = sketch.parameters.get("plane", "XY")
        if (plane, axis_name) in {("XY", "Z"), ("XZ", "Y"), ("YZ", "X")}:
            raise InvalidProfile(f"Revolve axis {axis_name} is normal to the {plane} sketch plane")
        direction = gp_Dir(1, 0, 0) if axis_name == "X" else gp_Dir(0, 1, 0) if axis_name == "Y" else gp_Dir(0, 0, 1)
        axis = gp_Ax1(gp_Pnt(0, 0, 0), direction)
        return BRepPrimAPI_MakeRevol(face, axis, angle * 3.141592653589793 / 180.0, True).Shape()

    def _edges(self, shape: Any) -> list[Any]:
        edges = []
        explorer = TopExp_Explorer(shape, TopAbs_EDGE)
        while explorer.More():
            edges.append(TopoDS.Edge_s(explorer.Current()))
            explorer.Next()
        return edges

    def _fillet(self, shape: Any, radius: float) -> Any:
        builder = BRepFilletAPI_MakeFillet(shape)
        for edge in self._edges(shape):
            builder.Add(radius, edge)
        builder.Build()
        if not builder.IsDone():
            raise RecomputeError("Fillet failed; reduce the radius")
        return builder.Shape()

    def _chamfer(self, shape: Any, distance: float) -> Any:
        builder = BRepFilletAPI_MakeChamfer(shape)
        for edge in self._edges(shape):
            builder.Add(distance, edge)
        builder.Build()
        if not builder.IsDone():
            raise RecomputeError("Chamfer failed; reduce the distance")
        return builder.Shape()

    @staticmethod
    def _axis_vector(axis: str, amount: float) -> gp_Vec:
        return gp_Vec(amount, 0, 0) if axis == "X" else gp_Vec(0, amount, 0) if axis == "Y" else gp_Vec(0, 0, amount)

    def _linear_pattern(self, shape: Any, count: int, spacing: float, axis: str) -> Any:
        if count < 1:
            raise RecomputeError("Pattern count must be at least one")
        shapes = []
        for index in range(count):
            transform = gp_Trsf()
            transform.SetTranslation(self._axis_vector(axis, index * spacing))
            shapes.append(BRepBuilderAPI_Transform(shape, transform, True).Shape())
        return self._compound(shapes)

    def _circular_pattern(self, shape: Any, count: int, angle: float, axis: str) -> Any:
        if count < 1:
            raise RecomputeError("Pattern count must be at least one")
        direction = gp_Dir(1, 0, 0) if axis == "X" else gp_Dir(0, 1, 0) if axis == "Y" else gp_Dir(0, 0, 1)
        rotation_axis = gp_Ax1(gp_Pnt(0, 0, 0), direction)
        shapes = []
        step = angle / count
        for index in range(count):
            transform = gp_Trsf()
            transform.SetRotation(rotation_axis, index * step * 3.141592653589793 / 180.0)
            shapes.append(BRepBuilderAPI_Transform(shape, transform, True).Shape())
        return self._compound(shapes)

    def recompute(self, document: CadDocument) -> tuple[dict[str, Any], dict[str, str]]:
        shapes: dict[str, Any] = {}
        errors: dict[str, str] = {}
        placements, mate_errors = AssemblySolver().solve(document) if document.document_type == "assembly" else ({}, {})
        errors.update(mate_errors)
        for feature in document.features:
            if feature.suppressed or feature.kind in {"sketch", "mate"}:
                continue
            try:
                if feature.kind == "component":
                    shapes[feature.id] = self._component_shape(feature, placements[feature.id])
                else:
                    shapes[feature.id] = self.build_feature(feature, document, shapes)
            except Exception as error:
                errors[feature.id] = str(error)
        return shapes, errors

    def build_document(self, document: CadDocument) -> list[Any]:
        shapes, errors = self.recompute(document)
        if errors:
            first_id = next(iter(errors))
            feature = document.feature(first_id)
            raise RecomputeError(f"{feature.name if feature else first_id}: {errors[first_id]}")
        consumed = set()
        for feature in document.features:
            for key in ("source_id", "first_id", "second_id"):
                if key in feature.parameters:
                    consumed.add(feature.parameters[key])
        return [shape for feature_id, shape in shapes.items() if feature_id not in consumed and document.feature(feature_id).visible]

    def volume(self, shape: Any) -> float:
        self._require_backend()
        properties = GProp_GProps()
        BRepGProp.VolumeProperties_s(shape, properties)
        return float(properties.Mass())

    def tessellate(self, shape: Any, tolerance: float = 0.35) -> list[tuple[tuple[float, float, float], ...]]:
        """Return render triangles derived from the kernel shape, independent of UI."""
        self._require_backend()
        BRepMesh_IncrementalMesh(shape, tolerance, False, 0.35, True)
        triangles = []
        explorer = TopExp_Explorer(shape, TopAbs_FACE)
        while explorer.More():
            face = TopoDS.Face_s(explorer.Current())
            location = TopLoc_Location()
            mesh = BRep_Tool.Triangulation_s(face, location)
            if mesh is not None:
                transform = location.Transformation()
                for index in range(1, mesh.NbTriangles() + 1):
                    node_indices = mesh.Triangle(index).Get()
                    points = []
                    for node_index in node_indices:
                        point = mesh.Node(node_index).Transformed(transform)
                        points.append((point.X(), point.Y(), point.Z()))
                    triangles.append(tuple(points))
            explorer.Next()
        return triangles

    def import_step(self, path: str | Path) -> Any:
        self._require_backend()
        reader = STEPControl_Reader()
        if reader.ReadFile(str(path)) != IFSelect_RetDone:
            raise ValueError(f"Open CASCADE could not read STEP file: {path}")
        reader.TransferRoots()
        return reader.OneShape()

    def import_stl(self, path: str | Path) -> Any:
        self._require_backend()
        shape = TopoDS_Shape()
        if not StlAPI_Reader().Read(shape, str(path)):
            raise ValueError(f"Open CASCADE could not read STL file: {path}")
        return shape

    def _compound(self, shapes: list[Any]) -> Any:
        compound = TopoDS_Compound()
        builder = BRep_Builder()
        builder.MakeCompound(compound)
        for shape in shapes:
            builder.Add(compound, shape)
        return compound

    def export(self, document: CadDocument, path: str | Path) -> None:
        self._require_backend()
        shapes = self.build_document(document)
        if not shapes:
            raise ValueError("The document contains no exportable solid geometry")
        shape = shapes[0] if len(shapes) == 1 else self._compound(shapes)
        destination = Path(path)
        if destination.suffix.lower() == ".stl":
            BRepMesh_IncrementalMesh(shape, 0.1, False, 0.35, True)
            if not StlAPI_Writer().Write(shape, str(destination)):
                raise OSError(f"Failed to write {destination}")
            return
        writer = STEPControl_Writer()
        writer.Transfer(shape, STEPControl_AsIs)
        if writer.Write(str(destination)) != IFSelect_RetDone:
            raise OSError(f"Failed to write {destination}")
