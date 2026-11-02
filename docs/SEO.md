# Search and planning · 2 November 2026

The public site has 44 paired German/English pages. Its first search audience is boat owners planning upgrades and people offering fabrication or installation. Developers can follow those paths into interfaces and source packages.

## Content contract

- `content/search.json` registers every public route, its unique paired search title and description, and project planning questions. These questions are editorial guidance, never claimed to be received feedback.
- `content/planning.json` supplies `/refit` and `/fabrication` in both languages. The source packages, measured results and media provenance remain the factual basis. Price information identifies its source and date; the €75 display is not a complete installed-system price.
- `lib/search.ts` creates canonical/language links and structured WebSite, Person, WebPage, BreadcrumbList, Article and visible VideoObject descriptions. Never invent a business, service offer, review, certification or device compatibility to fill structured fields.
- `scripts/generate-sitemap.mjs` generates URLs from the same route registry. Update the editorial date only for an actual content revision. Existing URLs and language-pair fragments remain supported.
- The native clips keep their original speed, captions and data-source distinctions. Their visible descriptions identify the demonstrated behaviour. No video-ranking or performance guarantee follows from metadata.

The current verification meta tags were supplied by Google Search Console and Bing Webmaster Tools for this website in Christoph’s signed-in account. They are public ownership-verification markers, not private login credentials. Keep them when updating the site. Search discovery and model-training preferences are separate; the existing robots policy allows public pages while excluding `/admin/` and `/api/`. Authentication remains the protection for private data.

## Research basis

Search topics were derived from public questions and actual refit reports, not private prompts or measured keyword volumes:

- [Keeping instruments during an electronics upgrade](https://forums.ybw.com/threads/upgrading-electronics.594341/)
- [Connecting older instruments to NMEA 2000](https://www.segeln-forum.de/thread/92145-vorhandene-alte-bordelektronik-an-nmea2000/)
- [Tablet readability and chartplotter choices](https://www.segeln-forum.de/thread/64911-e-ink-tablet-mit-navigationssoftware-als-plotter/)
- [Music and suitable equipment aboard](https://www.segeln-forum.de/thread/93573-guter-sound-an-bord-welche-stereoanlage/)
- [Owner account of an electronics refit and installer selection](https://myoloh.com/olohs-electronics-refit/)

Technical guidance: [Google AI-search guidance](https://developers.google.com/search/docs/fundamentals/ai-optimization-guide), [Google video guidance](https://developers.google.com/search/docs/appearance/video), [OpenAI crawlers](https://developers.openai.com/api/docs/bots). No special AI text file, FAQ-rich-result promise or paid SEO service is part of this release.

## Measurement

Record a baseline after publication. Review after 30, 60 and 90 days using the verified Google and Bing properties where data is available. Compare actual non-brand queries, pages, impressions and clicks; use the services’ available AI visibility reports for citations/impressions. These are not private prompt logs or a causal measure of a particular copy change.

Count relevant installation ideas, fabrication offers and technical contributions in the existing private mailbox without publishing identities. No visitor analytics or new contact fields are introduced. Small or missing samples stay unknown; do not present missing volume as zero demand.

## Checks

The headless regression covers all 44 pages, unique paired metadata, canonical/language links, matching structured video descriptions, images, playback, mobile layout, language switching, keyboard access, local contact persistence and owner-only inbox actions. A separate audit checked 1,736 internal links with no missing fragment target. Screenshots stay in ignored `outputs/`; results are never opened automatically.
