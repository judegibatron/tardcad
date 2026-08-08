from __future__ import annotations

import contextlib
import io
import traceback
from collections.abc import Callable

from tardcad.core.document import CadDocument


class ScriptSession:
    def __init__(self, document: CadDocument, on_change: Callable[[], None]) -> None:
        self.namespace = {"doc": document, "Feature": __import__("tardcad.core.document", fromlist=["Feature"]).Feature}
        self.on_change = on_change

    def execute(self, source: str) -> str:
        output = io.StringIO()
        try:
            with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
                try:
                    result = eval(compile(source, "<TardCAD console>", "eval"), self.namespace)
                    if result is not None:
                        print(repr(result))
                except SyntaxError:
                    exec(compile(source, "<TardCAD console>", "exec"), self.namespace)
            self.on_change()
        except Exception:
            traceback.print_exc(file=output)
        return output.getvalue()
