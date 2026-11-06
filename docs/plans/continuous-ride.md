# Continuous ride

Status: plan only. Asked for on 6 Nov: "a route from A to B with zero traffic. If there's traffic ahead, take another route; left, right, U-turn doesn't matter. Time and distance don't matter. The route needs to be clear."

## Is it realistic?

**Partly.**

| What | Realistic? |
|---|---|
| A route that keeps you moving more than the normal route, and changes when a jam appears ahead | **Yes.** Live traffic data exists for Indian cities and can be read every minute. |
| Never meeting traffic at all | **No.** Traffic data is 1–5 minutes old and averaged. Small lanes often have no data. Red lights stop you whatever the traffic. At Delhi rush hour, some areas have no clear road at all. |
| "Time and distance don't matter" | Yes, with one guard: without any limit, the cleanest road can be a 3× detour or a loop. A cap the rider sets ("up to 2× longer") stops that. |

The honest promise is: **"The ride that keeps you moving: fewer stops, and it steers you around jams as they appear."** Not "zero traffic".

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
   
   It picks the clearest route within the length cap and shows it on the map, coloured by traffic.

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

## Questions

1. **Length cap:** how much longer may the clear route be? (Suggest 2×, or 30 min more; you can change it per ride.)
2. **Traffic signals:** do red lights count as "traffic"? Avoiding them makes routes much longer in Delhi.
3. **Roads with no traffic data** (lanes, colonies): treat them as clear, or avoid them?
4. **Switching:** switch by itself with the 5-second "keep this road", or ask every time?
5. **Guidance:** the app's own on the riding screen (recommended), or hand off to Google Maps?
6. **Where:** mostly Delhi NCR? That decides which provider's data to check first in step 0.
