import pytest

from tardcad.core.document import CadDocument
from tardcad.core.units import from_mm, to_mm


def test_display_unit_conversion_preserves_physical_size() -> None:
    assert from_mm(25.4, "in") == pytest.approx(1.0)
    assert to_mm(1.0, "in") == pytest.approx(25.4)


def test_document_units_round_trip(tmp_path) -> None:
    document = CadDocument()
    document.set_units("in")
    document.add_feature("box", "One inch", length=25.4, width=25.4, height=25.4)
    path = tmp_path / "inch-part.tprt"

    document.save(path)
    loaded = CadDocument.load(path)

    assert loaded.units == "in"
    assert loaded.features[0].parameters["length"] == 25.4
