Place golden-truth pairs here for regression tests:

- `{id}.png` — frozen Smash board
- `{id}.json` — labels from the in-app review screen

Copy from the device after labeling:

```
adb exec-out run-as com.personal.solitaireassistant sh -c 'cd files/golden && tar cf - .' > golden.tar
```

`SmashGoldenTruthTest.desktopEvaluatePrintsSameReportAsDevice` loads every JSON+PNG pair and prints the same accuracy / confusion report as in-app Evaluate. Robolectric usually has no OpenCV natives, so numbers can differ from the phone.

An empty folder is valid; add samples when you want desktop coverage.
