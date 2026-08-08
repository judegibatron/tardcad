from tardcad.core.document import CadDocument
from tardcad.core.scripting import ScriptSession


def test_script_console_can_modify_document() -> None:
    document = CadDocument()
    changes = []
    session = ScriptSession(document, lambda: changes.append(True))

    output = session.execute("doc.add_feature('box', 'Cube', length=10, width=10, height=10).name")

    assert output.strip() == "'Cube'"
    assert document.features[0].name == "Cube"
    assert changes


def test_script_errors_are_returned() -> None:
    session = ScriptSession(CadDocument(), lambda: None)
    assert "ZeroDivisionError" in session.execute("1 / 0")
