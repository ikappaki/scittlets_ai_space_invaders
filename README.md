# Space Invaders Arcade Game - Built with [Scittlets](https://ikappaki.github.io/scittlets/) & AI via [Clojure MCP](https://github.com/bhauman/clojure-mcp)

**[Play the Live Demo](https://ikappaki.github.io/scittlets_ai_space_invaders/)**

This is a **complete, feature-rich Space Invaders game** - a [Scittle](https://babashka.org/scittle/) app using the **Scittlets ecosystem** and developed through **Clojure MCP AI-assisted collaboration**. Experience authentic Space Invaders gameplay with all classic arcade features, showcasing the power of modern ClojureScript development!

**From Reagent template to production game - all running in the browser with zero build step!**

## Files Overview:
1. **`index.html`** - Game entry point with enhanced CSS animations and starfield background
2. **`space_invaders.cljs`** - Complete game implementation
3. **`nrepl-server.clj`** - nREPL server for live REPL development (scittlets.dev.nrepl component)
4. **`README.md`** - This file - complete game documentation and instructions

## Quick Start:

### Setup:
1. **Start HTTP Server**: `npx serve -p 8080`
2. **Start nREPL Server**: `bb nrepl-server.clj` (optional, for live development)
3. **Open Game**: Navigate to `http://localhost:8080` in your web browser
4. **Initialize Audio**: Click anywhere to enable sound (browser requirement)
5. **Start Playing**: Game starts automatically!

### Controls:
- **←** and **→** arrow keys: Move the player ship left/right
- **SPACEBAR**: Fire bullets
- **Click anywhere**: Initialize audio system

## What's Included:

### Complete Game Features:
- **Authentic Space Invaders gameplay** with 50 invaders in formation
- **UFO mystery ship** with authentic arcade scoring
- **Destructible barriers** with pixel-perfect collision
- **Invader shooting system** with progressive difficulty
- **Lives & scoring system** with level progression
- **Complete audio system** using Web Audio API
- **Advanced visual effects** - particles, explosions, screen shake
- **Neon aesthetic** with animated starfield background

### Development Features:
- **Live REPL development** - connect to browser nREPL
- **Real-time code editing** while the game runs
- **Debug tools** with visual state inspection
- **No build step required** - pure browser development

## Technology Stack:

- **[Scittle](https://babashka.org/scittle/)**: Brings the Small Clojure Interpreter (SCI) to the browser, allowing you to run ClojureScript directly using simple `<script>` tags - no build step, no compilation, just pure ClojureScript in the browser!
- **[Scittlets](https://ikappaki.github.io/scittlets/)**: Catalog of ready-made components and starter templates for Scittle apps (short for "Scittle applets")
- **[Clojure MCP](https://github.com/bhauman/clojure-mcp)**: AI-assisted development tooling (enabled collaborative coding)
- **[Reagent](https://reagent-project.github.io/)**: ClojureScript React wrapper (from Reagent template)

## Start Playing Now!

Start a local server (see setup above) and open `http://localhost:8080` in your browser to experience the complete Space Invaders arcade experience! No build step required - everything runs directly in the browser thanks to the **Scittlets catalog of components and templates**.

**Result: A fully playable, authentic Space Invaders experience that rivals commercial implementations - built from a simple template!**

---

*Built with ❤️ using [Scittle](https://babashka.org/scittle/), [Scittlets](https://ikappaki.github.io/scittlets/) catalog, and [Clojure MCP](https://github.com/bhauman/clojure-mcp) AI-assisted development*
