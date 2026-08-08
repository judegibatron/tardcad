# TardCAD

TardCAD is an early-stage, GPL-licensed mechanical CAD desktop application for
Windows and Linux. The MVP provides a familiar feature-tree workflow,
parametric primitives, an interactive 3D viewport, local `.tprt` part and
`.tasm` assembly documents, STEP/STL interchange, and a built-in Python console.

## MVP stack

- Python 3.11+
- PySide6 / Qt 6 desktop UI
- Direct OCP bindings to the Open CASCADE BREP geometry kernel
- pytest for model-level tests

## Quick start

Windows PowerShell:

```powershell
.\scripts\install.ps1
.\.venv\Scripts\python.exe -m tardcad
```

Python 3.11 or newer is required. The installer detects either `python` or the
Windows `py -3.11` launcher.

Linux:

```bash
./scripts/install.sh
.venv/bin/python -m tardcad
```

The standard installation includes Open CASCADE. Primitive and extruded-sketch
features are recomputed as real BREP solids with kernel-derived mass properties
and STEP/STL export.

The persistent lower status bar switches document display units between `mm`,
`cm`, `m`, and `in`; values convert while canonical solid dimensions remain in
millimeters. Sketcher supports lines, circles, regular polygons, construction
geometry, and horizontal, vertical, coincident, and equal relations.

## Current scope

The 0.2 MVP is a functioning Open CASCADE solid modeler. It includes parametric
feature recompute, constrained sketch geometry, extrusion, revolve, fillet,
chamfer, patterns, booleans, tessellated BREP display, linked-part assemblies,
origin/axis mates, undo/redo, recovery, scripting, and STEP/STL exchange. It is
an MVP rather than a production-certified engineering system.

See [docs/architecture.md](docs/architecture.md) and
[docs/product-spec.md](docs/product-spec.md) for design decisions and roadmap.
The [MVP user guide](docs/user-guide.md) covers part and assembly workflows.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
