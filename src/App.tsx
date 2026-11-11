import { Suspense, lazy, useEffect, useRef, useState } from "react";
import { Nav } from "./components/Nav";
// three.js stays in its own chunk; the page is readable without it
const Scene = lazy(() => import("./three/Scene").then((m) => ({ default: m.Scene })));
import { Cursor } from "./components/Cursor";
import { ChapterRail } from "./components/ChapterRail";
import { Decisions } from "./components/Decisions";
import { Marquee } from "./components/Marquee";
import { Hero } from "./components/Hero";
import { Proof } from "./components/Proof";
import { Shred } from "./components/Shred";
import { Pipeline } from "./components/Pipeline";
import { Measurements } from "./components/Measurements";
import { Tamper } from "./components/Tamper";
import { Gallery } from "./components/Gallery";
import { Reference, type RefTab } from "./components/Reference";
import { Download } from "./components/Download";
import { Footer } from "./components/Footer";
import { setupReveals } from "./lib/motion";

export function App() {
  const root = useRef<HTMLDivElement | null>(null);
  const [tab, setTab] = useState<RefTab>("evidence");

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

      <Suspense fallback={null}>
        <Scene />
      </Suspense>
      <Cursor />
      <ChapterRail />
      <Nav onTab={setTab} />
      <main>
        <Hero />
        <Proof />
        <Shred />
        <Marquee />
        <Decisions />
        <Pipeline />
        <Measurements />
        <Tamper />
        <Gallery />
        <Reference tab={tab} onTab={setTab} />
        <Download />
      </main>
      <Footer />
    </div>
  );
}
