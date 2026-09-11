/**
 * TiltFold demo page. Zero dependencies; the only import is the local module.
 */
import { TiltFold, tiltOf, progressOf, frontOf } from './tiltfold.js';

const $ = (id) => document.getElementById(id);

const stage = $('stage');
const phone = $('phone');

const tf = new TiltFold(stage, {
  blackAt: 75,
  deadzone: 3,
  softness: 1.0,
  eyeDistance: 2.0,
  levels: 4,
});
tf.setImage('assets/sample.jpg');

// Expose for debugging / automated checks.
window.tf = tf;

/* ------------------------------------------------------------------------ */
/* One source of truth for roll                                              */
/* ------------------------------------------------------------------------ */

const rollInput = $('roll');
const rollOut = $('roll-out');

let currentRoll = 0;

/**
 * @param {number} deg
 * @param {'slider'|'drag'|'motion'|'init'} source — the slider is not written
 *        back while the user is dragging its own thumb, so it never fights
 *        itself, but every other source keeps it in sync.
 */
function applyRoll(deg, source) {
  const v = Math.max(-90, Math.min(90, Number(deg) || 0));
  currentRoll = v;
  tf.setRoll(v);
  if (source !== 'slider') rollInput.value = String(v);
  rollOut.textContent = `${v.toFixed(1)}°`;
  updateReadout(v);
}

function updateReadout(roll) {
  const o = tf.options;
  const tilt = tiltOf(roll, o.deadzone);
  const progress = progressOf(roll, o.deadzone, o.blackAt);
  const front = frontOf(progress, o.softness);
  $('r-roll').textContent = `${roll.toFixed(1)}°`;
  $('r-tilt').textContent = `${tilt.toFixed(1)}°`;
  $('r-progress').textContent = progress.toFixed(2);
  $('r-front').textContent = front.toFixed(2);
}

rollInput.addEventListener('input', () => {
  stopMotion('slider');
  applyRoll(parseFloat(rollInput.value), 'slider');
});

/* ------------------------------------------------------------------------ */
/* Desktop / fallback: drag horizontally across the phone                     */
/* ------------------------------------------------------------------------ */

let dragId = null;
let dragStartX = 0;
let dragStartRoll = 0;

phone.addEventListener('pointerdown', (e) => {
  if (dragId !== null) return;
  dragId = e.pointerId;
  dragStartX = e.clientX;
  dragStartRoll = currentRoll;
  // Capture keeps the drag alive when the cursor leaves the frame. It can throw
  // for a pointer id the browser does not consider active, which is harmless.
  try { phone.setPointerCapture(dragId); } catch (_) { /* not capturable */ }
  stopMotion('drag');
});

phone.addEventListener('pointermove', (e) => {
  if (e.pointerId !== dragId) return;
  // Full width of the phone frame maps to 180 degrees, so a drag from one edge
  // to the other covers the whole range.
  const span = phone.getBoundingClientRect().width || 1;
  applyRoll(dragStartRoll + ((e.clientX - dragStartX) / span) * 180, 'drag');
  e.preventDefault();
});

function endDrag(e) {
  if (e.pointerId !== dragId) return;
  try { phone.releasePointerCapture(dragId); } catch (_) { /* already gone */ }
  dragId = null;
}
phone.addEventListener('pointerup', endDrag);
phone.addEventListener('pointercancel', endDrag);

/* ------------------------------------------------------------------------ */
/* Mobile: device orientation                                                 */
/* ------------------------------------------------------------------------ */

const motionBtn = $('motion-btn');
const motionMsg = $('motion-msg');
const hint = $('hint');

const hasOrientation = typeof window.DeviceOrientationEvent !== 'undefined';
const needsPermission =
  hasOrientation && typeof window.DeviceOrientationEvent.requestPermission === 'function';

if (hasOrientation) motionBtn.hidden = false;

let motionOn = false;
let gotSample = false;

// ALGORITHM.md §2 — one-pole smoothing, k ~ 0.3 at 60 Hz. Without it the
// picture shimmers while the phone sits still.
const SMOOTHING_K = 0.3;
let smoothedRoll = null;

