# ds-rs design documents (vendored)

The design documents from the Rust `shapeshifter` project, copied verbatim from
`ds-rs` at commit `996aa7acb9c0820cd7617d62cc799ca37160e0f1` on 2026-08-20. They are here as
reference material for the port ([../../design/07-engine-port-plan.md](../../design/07-engine-port-plan.md),
decision D33), not as this module's own design record — that lives in
[../../design/](../../design/) alongside the matching layer's.

**Read them as history, not as specification.** Most are *plans*, written before the work
they describe; several were superseded by later ones, and a few describe things that were
never built or were built differently. Where a document and the Rust source disagree, the
source wins — it is the source, and its 51 green fixture sets, that the port is faithful to.
Internal `file://` links point at the authors' machines and are dead.

Nothing here has been edited. That is deliberate: an annotated copy invites the question of
which annotations are ours, and the answer should always be "none".

## What matters for the port

The engine as it actually ended up, in rough order of usefulness:

- **[template_engine_architecture.md](template_engine_architecture.md)** — the best single
  entry point. The XSLT-inspired template model, the source file map, and the data flow.
- **[xslt_template_simplification.md](xslt_template_simplification.md)** — why a template body
  is a flat instruction sequence writing to a stream, which is the shape `body.rs` has.
- **[ds3_design_document.md](ds3_design_document.md)** — DS3 itself: the semantics the
  `legacy` fixtures encode and that `migration.rs` has to reproduce.
- **[byte_level_engine_architecture.md](byte_level_engine_architecture.md)** and
  **[byte_level_performance_and_design.md](byte_level_performance_and_design.md)** — the
  "everything is bytes" principle, which our matching layer already shares (D13).
- **[graph_model_simplification.md](graph_model_simplification.md)** — where UUID-based
  capture bindings and `RefExpression` came from, and what the text-based `$0` syntax it
  replaced was for.
- **[combinator_design_plan.md](combinator_design_plan.md)** and
  **[implementation_plan.md](implementation_plan.md)** — the progressive `MatchStep`
  combinators (port phase 5).
- **[foreach_design.md](foreach_design.md)**, **[recursive_templates.md](recursive_templates.md)**,
  **[ordered_children_refactor.md](ordered_children_refactor.md)** — multi-valued stores,
  `apply-templates` recursion, and child execution order.
- **[encoding_completion_plan.md](encoding_completion_plan.md)** — the encoding work (phase 6).
- **[binary_parsing_and_typed_variables.md](binary_parsing_and_typed_variables.md)** — Avro,
  Parquet and Protobuf, which D33 defers. Read it when that decision is revisited.
- **[ausearch_transformation_plan.md](ausearch_transformation_plan.md)** — one fixture's
  story end to end, which is a useful way in if the abstract documents aren't landing.

## What does not apply

- **[data_centric_ui_redesign.md](data_centric_ui_redesign.md)**,
  **[node_editor_ui_refactor.md](node_editor_ui_refactor.md)**,
  **[node_editor_ui_implementation_plan.md](node_editor_ui_implementation_plan.md)**,
  **[semantic_bindings_remaining_phases.md](semantic_bindings_remaining_phases.md)** — the
  `ds-rs` node editor, which is not being ported. Kept because they document the authoring
  model the config format was shaped by, and Stroom will eventually need an editor of its own.
- **[template_engine_full_stack_migration.md](template_engine_full_stack_migration.md)**,
  **[engine_modernisation_roadmap.md](engine_modernisation_roadmap.md)**,
  **[byte_level_refactor_steps.md](byte_level_refactor_steps.md)**,
  **[byte_level_plan_review.md](byte_level_plan_review.md)** — completed migration plans.
  History of how the crate reached its current shape.
- **[future_optimisations.md](future_optimisations.md)** — deferred Rust-side performance
  work. Ideas may transfer; none of the measurements do, and per D33 no performance work
  happens during the port.
- **[full/](full)** — the product-level pitch (`full-design.md`) and its diagrams, plus
  `logo.svg` and `shapeshifter.svg`.
