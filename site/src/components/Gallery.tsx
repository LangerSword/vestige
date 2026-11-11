const SHOTS: Array<{ src: string; alt: string; caption: string; meta: string }> = [
  {
    src: "./img/desktop-app-destroyed.png",
    alt: "The desktop app after a file was destroyed, showing the nine plain-language stages and the proof panel",
    caption:
      "After a destruction. The nine stages come from the transaction itself, not from a progress bar someone animated.",
    meta: "the real window · real transaction",
  },
  {
    src: "./img/desktop-app-verified.png",
    alt: "The same desktop app after the user pressed Check my history, showing the record verified against its signatures",
    caption:
      "After “Check my history”. The record verifies against its signatures and the witness co-signature.",
    meta: "offline verification",
  },
];

export function Gallery() {
  return (
    <section className="section section--tight" id="app">
      <div className="shell">
        <div className="section-head reveal">
          <h2>One file. One button. Plain language.</h2>
          <p>
            The desktop app hides the machinery without hiding the limits: the proof panel
            says what could not be checked, in the same breath as what could.
          </p>
        </div>

        <div className="shots">
          {SHOTS.map((s) => (
            <figure className="shot reveal" key={s.src}>
              <div className="shot__frame">
                <div className="shot__bar" aria-hidden="true">
                  <span />
                  <span />
                  <span />
                  <em>jvd-desktop</em>
                </div>
                <img src={s.src} alt={s.alt} loading="lazy" decoding="async" width={760} height={683} />
              </div>
              <figcaption>
                <span className="shot__meta">{s.meta}</span>
                {s.caption}
              </figcaption>
            </figure>
          ))}
        </div>

        <p className="shots__run">
          Run it with <code>./bin/jvd-desktop</code>. The app needs a display; without one it
          says so and points at the command line instead of printing a stack trace.
        </p>
      </div>
    </section>
  );
}
