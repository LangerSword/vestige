import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  // relative base so the same build works at a domain root, under a path,
  // behind a gateway, or on IPFS where the site is not served from "/"
  base: "./",
  plugins: [react()],
  build: {
    target: "es2022",
    assetsInlineLimit: 2048,
    rollupOptions: {
      output: {
        manualChunks: { three: ["three"] },
      },
    },
  },
});
