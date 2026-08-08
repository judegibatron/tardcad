# TardCAD 0.2 MVP user guide

## Parts

Create a `.tprt` document, then use primitives or create a sketch on XY, XZ, or
YZ. Sketch tools include line, circle, and regular polygon. Select entities and
apply horizontal, vertical, coincident, equal, fixed, or driving dimensional
relations. A closed profile can be extruded or revolved into an Open CASCADE
solid.

Solid features are history based. Select a source feature before choosing
Fillet, Chamfer, Linear Pattern, or Circular Pattern. Boolean combines two
features using Union, Subtract, or Intersect. Editing a numeric property in the
PropertyManager recomputes every downstream feature.

Red FeatureManager text indicates a failed recompute; hover it for the kernel
error. Undo and redo operate on the complete feature graph.

## Assemblies

Create a `.tasm` document and use Insert Component to link saved `.tprt` files.
The first inserted component is fixed. Mate supports coincident, distance,
concentric, and parallel relationships between component origins/axes. Linked
parts are recomputed from their source documents whenever the assembly opens or
refreshes.

## Exchange and recovery

File > Import accepts STEP and STL. File > Export writes STEP BREP data or a
kernel-generated STL mesh. Native saves are atomic. While a document is dirty,
TardCAD maintains a recovery copy and offers it after an interrupted session.

## Units

Use the lower status bar to select mm, cm, m, or inches. The selected unit is
saved with the document. Displayed values and edits convert automatically while
the geometry kernel retains canonical millimeter dimensions.
