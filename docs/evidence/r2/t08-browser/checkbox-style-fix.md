# T08 checkbox correction

Before: `c31-direct-request.png` shows contract checkboxes inheriting the global 44px text-input minimum and full-row layout.

Change: both `明确折扣` and `包含条件性费用` now use frozen E `checked-label` (flex row, 10px gap, 18px checkbox, existing emerald accent). A contract-only selector resets the global minimum height to 18px and padding to 0; no master CSS or tokens changed. Payment gating is a select, not a checkbox.

Verification: ContractCard focused suite 14 passed, including actual application + frozen master + contract CSS loaded together and computed display:flex / width:18px / height:18px / min-height:18px / padding:0px. TypeScript and Vite build passed. This is style evidence, not a replacement for the post-SPA real browser screenshot.

Only the SPA needs updating. Build with `output/build-t08-review.ps1` to preserve local review OIDC settings before copying dist. No backend, database, or API changes.
