# Keep moving (was: Continuous ride)

Status: plan only. Asked for on 6 Nov: "a route from A to B with zero traffic. If there's traffic ahead, take another route; left, right, U-turn doesn't matter. Time and distance don't matter. The route needs to be clear."

## Is it realistic?

**Partly.**

| What | Realistic? |
|---|---|
| A route that keeps you moving more than the normal route, and changes when a jam appears ahead | **Yes.** Live traffic data exists for Indian cities and can be read every minute. |
| Never meeting traffic at all | **No.** Traffic data is 1–5 minutes old and averaged. Small lanes often have no data. Red lights stop you whatever the traffic. At Delhi rush hour, some areas have no clear road at all. |
| "Time and distance don't matter" | Yes, with one guard: without any limit, the cleanest road can be a 3× detour or a loop. A cap the rider sets ("up to 2× longer") stops that. |

The honest promise is: **"The ride that keeps you moving: fewer stops, and it steers you around jams as they appear."** Not "zero traffic".

## Decisions (6 Nov)

| Question | Decision |
|---|---|
| Length cap | **Up to 3×** the normal route's length |
| Red lights | Don't count as traffic, but **avoid when possible**: a small penalty per signal, so of two equally clear routes the one with fewer signals wins |
| Roads with no traffic data | **Avoid**: they score like slow roads, so they're used only when nothing with data is clear |
| Switching | **Automatic**, with a **10-second "Keep this road"** to cancel |
| Guidance | **In the app**: turn arrows on the riding screen, voice through the headset |
| Where | **Delhi NCR first, planned to scale** (see Scaling) |

## What the app has today

- A map (MapLibre with MapTiler), GPS, speed and the riding screen.
- **No routing, no turn-by-turn directions and no traffic data.** All three are new.

## The parts

1. **Traffic data.** Pick one provider:

| Provider | Good for | Cost |
|---|---|---|
| **Google Routes API** | Best coverage in India; has a **two-wheeler** mode; returns up to 3 routes, each with its slow and jammed stretches | Monthly free allowance, then paid per request. A 1-hour ride re-checked every minute is about 60 requests. Fine for you alone; it matters at scale. |
| TomTom Traffic | Live speed on every road segment it covers (so the app can judge any road, not just 3 routes); 2,500 free requests a day | Free tier, then paid |
| HERE Traffic | Similar to TomTom | Free tier, then paid |

   **Start with Google** for the first version (two-wheeler mode, best India data). Add TomTom later if 3 routes aren't enough choice.

2. **The planner.** It scores each route on how clear it is:
   - jammed and slow stretches, weighted by length
   - the number of traffic signals (from OpenStreetMap; incomplete in India)
   - your own past rides through that area at that hour (see part 6)
   
   It picks the clearest route within the length cap (3×; roads with no traffic data count as slow; each signal adds a small penalty) and shows it on the map, coloured by traffic.

3. **Live watch.** Every 60 s, and as you approach each decision point, it re-reads the next 3–5 km.
   - When a jam appears ahead, it asks for routes from where you are now.
   - It also forces detours: it tries waypoints on parallel roads to the left and right of the jam, and a U-turn when the road behind is clear.
   - It switches to the clearest. On the riding screen: "Jam ahead · taking the left road", with a 5-second "keep this road" option.

4. **Guidance.** Two options:
   - **(a) Hand off to Google Maps:** open its navigation with the planner's waypoints, and open it again on each reroute. Cheap to build, but Maps reroutes by its own logic between our waypoints, and switching apps mid-ride is clunky.
   - **(b) The app's own:** next-turn arrow and distance on the riding screen, plus voice prompts through your headset. Built from the route's steps, so it never fights the planner.
   
   **I'd build (b).** The phone is already on the mount running the app, and (a) breaks exactly when it matters (on reroutes).

5. **Destination search.** Search places with the MapTiler key you already have, or long-press the map.

6. **Your own traffic memory** (later). Every ride already records your speed second by second. Over time the app learns which roads are clear for *you*, at which hour, on a bike. That's better than car-based data, because you filter through traffic that stops cars.

7. **Own router** (only if needed). Run routing on your own road map of the city (OpenStreetMap plus GraphHopper on a small server), weighted by live traffic tiles. This allows any detour, not just the 3 routes Google offers. It's the most capable option, and the most work: a server to host and pay for, plus the traffic feed to integrate. Only worth it if version 1 proves too limited.

## Build order

| Step | What | Size |
|---|---|---|
| 0 | **Reality check before building:** record the traffic data along 2–3 of your usual routes while you ride, and compare it with what you actually met | Small: a test-build logger |
| 1 | Destination search, Google Routes (two-wheeler, with traffic), clearness score, route on the map coloured by traffic, start the ride | Medium |
| 2 | Live watch: re-check the road ahead, forced detours via waypoints, U-turns, switch with "keep this road" | Medium |
| 3 | Own turn-by-turn on the riding screen and voice through the headset; off-route detection | Large: the biggest single part |
| 4 | Your own traffic memory from past rides | Medium |
| 5 | Own router with live traffic tiles | Large, plus a server. Only if needed |

Steps 0–3 make a usable feature.

## What you'd need to provide

- **A Google Cloud project with the Routes API turned on and billing enabled** (the free monthly allowance covers personal use). Add its key as an environment secret, never in chat.
- Decisions, in the next section.

