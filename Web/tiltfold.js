/**
 * TiltFold — zero-dependency web port.
 *
 * A faithful implementation of docs/ALGORITHM.md. No framework, no build step,
 * no imports. Load it with <script type="module">.
 *
 *   import { TiltFold } from './tiltfold.js';
 *   const tf = new TiltFold(el, { blackAt: 75, deadzone: 3, softness: 1, eyeDistance: 2, levels: 4 });
 *   tf.setImage('photo.jpg');
 *   tf.setRoll(-26);
 *   tf.destroy();
 *
 * The mental model (ALGORITHM.md §1): the picture is a flat thing hanging in 3D
 * space and the container is a window you look through. Tilting moves your eye,
 * not the picture. The far part of the picture recedes, goes out of focus and
 * finally leaves the window.
 */

/* -------------------------------------------------------------------------- */
/* §2, §3, §5, §6 — pure maths. Exported so they can be unit-checked directly   */
/* against the reference values in ALGORITHM.md §10.                            */
/* -------------------------------------------------------------------------- */

export function clamp(x, lo, hi) {
  return x < lo ? lo : x > hi ? hi : x;
}

/** The usual Hermite ramp. ALGORITHM.md §6. */
export function smoothstep(e0, e1, x) {
  const t = clamp((x - e0) / (e1 - e0), 0, 1);
  return t * t * (3 - 2 * t);
}

/** §3 — degrees of effective tilt, after the deadzone is removed. */
export function tiltOf(roll, deadzone) {
  return clamp(Math.abs(roll) - deadzone, 0, 90);
}

/** §3 — 0 = untouched, 1 = fully gone. Drives the dissolve, not the rotation. */
export function progressOf(roll, deadzone, blackAt) {
  return clamp(tiltOf(roll, deadzone) / (blackAt - deadzone), 0, 1);
}

/**
 * §5 — leading position of the dissolve front, in `u` (0 at the root edge,
 * 1 at the far edge). The (1 + softness) factor is what lets the root itself
 * finish dissolving at progress = 1.
 */
export function frontOf(progress, softness) {
  return 1 - progress * (1 + softness);
}

/** §5 — phase at normalised distance `u` from the root. 0 = untouched, 1 = gone. */
export function phaseAt(u, front, softness) {
  return clamp((u - front) / softness, 0, 1);
}

/** §6 — fractional index into the blur ladder. Finishes early, at s = 0.65. */
export function blurIndexOf(s, levels) {
  return (levels - 1) * smoothstep(0, 0.65, s);
}

/** §6 — opacity. Starts late, at s = 0.3. The offset from 0.65 is the whole trick. */
export function alphaOf(s) {
  return 1 - smoothstep(0.3, 1.0, s);
}

/**
 * §7 — mask for ladder level `i`. Under source-over compositing with the
 * blurriest level at the bottom, this is an exact cross-fade between neighbours.
 */
export function levelMaskOf(i, s, levels) {
  return 1 - clamp(blurIndexOf(s, levels) - i, 0, 1);
}

/** §7 — blur of the last, blurriest level, as a fraction of picture width. */
export const MAX_SIGMA_FRACTION = 0.09;

/** §7 — curvature of the ramp. 1 would be linear; higher pushes levels toward the sharp end. */
export const SPACING_EXPONENT = 1.75;

/**
 * §7 — sigma for each ladder level, as a fraction of the picture's width so the
 * effect is resolution independent. The exponent makes the spacing geometric:
 * linear spacing wastes levels at the sharp end, where the eye is most sensitive
 * to a step between neighbours. The four-level default is
 * [0, 0.0132, 0.0443, 0.09].
 */
export function sigmaFractions(levels) {
  const n = Math.max(2, levels | 0);
  const out = [0];
  for (let i = 1; i < n; i++) {
    out.push(MAX_SIGMA_FRACTION * Math.pow(i / (n - 1), SPACING_EXPONENT));
  }
  return out;
}

/* -------------------------------------------------------------------------- */
/* Component                                                                    */
/* -------------------------------------------------------------------------- */

export const DEFAULTS = Object.freeze({
  blackAt: 75,      // 30..90   tilt at which the content is fully gone
  deadzone: 3,      // 0..15    tilt below which nothing happens
  softness: 1.0,    // 0.1..2.0 length of the dissolve front
  eyeDistance: 2.0, // 0.8..5   viewer distance, as a multiple of max(W, H)
  levels: 4,        // size of the blur ladder
});

/** Number of colour stops sampled from each response curve (§5 suggests ~24). */
const STOP_COUNT = 24;

