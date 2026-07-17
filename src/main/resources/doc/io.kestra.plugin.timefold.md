This plugin integrates Kestra with the [Timefold Platform](https://docs.timefold.ai/) to solve AI-powered optimization problems such as employee scheduling and field service routing.

## Prerequisites

Each model on the Timefold Platform requires its own API key. You can generate one from the [Timefold Platform](https://app.timefold.ai/). Store it as a Kestra secret (e.g. `TIMEFOLD_API_KEY`) and reference it with `{{ secret('TIMEFOLD_API_KEY') }}` in your workflow.

## Supported models

| Model | Kestra value | User guide |
|---|---|---|
| Employee Scheduling | `EMPLOYEE_SCHEDULING` | [docs.timefold.ai/employee-scheduling](https://docs.timefold.ai/employee-scheduling/latest) |
| Field Service Routing | `FIELD_SERVICE_ROUTING` | [docs.timefold.ai/field-service-routing](https://docs.timefold.ai/field-service-routing/latest) |
| Pick-up and Delivery Routing | `PICKUP_DELIVERY_ROUTING` | [docs.timefold.ai/pickup-delivery-routing](https://docs.timefold.ai/pickup-delivery-routing/latest) |

Consult the relevant user guide for the exact `modelInput` schema expected by each model — field names, required properties, and value types differ between them.

## Tasks

- `Solve`: submits a `modelInput` dataset to a Timefold model and returns a `jobId`. Optionally polls until solving completes and returns the full `modelOutput`.
- `GetDataset`: retrieves the current state of a previously submitted job by `jobId`. Use this after `Solve` (with `wait: false`) to fetch the solution once solving has completed.

## Large datasets and long solve times

When `solveDuration` is omitted, the Timefold Platform uses its built-in [diminishing-returns termination](https://docs.timefold.ai/timefold-platform/latest/how-tos/configuration-parameters-and-profiles#_run_termination_settings) to decide how long to run. It stops when further solving is unlikely to meaningfully improve the solution.

For large datasets where solving may take a significant amount of time, set `wait` to `false` (or omit it, `false` is the default) in the `Solve` task.
This returns the `jobId` immediately without blocking the flow. Use a subsequent `GetDataset` task to retrieve the solution once solving is complete, polling on your own schedule.
