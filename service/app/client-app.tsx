"use client";
import { App, configureMapLoader, configureRosterLoader } from "../generated/ui/main.js";
import { loadMapLibrary } from "./map-library.mjs";
import { loadRoster } from "./roster-loader";
configureMapLoader(loadMapLibrary);
configureRosterLoader(loadRoster);
export default function ClientApp() { return <App />; }
