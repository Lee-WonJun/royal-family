"use client";
import { App, configureMapLoader } from "../generated/ui/main.js";
import { loadMapLibrary } from "./map-library.mjs";
configureMapLoader(loadMapLibrary);
export default function ClientApp() { return <App />; }
