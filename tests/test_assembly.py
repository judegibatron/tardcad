import pytest

from tardcad.core.assembly import AssemblySolver
from tardcad.core.document import CadDocument
from tardcad.core.geometry import GeometryService


def test_distance_mate_solves_component_placement(tmp_path) -> None:
    part_path = tmp_path / "block.tprt"
    part = CadDocument("Block")
    part.add_feature("box", "Block", length=10, width=10, height=10)
    part.save(part_path)
    assembly = CadDocument("Pair", "assembly")
    first = assembly.add_feature("component", "Fixed", source=str(part_path), placement=[5, 0, 0, 0, 0, 0], fixed=True)
    second = assembly.add_feature("component", "Moving", source=str(part_path), placement=[0, 20, 0, 0, 0, 0], fixed=False)
    assembly.add_feature("mate", "Distance", component_a=first.id, component_b=second.id, mate_type="distance", axis="X", offset=25)

    placements, errors = AssemblySolver().solve(assembly)

    assert not errors
    assert placements[second.id].x == pytest.approx(30)


def test_linked_components_recompute_to_breps(tmp_path) -> None:
    geometry = GeometryService()
    if not geometry.available:
        pytest.skip("Open CASCADE unavailable")
    part_path = tmp_path / "block.tprt"
    part = CadDocument("Block")
    part.add_feature("box", "Block", length=10, width=10, height=10)
    part.save(part_path)
    assembly = CadDocument("Pair", "assembly")
    assembly.add_feature("component", "A", source=str(part_path), placement=[0, 0, 0, 0, 0, 0], fixed=True)
    assembly.add_feature("component", "B", source=str(part_path), placement=[20, 0, 0, 0, 0, 0], fixed=False)

    shapes = geometry.build_document(assembly)

    assert len(shapes) == 2
    assert sum(geometry.volume(shape) for shape in shapes) == pytest.approx(2000)
