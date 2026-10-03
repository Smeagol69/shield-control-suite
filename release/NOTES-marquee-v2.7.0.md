# Marquee 2.7.0

- Learns a private taste model from local playback, Trakt history, ratings,
  watchlist activity, genres, decades, media types, quality, and cast affinity.
- Searches the wider catalog for personalized results instead of only reordering
  fixed lists.
- Adds `Because you liked`, person-affinity, `Up next`, franchise, and free-with-ads
  shelves, with explanations for why recommendations match.
- Adds `Not interested`, like/dislike ratings, post-play prompts, and Trakt rating
  synchronization.
- Adds Browse filters for decade, mood, and runtime.
- Improves detail pages with one-call metadata loading, logos, certification,
  runtime, cast filmographies, and trailers.
- Preserves TV focus and shelf position after opening a title.

Validated by 94 Android unit tests with no failures, Android lint with no errors,
and an R8-minified signed release APK. The APK retains the deployed signing identity
for in-place upgrades.
