# Achievements: redesign plan (#47)

Plan only. No prototype (rider, 20 Oct). Open questions decided by Claude: level names as below; all badges in §3; no speed or lean records (§5). Build order in `final-plan-20-oct.md`.

## What's there today (Found in the code)
- **22 achievements in 7 groups:**
  - Distance: 100 to 10,000 km.
  - Long days: 100, 200 and 400 km days, and a 4-hour ride.
  - Riding often: 4 weekends in a row, 30 riding days a year.
  - Early and late: a sunrise ride, a night ride.
  - The bike: 10 fill-ups, a first service reminder.
  - Reels: 1, 10 and 50 Reels, a first series episode.
  - Ready to ride: crash alerts set up, mount calibrated.
- **The page:** two plain lists, "Earned" and "Still to reach", each row a small trophy or lock icon with a progress bar. There's no artwork, no celebration when you earn one, and nothing to share.
- **A deliberate rule in the code:** no goals for speed or lean, "they'd reward riding harder". This plan keeps that rule (see §5, which affects the 0–60 personal-best idea).
- Everything is worked out from data the app already has, and nothing is stored. That stays the same.

## 1. The new page

**Top: your rider level (Claude's idea)**
- A level from all your riding: **Rookie → Rider → Tourer → Road Captain → Legend**, with a ring showing how far you are to the next one.
- Points come from earning badges, not from speed. A gold badge counts more than a bronze one.
- Under it: "14 of 46 badges · 3 this month".

**Next up**
- A swipeable row of the 3 badges you're closest to, each with a progress ring and what's left: "62 km to go", "1 more weekend".

**Your riding year**
- A calendar grid of the year, one square per day, coloured by distance. It shows streaks at a glance.
- "Current streak: 3 weekends · Best: 6".

**Badges, by group**
- A grid of round badges, 3 per row, per group.
- **Earned badges** are in full colour.
- **Badges not reached yet** are grey silhouettes with their progress.
- **Secret badges** show as "?" until earned (see §3).

**Records**
- Facts, not goals:
  - longest ride;
  - longest day;
  - highest point reached;
  - farthest from home;
  - most moments in one ride;
  - longest streak.
- Each one opens the ride it happened on.

## 2. Badge design
- **Each badge has its own drawing:** a road for distance, a sun for sunrise, a fuel drop for fill-ups, a clapper board for Reels, and so on. They're drawn as vector art in the app's style.
- **Tiers** for the stepped badges: **Bronze → Silver → Gold → Platinum → Diamond**.
  - For example, distance goes 100, 500, 1,000, 5,000, 10,000 km.
  - One badge then levels up, instead of five separate rows.
- **Badge detail (tap a badge):**
  - a big badge with a light shine that follows the phone's tilt;
  - when you earned it;
  - the ride (a small map and its numbers), with **Open ride**;
  - the next tier and how far it is;
  - **Share**.
- **Share card:** a 9:16 picture of the badge, your bike's name, the ride's map and numbers, in your bike's colour (#44). Ready for Instagram Stories.

## 3. New badges (Claude's idea: tell me what to drop or add)

**Exploring**
- New places: 5, 25 and 100 towns or areas ridden through. This uses the place names rides already get.
- New roads: 100, 500 and 2,000 km of roads you'd never ridden before.
- Highest point: 1,000, 2,000 and 3,000 m above sea level, from GPS.
- Hairpins: 10, 50 and 200 hairpin turns ridden (from sharp turns).
- States: 2, 5 and 10 Indian states (or regions abroad).

**Seasons**
- Monsoon rider: 10 rides from June to September.
- Winter sunrise: a sunrise ride in December or January.
- Every month: a ride in each month of a year.

**Time with the bike**
- One year with your bike, counted from your first ride on it.
- Per bike, from its odometer or ride total: 1,000, 10,000 and 50,000 km.

**Moments and Studio**
- 1, 100 and 1,000 moments filmed.
- First Storyboard template filled (#40).
- A Reel in every series.

**Secret badges (hidden until earned)**
These are fun, and come from the new keywords (#41):
- **Bless you:** a sneeze caught on a moment.
- **Aaaaah:** a scream on a moment.
- **Midnight rider:** riding across midnight on New Year's Eve.
- **Same time next year:** a ride on the same date a year later.
- **Full tank, full day:** 3 fill-ups in one day.

**Kept:**
- every badge that exists today, folded into tiers;
- "Ready to ride" (crash alerts, mount), as a small setup checklist at the bottom rather than as badges.

## 4. Earning moments
- **Never during a ride.** Nothing pops up on the HUD while you're riding, so nothing distracts you or pushes you on.
- **After the ride:** the ride summary shows "New badge" with the badge, a short shine animation and confetti. Then **Share** or **See all**.
- **Badges earned without a ride** (a Reel, a fill-up) get a quiet in-app note the next time you open the app.
- A small store keeps track of which badges you've already been shown, so each one is celebrated once.

## 5. Speed, lean and the 0–60 idea
- The code's rule says no speed or lean goals. That's a safety choice: a badge for leaning further or going faster rewards riding harder.
- I proposed "personal best 0–60" keywords and a HUD note for #41 earlier. That breaks this rule, so I'd change it:
  - the 0–60 and braking runs stay as **keywords** on moments (facts, for finding clips);
  - **no badges** for them, **no "Best" tag**, and **no HUD note**;
  - top speed and best 0–60 can show under Records as plain facts, if you want them.
- **Question:** OK with that? Or do you want speed records shown at all?

## 6. Build notes
- `Achievements.kt` stays pure and tested. It gains tiers, new badges, levels and records, all from existing data:
  - rides and their samples;
  - place names;
  - moment keywords (#41);
  - fills, Reels and bikes.
- **New badges need some data that isn't there yet:**
  - Altitude is in the samples already.
  - New roads needs a grid of ridden areas, worked out once and kept up to date.
  - States need place names to include the state.
  - The secret sound badges need #41's keywords first.
- Badge art: about 25 vector drawings, made in Compose, with tier colours.
- Share card: drawn like the existing story card, at 1080×1920.

## Questions
- **Question:** The level names (Rookie → Rider → Tourer → Road Captain → Legend): like them, or other ideas?
- **Question:** Any badges to drop or add in §3?
- **Question:** §5: speed records shown as plain facts, or not at all?
- After your answers: a prototype of the new page, badge detail, the after-ride celebration and the share card.
