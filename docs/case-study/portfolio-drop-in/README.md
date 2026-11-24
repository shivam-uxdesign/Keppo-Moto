# Putting the case study on the portfolio

The portfolio (shivam-uxdesign/Portfolio, Vite + React on Vercel) already serves one plain-HTML case study from `public/` (`rupyy-case-study.html`). This one goes the same way.

1. Copy `public/keppo-moto/` from here into the portfolio's `public/` folder.
2. Push. It lives at `/keppo-moto/` (full) and `/keppo-moto/tldr.html` (TL;DR); the PDF at `/keppo-moto/keppo-moto-tldr.pdf`.
3. Link to `/keppo-moto/` with the trailing slash, so the images load.

Checked locally: no broken images or links, all feature tabs work, TL;DR and back.

## A card on Home (your call)
Home shows `PROJECTS.slice(0, 2)`, so a new card only appears if it's one of the first two. An entry for `PROJECTS` in `src/app/pages/Home.tsx`:

```tsx
{
  title: 'Designed and shipped a motorcycle app with AI as the engineering team',
  descriptor: 'Keppo Moto: from idea to 477 commits and tested Android builds in two months, one designer.',
  number: '04', bg: '#0A0A0B', link: '/keppo-moto/', external: true,
  thumbnail: <img src="/keppo-moto/img/og.jpg" alt="" className="absolute inset-0 w-full h-full object-cover" />,
},
```
`external: true` makes it a plain link (opens in a new tab), which a static page needs; a router `Link` would not load it.

Before publishing: fill `[YOUR EMAIL]` and `[YOUR PORTFOLIO LINK]`, and swap the dashed placeholders for the phone captures (`capture.sh`).
