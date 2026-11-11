import { useEffect, useRef } from "react";
import { Nav } from "./components/Nav";
import { Hero } from "./components/Hero";
import { Proof } from "./components/Proof";
import { Shred } from "./components/Shred";
import { Pipeline } from "./components/Pipeline";
import { Measurements } from "./components/Measurements";
import { Tamper } from "./components/Tamper";
import { Evidence } from "./components/Evidence";
import { Ladder } from "./components/Ladder";
import { Limits } from "./components/Limits";
import { Gallery } from "./components/Gallery";
import { Architecture } from "./components/Architecture";
import { Download } from "./components/Download";
import { Footer } from "./components/Footer";
import { setupReveals } from "./lib/motion";

export function App() {
  const root = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!root.current) return;
    return setupReveals(root.current);
  }, []);

  return (
    <div ref={root}>
      <div className="grain" aria-hidden="true" />

      <a
        href="#how"
        className="skip"
        onFocus={(e) => {
          e.currentTarget.classList.add("is-visible");
        }}
        onBlur={(e) => {
          e.currentTarget.classList.remove("is-visible");
        }}
      >
        Skip to content
      </a>

      <Nav />
      <main>
        <Hero />
        <Proof />
        <Shred />
        <Pipeline />
        <Measurements />
        <Tamper />
        <Evidence />
        <Ladder />
        <Limits />
        <Gallery />
        <Architecture />
        <Download />
      </main>
      <Footer />
    </div>
  );
}