## Scaling (beyond Delhi NCR and beyond one rider)

What changes when it's more cities and many riders:

1. **Keys never ship in the app.** A small Keppo server (or Firebase Functions, which the app already uses) calls the traffic provider. That way the key isn't in the APK, usage can be capped per rider, and the provider can be swapped without an app update. Needed before any public release; for testing on your phone, a key in the test build is fine.
2. **Provider behind one interface.** "Routes with traffic" and "traffic ahead" are asked through one door in the app, so Google, TomTom or HERE can be used per country or per price.
3. **Cost per ride, kept down:**
   - Re-check less often when the road ahead is clear (every 2–3 min), more often near known trouble.
   - Ask only about the next few km, not the whole route.
   - Riders on the same roads share recent answers through the server cache (a minute or two old is fine).
   - Rough cost at today's prices: a few rupees per hour of riding, before caching. Pro feature territory.
4. **Shared traffic memory.** Riders who opt in contribute anonymous speed-by-road-by-hour from their rides. Two-wheeler speeds are a dataset no provider has, and it gets better with every rider in a city. Needs a privacy design: aggregated, never individual routes.
5. **Own router when the numbers justify it:** OpenStreetMap roads per region plus live traffic tiles and the shared memory, on our server. Any detour, not just a provider's 3 routes, and cheaper per request at scale.
6. **New cities:** check the provider's data quality there first (step 0 again), plus signals data from OpenStreetMap, which varies by city.

The first version is built so none of this needs a rewrite: the provider interface and the scoring are separate from the screens, and the server can be put in front later.

## Questions (answered 6 Nov, see Decisions)

1. **Length cap:** how much longer may the clear route be? (Suggest 2×, or 30 min more; you can change it per ride.)
2. **Traffic signals:** do red lights count as "traffic"? Avoiding them makes routes much longer in Delhi.
3. **Roads with no traffic data** (lanes, colonies): treat them as clear, or avoid them?
4. **Switching:** switch by itself with the 5-second "keep this road", or ask every time?
5. **Guidance:** the app's own on the riding screen (recommended), or hand off to Google Maps?
6. **Where:** mostly Delhi NCR? That decides which provider's data to check first in step 0.

## Final plan (6 Nov)

Name: **Keep moving**. **Pro** feature. Pricing to revisit once step 0 and the first rides show the real cost per hour. Options then: raise Pro; include N hours of Keep moving a month in Pro with an add-on pack; or lean on caching and the shared memory to cut provider calls.

### You
1. **Google Cloud:** in the Google Cloud console, open the **keppo-moto** project (the same one Firebase uses).
2. **Billing:** Billing › link a billing account. Set a **budget alert** (e.g. ₹500 a month) so nothing surprises you.
3. **Routes API:** APIs & Services › Library › **Routes API** › Enable.
4. **Key:** APIs & Services › Credentials › Create credentials › API key. Edit it:
   - **API restrictions:** Routes API only.
   - **Application restrictions:** Android apps, package `com.keppo.moto`, SHA-1 `D5:7C:D7:F0:05:CA:19:90:BA:87:09:76:03:E0:4E:AC:94:EB:F5:0E` (the test builds' signing key).
   - **Quotas** (Routes API › Quotas): cap it at about 2,000 requests a day.
5. **Environment settings:** add `GOOGLE_ROUTES_KEY` (and `GOOGLE_SERVICES_JSON`, `APPCHECK_DEBUG_TOKEN`, `MAPTILER_KEY` if not added yet), then start a new session and ask for step 0.
6. **Step 0 rides:** install the logger build, pick the destination of 2–3 usual routes, ride each 2–3 times at different hours, export, and tell me roughly where you got stuck.
7. **Go / no-go:** read the step 0 report with me.
8. **Try each build** after steps 1–3 and send feedback.
9. **Pricing decision** with the real cost per hour.

### Me
0. **Logger (test builds):** set a destination; every 2 min during the ride, Google's two-wheeler route with traffic is fetched and saved (slow and jammed stretches, signals on it), exported with the ride. Then a report: what the data said vs your actual speed on the same road (stopped, crawling, moving). One build.
1. **Plan the ride:** traffic provider interface with Google behind it; destination search (MapTiler) or long-press; up to 3 routes with traffic; clearness score (jams and slow by length, no-data roads as slow, small penalty per signal, cap 3×); the chosen route on the map coloured by traffic; Start.
2. **Keep moving live:** re-check the next 3–5 km (every 2–3 min when clear, every 60 s near trouble); on a jam, routes from here plus forced detours (parallel roads left and right, a U-turn when behind is clear); automatic switch with a 10 s "Keep this road" on the riding screen; usage counter.
3. **Directions:** next-turn arrow and distance on the riding screen and pop-up; voice through the headset (turns, "jam ahead, taking the left road"); off-route detection and re-plan; arrival.
4. **Pro and cost controls:** Pro gate, monthly usage shown, request caching, a log of calls for the cost report.
5. **Later, for scale:** the server in front of the provider (Firebase Functions), opt-in shared two-wheeler traffic memory, own router when the numbers justify it.

Each step: unit tests for the logic (scoring, detour picking, switching rules, guidance), lint, logged in the plan log, one APK per step.
