<p align="center">
  <a href="https://www.kestra.io">
    <img src="https://kestra.io/banner.png"  alt="Kestra workflow orchestrator" />
  </a>
</p>

<h1 align="center" style="border-bottom: none">
    Event-Driven Declarative Orchestrator
</h1>

<div align="center">
 <a href="https://github.com/kestra-io/kestra/releases"><img src="https://img.shields.io/github/tag-pre/kestra-io/kestra.svg?color=blueviolet" alt="Last Version" /></a>
  <a href="https://github.com/kestra-io/kestra/blob/develop/LICENSE"><img src="https://img.shields.io/github/license/kestra-io/kestra?color=blueviolet" alt="License" /></a>
  <a href="https://github.com/kestra-io/kestra/stargazers"><img src="https://img.shields.io/github/stars/kestra-io/kestra?color=blueviolet&logo=github" alt="Github star" /></a> <br>
<a href="https://kestra.io"><img src="https://img.shields.io/badge/Website-kestra.io-192A4E?color=blueviolet" alt="Kestra infinitely scalable orchestration and scheduling platform"></a>
<a href="https://kestra.io/slack"><img src="https://img.shields.io/badge/Slack-Join%20Community-blueviolet?logo=slack" alt="Slack"></a>
</div>

<br />

<p align="center">
  <a href="https://twitter.com/kestra_io" style="margin: 0 10px;">
        <img src="https://kestra.io/twitter.svg" alt="twitter" width="35" height="25" /></a>
  <a href="https://www.linkedin.com/company/kestra/" style="margin: 0 10px;">
        <img src="https://kestra.io/linkedin.svg" alt="linkedin" width="35" height="25" /></a>
  <a href="https://www.youtube.com/@kestra-io" style="margin: 0 10px;">
        <img src="https://kestra.io/youtube.svg" alt="youtube" width="35" height="25" /></a>
</p>

<br />
<p align="center">
    <a href="https://go.kestra.io/video/product-overview" target="_blank">
        <img src="https://kestra.io/startvideo.png" alt="Get started in 3 minutes with Kestra" width="640px" />
    </a>
</p>
<p align="center" style="color:grey;"><i>Get started with Kestra in 3 minutes.</i></p>

# Kestra Timefold Plugin

## Why

- **What user problem does this solve?** Teams need to run AI-powered optimization — employee scheduling, field service routing, pick-up and delivery routing — from within their orchestrated workflows instead of relying on one-off scripts or manual API calls.
- **Why would a team adopt this plugin in a workflow?** It keeps Timefold solving steps in the same Kestra flow as upstream data preparation, approvals, retries, notifications, and downstream systems — without custom glue code.
- **What operational/business outcome does it enable?** It reduces manual handoffs and fragmented tooling while improving reliability, traceability, and delivery speed for processes that depend on optimization results.

## What

- Provides plugin components under `io.kestra.plugin.timefold`.
- `Solve` — submits a `modelInput` dataset to a Timefold Platform model and returns a `jobId`. Optionally polls until solving completes and returns the full `modelOutput`.
- `GetDataset` — retrieves the current state of a previously submitted job by `jobId`.

## Supported models

| Model | Kestra value | User guide |
|---|---|---|
| Employee Scheduling | `EMPLOYEE_SCHEDULING` | [docs.timefold.ai/employee-scheduling](https://docs.timefold.ai/employee-scheduling/latest) |
| Field Service Routing | `FIELD_SERVICE_ROUTING` | [docs.timefold.ai/field-service-routing](https://docs.timefold.ai/field-service-routing/latest) |
| Pick-up and Delivery Routing | `PICKUP_DELIVERY_ROUTING` | [docs.timefold.ai/pickup-delivery-routing](https://docs.timefold.ai/pickup-delivery-routing/latest) |

## Documentation
* Full documentation can be found under: [kestra.io/docs](https://kestra.io/docs)
* Plugin developer guide: [kestra.io/docs/plugin-developer-guide](https://kestra.io/docs/plugin-developer-guide/)
* Timefold Platform documentation: [docs.timefold.ai](https://docs.timefold.ai/)


## License
Apache 2.0 © [Kestra Technologies](https://kestra.io)


## Stay up to date

We release new versions every month. Give the [main repository](https://github.com/kestra-io/kestra) a star to stay up to date with the latest releases and get notified about future updates.

![Star the repo](https://kestra.io/star.gif)
