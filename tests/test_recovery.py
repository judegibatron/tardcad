from tardcad.core.document import CadDocument
from tardcad.core.recovery import RecoveryManager


def test_recovery_round_trip(tmp_path) -> None:
    manager = RecoveryManager(tmp_path / "recovery")
    document = CadDocument("Unsaved")
    document.add_feature("box", "Box", length=1, width=2, height=3)

    path = manager.write(document)
    recovered = manager.load(path)

    assert recovered.name == "Unsaved"
    assert recovered.features[0].parameters["height"] == 3
    assert recovered.dirty
    manager.clear(document)
    assert not path.exists()
