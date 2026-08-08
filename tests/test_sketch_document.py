from tardcad.core.document import CadDocument


def test_sketch_entities_round_trip(tmp_path) -> None:
    document = CadDocument("Profile")
    document.add_feature(
        "sketch",
        "Sketch1",
        plane="XY",
        entities=[
            {"type": "line", "start": [0.0, 0.0], "end": [25.0, 0.0], "construction": False},
            {"type": "line", "start": [25.0, 0.0], "end": [25.0, 15.0], "construction": False},
        ],
        constraint_status="Under defined",
    )
    path = tmp_path / "profile.tprt"

    document.save(path)
    loaded = CadDocument.load(path)

    assert loaded.features[0].parameters["entities"][1]["end"] == [25.0, 15.0]
