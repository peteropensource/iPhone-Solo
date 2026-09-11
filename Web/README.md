# TiltFold — web port

A zero-dependency ES module (`tiltfold.js`) plus a demo page. No framework, no build step,
no bundler, nothing loaded from a CDN. The implementation follows
[`../docs/ALGORITHM.md`](../docs/ALGORITHM.md) exactly.

## Run it locally

ES modules and `URL.createObjectURL` both need a real HTTP origin, so `file://` will not work.
Serve the folder:

```sh
cd Web
python3 -m http.server 8000
```

Then open <http://localhost:8000>.

The `How it works` links point at `../docs/ALGORITHM.md` and the repository root, which resolve
when the site is served from the repo root (as on GitHub Pages) but 404 under a server rooted at
`Web/`. Serve from the repo root instead if you want those links live:

```sh
python3 -m http.server 8000      # from the repository root
# then open http://localhost:8000/Web/
```

Device tilt on iOS additionally requires **https** — `DeviceOrientationEvent.requestPermission()`
rejects on an insecure origin. Use the deployed GitHub Pages URL to try it on a phone; the page
falls back to drag + slider everywhere else.

## Using the module

```html
<div id="stage" style="width:390px;height:844px"></div>
<script type="module">
  import { TiltFold } from './tiltfold.js';

  const tf = new TiltFold(document.getElementById('stage'), {
    blackAt: 75,       // 30..90   tilt at which the content is fully gone
    deadzone: 3,       // 0..15    tilt below which nothing happens
    softness: 1.0,     // 0.1..2.0 length of the dissolve front
    eyeDistance: 2.0,  // 0.8..5   viewer distance, as a multiple of max(W, H)
    levels: 4,         // rungs in the blur ladder
  });

  tf.setImage('photo.jpg');   // or a blob: URL
  tf.setRoll(-26);            // -90..90, the only live input
  // tf.destroy();
</script>
```

Do not put `overflow: hidden` on the container if you can avoid it — the blurred levels
deliberately overspill their box so the blur melts into the background (ALGORITHM.md §8).
Clipping at a frame *outside* the container (as the demo's phone bezel does) is fine.

The pure functions (`smoothstep`, `tiltOf`, `progressOf`, `frontOf`, `phaseAt`, `blurIndexOf`,
`alphaOf`, `levelMaskOf`, `sigmaFractions`) are exported too, so the maths can be checked against
the reference values in ALGORITHM.md §10 without touching the DOM.

## Deploy to GitHub Pages

1. Push the repository to GitHub.
2. **Settings → Pages → Build and deployment → Source: Deploy from a branch**, branch `main`,
   folder `/ (root)`.
3. Save. The demo lands at `https://<user>.github.io/<repo>/Web/`, and the relative links to
   `../docs/ALGORITHM.md` and the repo root resolve correctly from there.

There is nothing to build: the files are served exactly as they sit in the repository. If you
would rather have the demo at the site root, move the contents of `Web/` up one level (or copy
them into a `docs/` folder and point Pages at `/docs`) and fix the two relative links in
`index.html`.

## Privacy

Everything runs in the browser. Pictures chosen with "use your own image" are read with
`URL.createObjectURL` and never leave the device; the page makes no network requests beyond the
files in this folder.
