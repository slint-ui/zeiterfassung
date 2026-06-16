const pad = (value: number) => String(value).padStart(2, "0");

const format = (hours: number, minutes: number) =>
  `${pad(hours)}:${pad(minutes)}`;

/**
 * Normalizes a user-entered duration string into the canonical `HH:mm` form, so the leading
 * zero no longer has to be typed by hand.
 *
 * Accepted inputs (examples):
 * - `"8"` → `"08:00"` (hours only)
 * - `"830"` → `"08:30"` (digits only, the last two digits are the minutes)
 * - `"8:30"` / `"08:30"` → `"08:30"`
 * - `":30"` / `"030"` → `"00:30"`
 * - `"8,5"` / `"8.5"` → `"08:30"` (decimal hours, comma or dot)
 *
 * Anything that cannot be interpreted is returned unchanged, leaving the server-side
 * validation to reject it.
 */
export function normalizeDuration(raw: string): string {
  const s = (raw ?? "").trim();
  if (s === "") {
    return "";
  }

  const hasColon = s.includes(":");
  const hasDecimal = s.includes(",") || s.includes(".");

  // decimal hours, e.g. "8,5" or "8.5" → 08:30
  if (hasDecimal && !hasColon) {
    const hours = Number.parseFloat(s.replace(",", "."));
    if (Number.isNaN(hours) || hours < 0) {
      return s;
    }
    const totalMinutes = Math.round(hours * 60);
    return format(Math.floor(totalMinutes / 60), totalMinutes % 60);
  }

  // explicit colon, e.g. "8:30", "8:3", ":30"
  if (hasColon) {
    const parts = s.split(":");
    if (parts.length !== 2) {
      return s;
    }
    const h = parts[0] === "" ? "0" : parts[0];
    const m = parts[1] === "" ? "0" : parts[1];
    if (!/^\d{1,2}$/.test(h) || !/^\d{1,2}$/.test(m)) {
      return s;
    }
    const minutes = Number.parseInt(m, 10);
    if (minutes > 59) {
      return s;
    }
    return format(Number.parseInt(h, 10), minutes);
  }

  // digits only, e.g. "8", "830", "1230"
  if (/^\d{1,4}$/.test(s)) {
    let h: number;
    let m: number;
    if (s.length <= 2) {
      h = Number.parseInt(s, 10);
      m = 0;
    } else {
      h = Number.parseInt(s.slice(0, -2), 10);
      m = Number.parseInt(s.slice(-2), 10);
    }
    if (m > 59) {
      return s;
    }
    return format(h, m);
  }

  return s;
}

export class TimeEntryDurationInput extends HTMLInputElement {
  #cleanup = () => {};

  connectedCallback() {
    const normalize = () => {
      const normalized = normalizeDuration(this.value);
      if (normalized !== this.value) {
        this.value = normalized;
      }
    };

    // Normalize once the user leaves the field. The server normalizes too, so the value is
    // canonical even if the form is submitted without the field ever losing focus.
    this.addEventListener("blur", normalize);

    this.#cleanup = () => {
      this.removeEventListener("blur", normalize);
    };
  }

  disconnectedCallback() {
    this.#cleanup();
  }
}

customElements.define("z-time-entry-duration-input", TimeEntryDurationInput, {
  extends: "input",
});