function onOrientation(event) {
  // gamma is the left/right tilt in degrees. It is null on desktops and on a
  // few mobile browsers that fire the event without a real sensor behind it.
  const gamma = event.gamma;
  if (gamma === null || gamma === undefined || !Number.isFinite(gamma)) {
    if (!gotSample) {
      showMotionMessage('This browser fired an orientation event with no tilt data. Using the drag control instead.');
      stopMotion('motion');
    }
    return;
  }
  if (!gotSample) {
    gotSample = true;
    hint.textContent = 'Tilt the device left and right.';
  }
  smoothedRoll =
    smoothedRoll === null ? gamma : smoothedRoll * (1 - SMOOTHING_K) + gamma * SMOOTHING_K;
  applyRoll(smoothedRoll, 'motion');
}

function startMotion() {
  if (motionOn) return;
  motionOn = true;
  gotSample = false;
  smoothedRoll = null;
  window.addEventListener('deviceorientation', onOrientation);
  motionBtn.textContent = 'Stop device tilt';
  motionBtn.dataset.on = '1';
  motionMsg.hidden = true;

  // If nothing arrives within a second there is no usable sensor here.
  setTimeout(() => {
    if (motionOn && !gotSample) {
      showMotionMessage('No tilt data from this device. Drag the phone or use the slider.');
      stopMotion('motion');
    }
  }, 1200);
}

function stopMotion(source) {
  if (!motionOn) return;
  motionOn = false;
  window.removeEventListener('deviceorientation', onOrientation);
  motionBtn.textContent = 'Use device tilt';
  delete motionBtn.dataset.on;
  if (source === 'drag' || source === 'slider') hint.textContent = defaultHint();
}

function showMotionMessage(text) {
  motionMsg.textContent = text;
  motionMsg.hidden = false;
}

function defaultHint() {
  return 'Drag left and right across the phone to tilt it.';
}

motionBtn.addEventListener('click', () => {
  if (motionOn) {
    stopMotion('button');
    return;
  }
  if (needsPermission) {
    // iOS 13+ only grants this from inside a user gesture.
    window.DeviceOrientationEvent.requestPermission()
      .then((response) => {
        if (response === 'granted') {
          startMotion();
        } else {
          showMotionMessage(
            window.isSecureContext
              ? 'Motion access was denied. You can re-enable it in Settings › Safari › Motion & Orientation Access. ' +
                'Meanwhile, drag across the phone or use the slider below.'
              : 'Motion access needs a secure (https) origin, so this page cannot use it here. ' +
                'Drag across the phone or use the slider below.'
          );
        }
      })
      .catch(() => {
        showMotionMessage(
          'Motion access could not be requested (this usually needs a secure https origin). ' +
          'Drag across the phone or use the slider below.'
        );
      });
  } else {
    startMotion();
  }
});

/* ------------------------------------------------------------------------ */
/* Tuning sliders                                                             */
/* ------------------------------------------------------------------------ */

const tuners = [
  { id: 'blackAt', format: (v) => `${v.toFixed(0)}°` },
  { id: 'softness', format: (v) => v.toFixed(2) },
  { id: 'eyeDistance', format: (v) => v.toFixed(1) },
];

for (const { id, format } of tuners) {
  const input = $(id);
  const out = $(`${id}-out`);
  const sync = () => {
    const v = parseFloat(input.value);
    out.textContent = format(v);
    tf.setOptions({ [id]: v });
    updateReadout(currentRoll);
  };
  input.addEventListener('input', sync);
  sync();
}

/* ------------------------------------------------------------------------ */
/* Bring your own picture — entirely client side                              */
/* ------------------------------------------------------------------------ */

const fileInput = $('file');
let objectUrl = null;

fileInput.addEventListener('change', () => {
  const file = fileInput.files && fileInput.files[0];
  if (!file) return;
  if (objectUrl) URL.revokeObjectURL(objectUrl);
  objectUrl = URL.createObjectURL(file);
  tf.setImage(objectUrl);
});

$('reset').addEventListener('click', () => {
  if (objectUrl) {
    URL.revokeObjectURL(objectUrl);
    objectUrl = null;
  }
  fileInput.value = '';
  tf.setImage('assets/sample.jpg');
  for (const { id } of tuners) {
    const input = $(id);
    input.value = input.defaultValue;
    input.dispatchEvent(new Event('input'));
  }
  stopMotion('slider');
  applyRoll(0, 'reset');
});

window.addEventListener('pagehide', () => {
  if (objectUrl) URL.revokeObjectURL(objectUrl);
});

/* ------------------------------------------------------------------------ */

applyRoll(0, 'init');
