# Research playbook

Read this reference before researching a feature idea on the internet.

## Research question set

Build queries around the decision, not only the proposed UI label:

1. What user job or recurring failure is the feature trying to address?
2. Which domain concepts are broader, narrower, or adjacent to the proposed concept?
3. How do established products model and expose the concept today?
4. What trade-offs or complaints appear around those models?
5. Which Android, Google Play, accessibility, privacy, or lifecycle constraints change the product
   behavior?
6. What would falsify the need for the feature or favor the status quo?

For exercise-domain changes, search vocabulary used by coaches and exercise-science sources as
well as app vocabulary. A competitor label is not automatically a sound domain model.

## Source hierarchy

Prefer, in order:

1. The current repository, its schemas, tests, `ARCHITECTURE.md`, design system, and prior product
   decisions for existing behavior.
2. Official Android and Google Play documentation for platform behavior, quality, accessibility,
   permissions, privacy, background work, and policy.
3. Primary standards, position statements, and peer-reviewed research for fitness or health
   claims. State when evidence is indirect or contested; do not turn product discovery into medical
   advice.
4. First-party competitor help centers, release notes, product pages, and store listings for what a
   product claims to support.
5. App reviews, forums, communities, and support discussions for hypotheses about pain points and
   vocabulary. Report these as anecdotal signals with visible sampling limitations.
6. Reputable secondary analysis for synthesis. Use uncited SEO pages only as leads to better
   sources.

Prefer sources updated for the currently supported Android versions. If a fact may have changed,
verify it in a current authoritative source rather than relying on memory.

## Evidence ledger

Keep enough structure to prevent citation laundering:

| Claim | Type | Source and date | What it supports | Limitation |
|---|---|---|---|---|
| … | Repository fact / External fact / Inference / Assumption | Direct URL or file | Narrow claim | Age, sample, marketing claim, missing data |

Use direct links to the page supporting each external claim. Do not cite a search-results page.
Avoid long quotes; synthesize the evidence in the product's language.

For competitor research, distinguish:

- **documented capability** — confirmed in a first-party help page or release note;
- **observed complaint or workaround** — anecdotal user signal;
- **effectiveness** — requires behavioral or research evidence and must not be inferred from mere
  availability.

## Minimum useful coverage

Research until additional results stop changing the options or concerns, not until a fixed source
count is reached. A sound pass normally includes:

- more than one product/model, including the manual or “do nothing” alternative;
- at least one authoritative domain or platform source when the decision depends on it;
- one search for negative evidence, failure modes, or user friction;
- currentness checks for policies, APIs, and platform recommendations.

If reliable evidence is absent, say so and lower confidence. Do not manufacture market prevalence,
conversion estimates, retention impact, sample sizes, or target thresholds.

## Methodological anchors

These sources informed this project's workflow; consult them when the method itself is in doubt:

- [gabros20/product-skill](https://github.com/gabros20/product-skill) — broad discovery, mobile,
  risks, and PRD routing.
- [TerminalSkills product-discovery](https://github.com/TerminalSkills/skills/tree/main/skills/product-discovery)
  — opportunity/solution/assumption separation and cheap validation.
- [Product Talk: Opportunity Solution Trees](https://www.producttalk.org/adopting-opportunity-solution-trees/)
  — mapping outcomes, opportunities, solutions, assumptions, and experiments.
- [Android app quality](https://developer.android.com/quality) and
  [core app quality guidelines](https://developer.android.com/docs/quality-guidelines/core-app-quality)
  — current Android product-quality constraints.
- [Google Play Data safety](https://support.google.com/googleplay/android-developer/answer/10787469)
  — disclosure implications when a feature collects or shares data.
