import json

import pytest

from tardcad.core.document import CadDocument, DocumentHistory


def test_feature_lifecycle_marks_document_dirty() -> None:
    document = CadDocument("Bracket")
    feature = document.add_feature("box", "Base", length=50.0, width=30.0, height=10.0)

    assert document.dirty
    assert document.feature(feature.id) is feature

    document.update_parameter(feature.id, "height", 12.0)
    assert feature.parameters["height"] == 12.0

    document.remove_feature(feature.id)
    assert document.features == []


def test_round_trip_document(tmp_path) -> None:
    path = tmp_path / "bracket.tprt"
    document = CadDocument("Bracket")
    document.add_feature("cylinder", "Boss", diameter=25.0, height=16.0)

    document.save(path)
    loaded = CadDocument.load(path)

    assert loaded.name == "Bracket"
    assert loaded.document_type == "part"
    assert loaded.features[0].parameters == {"diameter": 25.0, "height": 16.0}
    assert loaded.path == path
    assert not loaded.dirty
    assert json.loads(path.read_text())["schema_version"] == 1


def test_unknown_document_version_is_rejected(tmp_path) -> None:
    path = tmp_path / "future.tprt"
    path.write_text('{"schema_version": 99}')

    with pytest.raises(ValueError, match="Unsupported"):
        CadDocument.load(path)


def test_assembly_type_round_trip(tmp_path) -> None:
    path = tmp_path / "fixture.tasm"
    document = CadDocument("Fixture", "assembly")

    document.save(path)
    loaded = CadDocument.load(path)

    assert loaded.document_type == "assembly"


def test_undo_and_redo_restore_feature_graph() -> None:
    document = CadDocument()
    history = DocumentHistory(document)
    document.add_feature("box", "Box", length=10, width=10, height=10)

    assert history.undo()
    assert document.features == []
    assert history.redo()
    assert document.features[0].name == "Box"