const STYLE_ID = 'tiltfold-styles';
// Note: `.tf-root` carries no positioning. The injected sheet lands after the
// page's own stylesheet, so a rule here would silently beat whatever the host
// page said about the container. Positioning is applied inline instead, and
// only when the container is still `static`.
const STYLE_TEXT = `
.tf-stack, .tf-level {
  position: absolute;
  pointer-events: none;
  -webkit-mask-repeat: no-repeat; mask-repeat: no-repeat;
  -webkit-mask-position: 0 0;     mask-position: 0 0;
  -webkit-mask-size: 100% 100%;   mask-size: 100% 100%;
}
.tf-stack { will-change: transform, -webkit-mask-image, mask-image; }
.tf-level img {
  position: absolute;
  display: block;
  object-fit: cover;
  pointer-events: none;
  user-select: none;
  -webkit-user-select: none;
  -webkit-user-drag: none;
}
`;

let styleRefCount = 0;

function installStyles() {
  styleRefCount++;
  if (document.getElementById(STYLE_ID)) return;
  const style = document.createElement('style');
  style.id = STYLE_ID;
  style.textContent = STYLE_TEXT;
  document.head.appendChild(style);
}

function uninstallStyles() {
  styleRefCount = Math.max(0, styleRefCount - 1);
  if (styleRefCount === 0) {
    const el = document.getElementById(STYLE_ID);
    if (el) el.remove();
  }
}

export class TiltFold {
  /**
   * @param {HTMLElement} container the window you look through. Its box defines
   *        W and H. It gets `position: relative` and the CSS perspective.
   * @param {object} [options] see DEFAULTS.
   */
  constructor(container, options = {}) {
    if (!(container instanceof Element)) {
      throw new TypeError('TiltFold: first argument must be an element');
    }
    this.container = container;
    this.options = { ...DEFAULTS, ...options };
    this.options.levels = Math.max(1, Math.round(this.options.levels));

    this._roll = 0;
    this._src = null;
    this._size = { W: 0, H: 0 };
    this._frame = 0;          // pending requestAnimationFrame handle
    this._destroyed = false;
    this._savedPosition = container.style.position;

    installStyles();
    this._buildDom();
    this._measure();

    // Re-derive the geometry (all of which scales with W) whenever the window
    // the picture hangs in changes size.
    this._ro = new ResizeObserver(() => this.refresh());
    this._ro.observe(container);

    // Web fonts, late stylesheets and image decodes can all shift the layout
    // after the first paint, and some browsers throttle ResizeObserver delivery
    // for background tabs, so take one more look once everything has loaded.
    this._onLoad = () => this.refresh();
    if (document.readyState !== 'complete') {
      window.addEventListener('load', this._onLoad, { once: true });
    }

    this._render();
  }

  /* ---------------------------------------------------------------------- */
  /* Public API                                                              */
  /* ---------------------------------------------------------------------- */

  /**
   * Point every rung of the ladder at a new picture. Accepts a normal URL or a
   * blob: URL from URL.createObjectURL.
   * @returns {Promise<void>} resolves once the picture has decoded.
   */
  setImage(url) {
    this._src = url;
    for (const img of this._imgs) img.src = url;
    const probe = this._imgs[0];
    if (!probe) return Promise.resolve();
    if (probe.decode) {
      return probe.decode().catch(() => {}).then(() => {
        this._render();
      });
    }
    return new Promise((resolve) => {
      probe.addEventListener('load', () => resolve(), { once: true });
      probe.addEventListener('error', () => resolve(), { once: true });
    }).then(() => this._render());
  }

  /** The only live input. Degrees, -90..90. Negative = left edge is lower. */
  setRoll(degrees) {
    const v = Number(degrees);
    if (!Number.isFinite(v)) return;
    this._roll = clamp(v, -90, 90);
    this._schedule();
  }

  get roll() {
    return this._roll;
  }

  /** Re-measure the container and redraw immediately. */
  refresh() {
    this._measure();
    this._render();
  }

  /** Change any of the tuning constants at runtime. */
  setOptions(partial) {
    Object.assign(this.options, partial);
    this.options.levels = Math.max(1, Math.round(this.options.levels));
    if (this.options.levels !== this._levelEls.length) {
      this._buildDom();
      if (this._src) this.setImage(this._src);
    }
    this._measure();
    this._render();
  }

  /**
   * Every derived scalar for the current roll. Handy for driving overlays
   * (§5, "phase at an arbitrary point") and for testing against §10.
   */
  get state() {
    const o = this.options;
    const roll = this._roll;
    const tilt = tiltOf(roll, o.deadzone);
    const progress = progressOf(roll, o.deadzone, o.blackAt);
    const front = frontOf(progress, o.softness);
    return {
      roll, tilt, progress, front,
      softness: o.softness,
      levels: o.levels,
      direction: roll < 0 ? -1 : 1,
      W: this._size.W,
      H: this._size.H,
    };
  }

