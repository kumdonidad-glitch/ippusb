Canon CHMP sample

Source: extracted from capture.prn (partial). This file contains the StartJob control frame and the first 64KiB of the following raster payload to act as a golden test vector for CHMP/PWG tuning.

Usage:
- tools/ReplayChmpSample.kt will read this file and feed it to CanonChmpClient.postDocument for offline testing.

Legal/ownership note:
- The sample was derived from capture.prn which you uploaded. The sample is trimmed to the minimum bytes required for reproducing CHMP control+payload behavior.
