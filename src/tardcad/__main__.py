from __future__ import annotations

import sys

from PySide6.QtWidgets import QApplication

from tardcad.ui.main_window import MainWindow


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName("TardCAD")
    app.setOrganizationName("TardCAD")
    app.setApplicationVersion("0.2.0")
    window = MainWindow()
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