  /** Phase at a point given in container coordinates (§5). */
  phaseAtPoint(x, y) {
    const { W } = this._size;
    const st = this.state;
    if (!W) return 0;
    const x0 = this._axisX(st.front, st.direction, W);
    const x1 = this._axisX(st.front + st.softness, st.direction, W);
    const ax = x1 - x0;
    // The axis is purely horizontal in the left/right-only version, so the dot
    // products collapse to one dimension.
    if (Math.abs(ax) < 1e-9) return x >= x0 ? 1 : 0;
    return clamp((x - x0) / ax, 0, 1);
  }

  destroy() {
    if (this._destroyed) return;
    this._destroyed = true;
    if (this._frame) cancelAnimationFrame(this._frame);
    if (this._ro) this._ro.disconnect();
    if (this._onLoad) window.removeEventListener('load', this._onLoad);
    if (this._root && this._root.parentNode) this._root.remove();
    this.container.classList.remove('tf-root');
    this.container.style.position = this._savedPosition;
    this.container.style.perspective = '';
    this.container.style.perspectiveOrigin = '';
    uninstallStyles();
  }

  /* ---------------------------------------------------------------------- */
  /* DOM                                                                     */
  /* ---------------------------------------------------------------------- */

  _buildDom() {
    if (this._root && this._root.parentNode) this._root.remove();

    const N = this.options.levels;
    if (getComputedStyle(this.container).position === 'static') {
      this.container.style.position = 'relative';
    }
    this.container.classList.add('tf-root');
    // §4: perspective-origin at the viewport centre is what makes the picture
    // recede toward the middle of the window instead of skewing.
    this.container.style.perspectiveOrigin = '50% 50%';

    const stack = document.createElement('div');
    stack.className = 'tf-stack';

    this._levelEls = [];
    this._imgs = [];
    // §7: blurriest at the bottom, sharp on top. DOM order is paint order, so
    // append level N-1 first.
    for (let i = N - 1; i >= 0; i--) {
      const level = document.createElement('div');
      level.className = 'tf-level';
      level.dataset.level = String(i);
      const img = document.createElement('img');
      img.alt = '';
      img.draggable = false;
      if (this._src) img.src = this._src;
      level.appendChild(img);
      stack.appendChild(level);
      this._levelEls[i] = level;
      this._imgs[i] = img;
    }

    this.container.appendChild(stack);
    this._root = stack;
    this._stack = stack;
  }

  _measure() {
    const r = this.container.getBoundingClientRect();
    // A zero measurement is nearly always a transient layout state (or a
    // hidden tab). Keeping the last good size means we never throw away a
    // correct geometry because of one bad frame.
    if (r.width > 0 && r.height > 0) this._size = { W: r.width, H: r.height };
  }

  _schedule() {
    if (this._frame || this._destroyed) return;
    this._frame = requestAnimationFrame(() => {
      this._frame = 0;
      this._render();
    });
  }

  /* ---------------------------------------------------------------------- */
  /* Geometry                                                                */
  /* ---------------------------------------------------------------------- */

  /**
   * §5, point(u) = C + d * (1 - 2u) * E, collapsed to its x component for the
   * left/right-only case where d = (±1, 0) and E = W/2.
   *
   *   direction = -1 (left root)  ->  x = u * W
   *   direction = +1 (right root) ->  x = (1 - u) * W
   */
  _axisX(u, direction, W) {
    return (W / 2) * (1 + direction * (1 - 2 * u));
  }

  /**
   * Build one CSS linear-gradient mask.
   *
   * The spec defines the gradient in container coordinates, but every level has
   * a different box (§8: each is expanded by its own 3*sigma margin), so the
   * same two container-space endpoints land at different percentages inside each
   * level. We therefore convert container x -> local percentage here, which is
   * the direction §8 asks for ("build the masks in viewport coordinates and
   * convert to each level's local coordinates, rather than the other way
   * around").
   *
   * @param {(s:number)=>number} curve   response curve, 0..1, sampled over s
   * @param {number} x0      container x where s = 0
   * @param {number} x1      container x where s = 1
   * @param {number} boxLeft container x of this element's left edge (<= 0)
   * @param {number} boxW    this element's width
   */
  _gradient(curve, x0, x1, boxLeft, boxW) {
    const stops = [];
    const degenerate = Math.abs(x1 - x0) < 1e-6;
    for (let j = 0; j < STOP_COUNT; j++) {
      const s = j / (STOP_COUNT - 1);
      // A degenerate (zero-width) front becomes a hard step at x0.
      const x = degenerate ? x0 + (s < 0.5 ? -0.01 : 0.01) : x0 + (x1 - x0) * s;
      const pos = ((x - boxLeft) / boxW) * 100;
      stops.push([pos, curve(s)]);
    }
    // CSS requires non-decreasing stop positions. When the root is the right
    // edge the axis runs leftwards, so emit the samples in reverse.
    if (x1 < x0) stops.reverse();

    // Outside the sampled range CSS holds the first/last colour, which is
    // exactly the clamping behaviour s(u) already has.
    const body = stops
      .map(([p, a]) => `rgba(0,0,0,${a.toFixed(4)}) ${p.toFixed(3)}%`)
      .join(',');
    return `linear-gradient(90deg,${body})`;
  }

