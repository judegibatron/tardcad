import os

import pytest

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtWidgets import QApplication

from tardcad.ui.sketcher import SketchCanvas


def test_driving_line_dimension_changes_geometry() -> None:
    app = QApplication.instance() or QApplication([])
    canvas = SketchCanvas(
        [{"type": "line", "start": [0.0, 0.0], "end": [10.0, 0.0], "construction": False}]
    )
    canvas.selected_indices = [0]

    assert canvas.apply_dimension(25.0)
    assert canvas.entities[0]["end"] == pytest.approx([25.0, 0.0])
    assert canvas.relations[-1]["type"] == "length"
    canvas.close()
