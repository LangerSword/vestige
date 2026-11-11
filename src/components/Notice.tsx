/**
 * The honest status line. It sits above the footer so the last thing a reader
 * sees is that the page describes a moving target, not a finished product.
 */
export function Notice() {
  return (
    <section className="notice" aria-label="Project status">
      <div className="shell notice__inner">
        <span className="notice__tag">In development</span>
        <p>
          Vestige is a work in progress. Several limits on this page are the current work
          queue, and closing them will change the results, the assurance levels and this
          interface. The record format is the part we intend to keep stable.
        </p>
      </div>
    </section>
  );
}