  _setMask(el, gradient) {
    el.style.webkitMaskImage = gradient; // Safari
    el.style.maskImage = gradient;
  }

  /* ---------------------------------------------------------------------- */
  /* Render                                                                  */
  /* ---------------------------------------------------------------------- */

  _render() {
    if (this._destroyed) return;
    const { W, H } = this._size;
    if (!W || !H) return;

    const o = this.options;
    const N = o.levels;
    const roll = this._roll;

    // §3 — derived scalars.
    const tilt = tiltOf(roll, o.deadzone);
    const progress = progressOf(roll, o.deadzone, o.blackAt);
    const direction = roll < 0 ? -1 : 1; // -1 = left root, +1 = right root
    const softness = o.softness;
    const front = frontOf(progress, softness);

    // §7/§8 — ladder sigmas in CSS pixels and the matching 3*sigma margins.
    // CSS blur() takes a standard-deviation-like radius, so sigma goes in raw.
    const sigmas = sigmaFractions(N).map((f) => f * W);
    const margins = sigmas.map((s) => 3 * s);
    const M = margins[N - 1]; // the widest overspill; the stack must contain it

    // §5 — the two ends of the dissolve axis, in container coordinates.
    const x0 = this._axisX(front, direction, W);
    const x1 = this._axisX(front + softness, direction, W);

    /* ---- the stack: box, transform, and the overall alpha mask ---------- */

    const stackW = W + 2 * M;
    const stackH = H + 2 * M;
    const stack = this._stack;
    stack.style.left = `${-M}px`;
    stack.style.top = `${-M}px`;
    stack.style.width = `${stackW}px`;
    stack.style.height = `${stackH}px`;

    // §4 in its CSS form. transform-origin does the translate-to-hinge and back;
    // perspective-origin (set on the container) puts the vanishing point at the
    // centre of the window. The hinge is the LEFT edge for roll < 0 and the
    // RIGHT edge for roll > 0, so both the origin and the sign of the rotation
    // flip with `direction`.
    const originX = direction < 0 ? M : M + W;
    this.container.style.perspective = `${o.eyeDistance * Math.max(W, H)}px`;
    stack.style.transformOrigin = `${originX}px 50%`;
    stack.style.transform = `rotateY(${(-direction * tilt).toFixed(4)}deg)`;

    // §6/§7 — mask the whole stack with alpha(s).
    this._setMask(stack, this._gradient(alphaOf, x0, x1, -M, stackW));

    /* ---- each rung of the ladder --------------------------------------- */

    for (let i = 0; i < N; i++) {
      const m = margins[i];
      const el = this._levelEls[i];
      const img = this._imgs[i];

      // §8, the part everyone gets wrong: the blurred copy lives in a box grown
      // by 3*sigma on every side, so the feathered edge that CSS blur paints
      // outside the picture is still inside the element (and therefore still
      // inside the element's mask). The <img> keeps its own W x H size, centred
      // on the same point, so every level shares one scale and stays registered.
      el.style.left = `${M - m}px`;
      el.style.top = `${M - m}px`;
      el.style.width = `${W + 2 * m}px`;
      el.style.height = `${H + 2 * m}px`;
      el.style.filter = sigmas[i] > 0 ? `blur(${sigmas[i].toFixed(3)}px)` : '';

      img.style.left = `${m}px`;
      img.style.top = `${m}px`;
      img.style.width = `${W}px`;
      img.style.height = `${H}px`;

      // §7 — levelMask(i, s) = 1 - clamp(blurIndex(s) - i, 0, 1).
      const curve = (s) => levelMaskOf(i, s, N);
      this._setMask(el, this._gradient(curve, x0, x1, -m, W + 2 * m));
    }
  }
}

export default TiltFold;
