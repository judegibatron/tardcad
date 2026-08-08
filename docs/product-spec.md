# TardCAD product definition

## Product promise

TardCAD is a local-first, general-purpose mechanical CAD system for professional
engineers. It combines a familiar SolidWorks-style feature workflow with an
open Open CASCADE geometry stack and a scriptable Python API.

## Version 1 boundaries

Included:

- Parametric, history-based solids plus direct-editing foundations
- Fully constrained 2D sketch workflow
- Extrude, revolve, fillet, chamfer, linear/circular patterns, and booleans
- STEP import/export and STL import/export
- Multi-part assemblies with traditional mates
- Local project files, undo/redo, recovery, and Python scripting
- Windows and mainstream Linux desktop distributions
- Models containing hundreds of assembly components on workstation GPUs

Explicitly deferred:

- Simulation, CAM, G-code, cloud storage, and live collaboration
- Full UI customization, macros, themes, and multi-monitor workspace management

## MVP acceptance criteria

The MVP must behave as a credible desktop CAD application. It must
open to a coherent modeling workspace, create and edit parametric primitives,
present a feature tree and property editor, provide standard camera controls,
persist local documents, and expose a Python console. Operations unavailable
without the optional geometry engine must fail clearly rather than silently.

The sketcher currently supports connected lines, circles, regular polygons,
grid/endpoint snapping, construction geometry, undo, plane selection, reopening,
and persistence. Horizontal, vertical, coincident, and equal relations can be
applied to selected entities. A complete dimensional constraint solver remains
a following milestone.
