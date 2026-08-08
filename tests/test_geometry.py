import pytest

from tardcad.core.document import CadDocument
from tardcad.core.geometry import GeometryService, InvalidProfile


def rectangle_sketch(document: CadDocument):
    return document.add_feature(
        "sketch",
        "Rectangle",
        plane="XY",
        entities=[
            {"type": "line", "start": [0, 0], "end": [20, 0]},
            {"type": "line", "start": [20, 0], "end": [20, 10]},
            {"type": "line", "start": [20, 10], "end": [0, 10]},
            {"type": "line", "start": [0, 10], "end": [0, 0]},
        ],
    )


def test_occt_box_has_real_volume() -> None:
    geometry = GeometryService()
    if not geometry.available:
        pytest.skip("Open CASCADE is not installed")
    document = CadDocument()
    feature = document.add_feature("box", "Box", length=10, width=20, height=30)

    assert geometry.volume(geometry.build_feature(feature, document)) == pytest.approx(6000)


def test_closed_sketch_extrudes_to_brep() -> None:
    geometry = GeometryService()
    if not geometry.available:
        pytest.skip("Open CASCADE is not installed")
    document = CadDocument()
    sketch = rectangle_sketch(document)
    extrude = document.add_feature("extrude", "Boss", sketch_id=sketch.id, distance=5, symmetric=False)

    assert geometry.volume(geometry.build_feature(extrude, document)) == pytest.approx(1000)


def test_open_sketch_cannot_make_solid() -> None:
    geometry = GeometryService()
    if not geometry.available:
        pytest.skip("Open CASCADE is not installed")
    document = CadDocument()
    sketch = document.add_feature(
        "sketch",
        "Open",
        plane="XY",
        entities=[
            {"type": "line", "start": [0, 0], "end": [10, 0]},
            {"type": "line", "start": [10, 0], "end": [10, 10]},
            {"type": "line", "start": [10, 10], "end": [0, 10]},
        ],
    )
    extrude = document.add_feature("extrude", "Bad", sketch_id=sketch.id, distance=5)

    with pytest.raises(InvalidProfile, match="open"):
        geometry.build_feature(extrude, document)


def test_step_export_and_import_preserves_volume(tmp_path) -> None:
    geometry = GeometryService()
    if not geometry.available:
        pytest.skip("Open CASCADE is not installed")
    document = CadDocument()
    document.add_feature("box", "Box", length=10, width=20, height=30)
    destination = tmp_path / "box.step"

    geometry.export(document, destination)
    imported = geometry.import_step(destination)

    assert destination.stat().st_size > 0
    assert geometry.volume(imported) == pytest.approx(6000)


def test_circle_sketch_extrudes_to_cylinder() -> None:
    geometry = GeometryService()
    if not geometry.available:
        pytest.skip("Open CASCADE is not installed")
    document = CadDocument()
    sketch = document.add_feature(
        "sketch",
        "Circle",
        plane="XY",
        entities=[{"type": "circle", "center": [0, 0], "radius": 10}],
    )
    extrude = document.add_feature("extrude", "Cylinder", sketch_id=sketch.id, distance=5)

    assert geometry.volume(geometry.build_feature(extrude, document)) == pytest.approx(1570.79632679)


def test_stl_export_and_import_returns_mesh_shape(tmp_path) -> None:
    geometry = GeometryService()
    if not geometry.available:
        pytest.skip("Open CASCADE is not installed")
    document = CadDocument()
    document.add_feature("box", "Box", length=10, width=10, height=10)
    destination = tmp_path / "box.stl"

    geometry.export(document, destination)
    imported = geometry.import_stl(destination)

    assert destination.stat().st_size > 0
    assert not imported.IsNull()
    assert geometry.tessellate(imported)
