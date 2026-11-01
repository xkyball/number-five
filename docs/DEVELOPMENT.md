# Development and operations

This is the public Number Five site. The separate private No5 Project Cockpit is unchanged.

## Content

`content/projects.ts` holds paired German/English module content, timeline, credits and source-note links. `content/media.json` holds paired captions and provenance; original images are in `public/images/`. `content/downloads.json` lists actual source archives with sizes and SHA-256. Avoid unsupported claims or substituting simulated screenshots for physical proof.

## Database and contact

The Sites manifest binds D1 as `DB`. Schema lives in `db/schema.ts`; generated SQL, snapshots and journal are in `drizzle/`. Do not change applied migrations. After schema changes, generate a new migration with `npm run db:generate`.

Runtime configuration (server-only, never committed):

- `CONTACT_SECRET`: random HMAC key used for signed, expiring submission tokens and keyed abuse-control hashes.
- `OWNER_EMAIL`: configured owner email from the selected Sites account. Identity is established by Sites, not by a browser-supplied form field. Requests with no configured owner fail closed.

For local development only, `.dev.vars` may use `OWNER_EMAIL="seedy@sites.test"` and a separate test key. The included portable Sites development auth supplies its documented local mock identity. It is not part of production authentication. Do not set the local identity as hosted owner.

Build once, then apply each pending migration exactly once to the local preview:

```sh
node --import ./scripts/sites-env.mjs ./node_modules/wrangler/bin/wrangler.js d1 execute DB --local --config dist/server/wrangler.json --persist-to .wrangler/state --file drizzle/0000_broken_crystal.sql
```

`GET /api/contact` issues a signed two-hour token. `POST /api/contact` requires same-origin JSON, bounded fields/body, a clear honeypot, a token at least1.5seconds old, and database-backed rate limits. Successful storage returns a reference; token-based idempotency avoids duplicating a retry. A different payload cannot reuse a token.

`/admin/inbox` uses dispatch-owned ChatGPT authentication plus a server-side owner check. `/api/inbox` requires the same owner; writes additionally require the same origin. Messages are private/no-store, escaped as text and never logged by application code. Owner actions read, mark and delete. There is no mail delivery, background notification or contact export. Check the inbox manually.

## Validation

`npm run check` and `npm run test:core` check types and meaningful token, authorization, origin and body-limit cases. The headless browser script exercises34 bilingual pages, images, mobile layout/menu, language preservation, contact persistence and inbox state changes with synthetic data that it removes. Its local Playwright resolution is environment-specific; use an installed Playwright and a genuinely headless browser on another machine. No visible GUI fallback.

`outputs/` contains ignored local QA captures and logs. The public QA report identifies performed checks and limits.

## Hosting and reuse

This checkout's `.openai/hosting.json` identifies the actual Sites project. Preserve it for updates to this site. A fork deployed by another owner must register its own Sites identity and secrets. Use the installed Sites workflow to push the exact source, build/package, save and deploy. Database migrations are applied by Sites. Keep credentials, `.dev.vars`, runtime caches and publication archives out of Git.
