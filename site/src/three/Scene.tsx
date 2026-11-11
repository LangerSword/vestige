import { useEffect, useRef } from "react";
import * as THREE from "three";
import { prefersReduced } from "../lib/motion";

/**
 * The hero field: a slab of stored bytes that comes apart as you scroll.
 *
 * It is the product in one image — a dense, ordered block of data that stops
 * being ordered. Nothing here claims to be a simulation of storage; it is the
 * page's own metaphor, rendered with points and a tiny shader.
 */
export function Scene() {
  const host = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const mount = host.current;
    if (!mount) return;

    let renderer: THREE.WebGLRenderer;
    try {
      renderer = new THREE.WebGLRenderer({ antialias: false, alpha: true, powerPreference: "low-power" });
    } catch {
      return; // no WebGL: the CSS gradient underneath is the fallback
    }

    const reduced = prefersReduced();
    const dpr = Math.min(window.devicePixelRatio || 1, 2);

    renderer.setPixelRatio(dpr);
    renderer.setClearColor(0x000000, 0);
    mount.appendChild(renderer.domElement);
    renderer.domElement.style.cssText = "position:absolute;inset:0;width:100%;height:100%;display:block";

    const scene = new THREE.Scene();
    const camera = new THREE.PerspectiveCamera(55, 1, 0.1, 100);
    camera.position.set(0, 0, 6.1);

    const COLS = 96;
    const ROWS = 54;
    const count = COLS * ROWS;
    const positions = new Float32Array(count * 3);
    const seeds = new Float32Array(count);
    const blocks = new Float32Array(count);

    let i = 0;
    for (let y = 0; y < ROWS; y++) {
      for (let x = 0; x < COLS; x++) {
        const fx = (x / (COLS - 1) - 0.5) * 23;
        const fy = (y / (ROWS - 1) - 0.5) * 13;
        positions[i * 3] = fx;
        positions[i * 3 + 1] = fy;
        positions[i * 3 + 2] = Math.sin(x * 0.22) * 0.28 + Math.cos(y * 0.31) * 0.28;

        // Storage has structure: 6×6 sectors are either allocated (dense, bright)
        // or free (sparse, dim). Random dots read as stars; sectors read as data.
        const h = Math.sin(Math.floor(x / 6) * 12.9898 + Math.floor(y / 6) * 78.233) * 43758.5453;
        const v = h - Math.floor(h);
        blocks[i] = v < 0.46 ? 1 : 0;
        seeds[i] = Math.random();
        i++;
      }
    }

    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute("position", new THREE.BufferAttribute(positions, 3));
    geometry.setAttribute("aRand", new THREE.BufferAttribute(seeds, 1));
    geometry.setAttribute("aBlock", new THREE.BufferAttribute(blocks, 1));

    const material = new THREE.ShaderMaterial({
      transparent: true,
      depthWrite: false,
      blending: THREE.AdditiveBlending,
      uniforms: {
        uTime: { value: 0 },
        uProgress: { value: 0 },
        uPixel: { value: dpr },
        uGhost: { value: 0 },
        uPulse: { value: new THREE.Vector3(0, 0, 0) },
      },
      vertexShader: /* glsl */ `
        attribute float aRand;
        attribute float aBlock;
        uniform float uTime;
        uniform float uProgress;
        uniform float uPixel;
        uniform float uGhost;
        uniform vec3 uPulse;
        varying float vAlpha;
        varying float vSeed;

        void main() {
          vec3 p = position;

          // each point leaves at its own moment, so the block thins from the inside
          float t = clamp((uProgress - aRand * 0.45) / 0.35, 0.0, 1.0);

          float breathe = sin(uTime * 0.6 + aRand * 6.283) * 0.02;
          p.z += breathe;

          vec3 dir = vec3(sin(aRand * 61.7), cos(aRand * 37.3), sin(aRand * 23.1));
          p += dir * t * 3.4;
          p.y += t * t * 1.7;

          // click shockwave: the field is an object you can disturb
          float pd = distance(p.xy, uPulse.xy);
          float push = smoothstep(3.4, 0.0, pd) * uPulse.z;
          vec2 away = normalize(p.xy - uPulse.xy + vec2(0.001, 0.001));
          p.xy += away * push * 2.4;
          p.z += push * 2.0;

          // two passes over the same geometry: free sectors are drawn as a faint
          // ghost grid so the slab reads as a storage map, not a starfield
          float blockMix = mix(0.22, 1.0, aBlock);
          if (uGhost > 0.5) blockMix = mix(1.0, 0.02, aBlock);

          vAlpha = (1.0 - t) * (0.28 + aRand * 0.6) * blockMix * (1.0 - push * 0.9);
          vSeed = aRand;

          vec4 mv = modelViewMatrix * vec4(p, 1.0);
          gl_Position = projectionMatrix * mv;
          float sizeMix = uGhost > 0.5 ? 0.85 : mix(0.5, 1.45, aBlock);
          gl_PointSize = (2.4 + aRand * 2.6) * sizeMix * uPixel * (1.0 - t * 0.7) * (6.0 / -mv.z);
        }
      `,
      fragmentShader: /* glsl */ `
        varying float vAlpha;
        varying float vSeed;

        void main() {
          // square cells, not round dots: this is storage, not a starfield
          vec2 c = abs(gl_PointCoord - 0.5);
          float edge = max(c.x, c.y);
          float a = smoothstep(0.5, 0.40, edge) * vAlpha;
          if (a < 0.01) discard;
          vec3 ink = vec3(0.925, 0.914, 0.886);
          vec3 signal = vec3(0.282, 0.878, 0.553);
          vec3 col = mix(ink, signal, step(0.94, vSeed));
          // a few cells are always scrubbed, so the key describes the field
          if (vSeed > 0.988) col = vec3(1.0, 0.353, 0.302);
          gl_FragColor = vec4(col, a * 0.9);
        }
      `,
    });

    const points = new THREE.Points(geometry, material);

    // second pass: free sectors as a faint ghost grid, parented so it inherits rotation
    const ghostMaterial = material.clone();
    ghostMaterial.uniforms.uGhost.value = 1;
    const ghost = new THREE.Points(geometry, ghostMaterial);
    points.add(ghost);

    scene.add(points);

    let progress = 0;
    let target = 0;
    const pointer = { x: 0, y: 0 };
    const pointerTarget = { x: 0, y: 0 };

    const resize = () => {
      const rect = mount.getBoundingClientRect();
      const w = Math.max(1, rect.width);
      const h = Math.max(1, rect.height);
      renderer.setSize(w, h, false);
      camera.aspect = w / h;
      camera.updateProjectionMatrix();
      material.uniforms.uPixel.value = Math.min(window.devicePixelRatio || 1, 2) * (w < 700 ? 0.8 : 1);
    };

    const onScroll = () => {
      const span = Math.max(1, window.innerHeight * 1.15);
      target = Math.min(1, Math.max(0, window.scrollY / span));
    };

    const onPointer = (e: PointerEvent) => {
      pointerTarget.x = (e.clientX / window.innerWidth - 0.5) * 2;
      pointerTarget.y = (e.clientY / window.innerHeight - 0.5) * 2;
    };

    // project the click onto the plane the field lives on, then let it decay
    const onDown = (e: PointerEvent) => {
      const rect = mount.getBoundingClientRect();
      const ndcX = ((e.clientX - rect.left) / rect.width) * 2 - 1;
      const ndcY = -((((e.clientY - rect.top) / rect.height) * 2 - 1));
      const vFov = (camera.fov * Math.PI) / 180;
      const planeHeight = 2 * Math.tan(vFov / 2) * camera.position.z;
      const planeWidth = planeHeight * camera.aspect;
      material.uniforms.uPulse.value.set((ndcX * planeWidth) / 2, (ndcY * planeHeight) / 2, 1);
    };

    resize();
    window.addEventListener("resize", resize);
    window.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("pointermove", onPointer, { passive: true });
    mount.addEventListener("pointerdown", onDown, { passive: true });
    onScroll();

    let raf = 0;
    const t0 = performance.now();

    const frame = () => {
      const t = (performance.now() - t0) / 1000;
      progress += (target - progress) * 0.06;
      pointer.x += (pointerTarget.x - pointer.x) * 0.045;
      pointer.y += (pointerTarget.y - pointer.y) * 0.045;

      const pulse = material.uniforms.uPulse.value as THREE.Vector3;
      pulse.z *= 0.94;
      if (pulse.z < 0.01) pulse.z = 0;

      material.uniforms.uTime.value = t;
      material.uniforms.uProgress.value = progress;
      ghostMaterial.uniforms.uPulse.value.copy(pulse);
      ghostMaterial.uniforms.uTime.value = t;
      ghostMaterial.uniforms.uProgress.value = progress;
      ghostMaterial.uniforms.uPixel.value = material.uniforms.uPixel.value;

      points.rotation.y = pointer.x * 0.12 + Math.sin(t * 0.08) * 0.03;
      points.rotation.x = -pointer.y * 0.07;
      camera.position.z = 7.4 + progress * 1.2;

      renderer.render(scene, camera);
      raf = requestAnimationFrame(frame);
    };

    if (reduced) {
      material.uniforms.uProgress.value = 0.25;
      renderer.render(scene, camera);
    } else {
      frame();
    }

    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener("resize", resize);
      window.removeEventListener("scroll", onScroll);
      window.removeEventListener("pointermove", onPointer);
      mount.removeEventListener("pointerdown", onDown);
      geometry.dispose();
      material.dispose();
      ghostMaterial.dispose();
      renderer.dispose();
      if (renderer.domElement.parentNode === mount) mount.removeChild(renderer.domElement);
    };
  }, []);

  return <div ref={host} className="scene" data-cursor="erase" aria-hidden="true" />;
}
