# Evaluation Material

This directory contains the preliminary evaluation material associated with the
AIGen tool paper.

## Files

- `lists_of_projects.csv`: list of the eight open-source ML projects used in the
  preliminary evaluation.
- `Quality_AI.csv`: manual quality assessment of fields generated for AI
  packages.
- `Quality_Datasets.csv`: manual quality assessment of fields generated for
  dataset packages.

## Evaluation Scope

The evaluation covers projects from GitHub and Kaggle. For the evaluated
projects, AIGen combines:

- structured metadata extracted from MLflow runs and registered models;
- dataset metadata extracted from Kaggle;
- source-code and documentation analysis through the configured LLM backend.

The CSV files report field-level quality ratings, not end-to-end compliance
certification. They are intended to support the preliminary results discussed in
the paper and to make the scoring data inspectable.

## Rating Legend

- `P` - Perfect: the generated field is correct and complete.
- `G` - Good: the generated field is mostly correct, with minor omissions or
  wording issues.
- `L` - Lackluster: the generated field is partially useful but incomplete,
  vague, or weakly grounded.
- `B` - Bad: the generated field is incorrect or not useful.
- `M` - Meta-comment: the generated field contains process-oriented text,
  self-reference, or commentary instead of the requested AIBOM content.

## Field Groups

`Quality_AI.csv` evaluates:

- hyperparameters and metrics;
- model limitations;
- training information;
- application information;
- model type;
- domain;
- explainability;
- license.

`Quality_Datasets.csv` evaluates:

- dataset type;
- collection process;
- intended use;
- preprocessing;
- originator;
- download location;
- license.
