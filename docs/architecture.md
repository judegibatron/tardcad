# Architecture

TardCAD separates document intent from geometry realization.

```text
Qt desktop shell
  |-- Commands and workbenches
  |-- Document/feature graph
  |-- Viewport scene adapter
  |-- Python scripting facade
  `-- Geometry service
        `-- Direct OCP bindings -> Open CASCADE BREP
```

The document stores stable feature IDs, operation parameters, ordering, and
assembly metadata. Geometry is derived and can be recomputed or replaced. This
keeps saved projects inspectable and avoids binding the file format to Python
object serialization.

The viewport displays meshes tessellated directly from Open CASCADE BREP shapes.
The separate geometry service creates solids, computes volume, performs feature
recompute, and owns STEP/STL exchange; the UI never acts as geometric authority.

## Project format

`.tprt` part files and `.tasm` assembly files are UTF-8 JSON containing a schema version, document identity,
units, and ordered feature records. Future versions will package JSON, BREP
caches, thumbnails, and linked-component manifests into a ZIP container.
