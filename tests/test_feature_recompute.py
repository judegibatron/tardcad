import pytest

from tardcad.core.document import CadDocument
from tardcad.core.geometry import GeometryService


def test_history_chain_recomputes_and_hides_consumed_source() -> None:
    geometry = GeometryService()
    if not geometry.available:
        pytest.skip("Open CASCADE unavailable")
    document = CadDocument()
    box = document.add_feature("box", "Base", length=20, width=20, height=10)
    fillet = document.add_feature("fillet", "Fillet", source_id=box.id, radius=1)

    shapes, errors = geometry.recompute(document)
    visible = geometry.build_document(document)

    assert not errors
    assert geometry.volume(shapes[fillet.id]) < 4000
    assert len(visible) == 1


def test_linear_pattern_creates_real_compound() -> None:
    geometry = GeometryService()
    if not geometry.available:
        pytest.skip("Open CASCADE unavailable")
    document = CadDocument()
    box = document.add_feature("box", "Seed", length=5, width=5, height=5)
    pattern = document.add_feature("linear_pattern", "Pattern", source_id=box.id, count=4, spacing=10, axis="X")

    shape = geometry.recompute(document)[0][pattern.id]

    assert geometry.volume(shape) == pytest.approx(500)


def test_boolean_cut_changes_volume() -> None:
    geometry = GeometryService()
    if not geometry.available:
        pytest.skip("Open CASCADE unavailable")
    document = CadDocument()
    outer = document.add_feature("box", "Outer", length=20, width=20, height=20)
    inner = document.add_feature("box", "Inner", length=10, width=10, height=20)
    cut = document.add_feature("boolean", "Cut", first_id=outer.id, second_id=inner.id, operation="cut")

    shape = geometry.recompute(document)[0][cut.id]

    assert geometry.volume(shape) == pytest.approx(6000)


def test_kernel_tessellation_returns_triangles() -> None:
    geometry = GeometryService()
    if not geometry.available:
        pytest.skip("Open CASCADE unavailable")
    document = CadDocument()
    box = document.add_feature("box", "Box", length=10, width=20, height=30)

    triangles = geometry.tessellate(geometry.build_feature(box, document))

    assert len(triangles) == 12
    assert all(len(triangle) == 3 for triangle in triangles)


def test_revolve_builds_solid_about_in_plane_axis() -> None:
    geometry = GeometryService()
    if not geometry.available:
        pytest.skip("Open CASCADE unavailable")
    document = CadDocument()
    sketch = document.add_feature(
        "sketch",
        "Section",
        plane="XY",
        entities=[
            {"type": "line", "start": [0, 5], "end": [10, 5]},
            {"type": "line", "start": [10, 5], "end": [10, 10]},
            {"type": "line", "start": [10, 10], "end": [0, 10]},
            {"type": "line", "start": [0, 10], "end": [0, 5]},
        ],
    )
    revolve = document.add_feature("revolve", "Revolve", sketch_id=sketch.id, angle=360, axis="X")

    shape = geometry.recompute(document)[0][revolve.id]

    assert geometry.volume(shape) == pytest.approx(750 * 3.141592653589793)
