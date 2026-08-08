STYLE_SHEET = """
QMainWindow, QDialog { background: #20242b; color: #e8edf2; }
QWidget { color: #e8edf2; font-family: "Segoe UI", "Inter", sans-serif; font-size: 10pt; }
QMenuBar, QMenu, QToolBar, QTabWidget::pane { background: #282d35; }
QMenuBar::item:selected, QMenu::item:selected { background: #1769aa; }
QToolBar { border: 0; spacing: 4px; padding: 5px; }
QToolButton { border: 1px solid transparent; border-radius: 4px; padding: 6px; min-width: 45px; }
QToolButton:hover { background: #3a414c; border-color: #566170; }
QToolButton:pressed { background: #1769aa; }
QDockWidget { color: #cfd7df; font-weight: 600; }
QDockWidget::title { background: #282d35; padding: 7px; }
QTreeWidget, QTableWidget, QPlainTextEdit, QLineEdit, QDoubleSpinBox {
  background: #1b1f25; border: 1px solid #353c46; selection-background-color: #1769aa;
}
QTreeWidget::item { padding: 4px; }
QHeaderView::section { background: #282d35; border: 0; padding: 5px; }
QStatusBar { background: #1769aa; }
QPushButton { background: #303741; border: 1px solid #46505d; border-radius: 4px; padding: 6px 12px; }
QPushButton:hover { background: #3b4653; }
QTabBar::tab { background: #282d35; padding: 8px 16px; }
QTabBar::tab:selected { background: #1769aa; }
QLabel#startTitle { font-size: 28pt; font-weight: 300; color: #f7f9fb; }
QLabel#sectionTitle { font-size: 13pt; font-weight: 600; }
"""
