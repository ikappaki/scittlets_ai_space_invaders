# GAME CONSTRAINTS REFERENCE

## 🚨 **CRITICAL DEVELOPMENT CONSTRAINTS**

### **❌ NEVER Use println for Debugging**
- **STRICT RULE**: No `println`, `console.log`, or debug logging in game code
- **Required**: Return diagnostic data as maps, use `clojure_eval` with proper returns
- **Rationale**: Performance, production readiness, React integration

### **📐 Fixed Dimensions & Positioning**
- **Game Size**: 796×408 pixels (accounts for 4px borders)
- **Player Position**: Y=358 (50px from bottom)
- **Barrier Position**: Y=280 (maintains 30px gap to player)
- **Boundary Logic**: Player 0-756, invaders reverse at 796
- **Critical**: No visual overflow on any platform

### **👾 Authentic Sprites**
- **Alien Emoji**: Must use 👾 (authentic Space Invaders feel)
- **Sprite Dimensions**: 30×20 pixels (original arcade proportions)
- **Font Size**: 22px optimized for containers

### **📱 Cross-Platform Performance**
- **Frame Rate Compensation**: 2x movement speed on mobile (30fps) vs desktop (60fps)
- **Mobile Detection**: Comprehensive device detection system
- **Consistent Gameplay**: Identical feel across all platforms

## 🎮 **GAME DYNAMICS CONSTRAINTS**

### **Movement & Speed**
- **Player Speed**: Consistent across platforms (2x on mobile for 30fps compensation)
- **Bullet Speed**: 6 pixels/frame (balanced for gameplay)
- **Invader Speed**: 0.5+ pixels/frame continuous movement (no jerky steps)
- **Smooth Motion**: All movement must be frame-by-frame, no discrete jumps

### **Collision & Physics**
- **One Life Loss Per Frame**: Anti-double-loss system prevents multiple collisions in single frame
- **Precise Hit Detection**: Pixel-perfect collision boundaries
- **Invader Direction**: Reverse at exact screen boundaries (796px)

### **Timing & Balance**
- **30px Barrier Gap**: Critical for authentic arcade defensive gameplay
- **Frame Rate Independence**: Identical gameplay feel on 30fps mobile vs 60fps desktop
- **Invader Progression**: Speed increases with level, maintains challenge

### **Audio & Feedback**
- **Distinct Sound Frequencies**: Each action has unique audio signature
- **Player Death Sequence**: 2-second pause, explosion, respawn with 3-second invulnerability
- **Immediate Audio Feedback**: All actions trigger instant sound response

## 🎯 **GAMEPLAY CONSTRAINTS**

### **Core Mechanics**
- **3 Lives Maximum**: Player starts with 3, loses 1 per hit, game over at 0
- **Single Shot Limit**: Player can only have 1 bullet on screen at a time
- **Invader Formation**: Must maintain grid formation and move as a unit
- **Barrier Destruction**: Barriers must be destructible by both player and invader bullets

### **Movement Rules**
- **Player Horizontal Only**: Cannot move vertically, confined to bottom area
- **Invader Pattern**: Side-to-side movement, drop down when hitting edges
- **Bullet Straight Lines**: All bullets move in straight vertical lines only

### **Victory/Defeat Conditions**
- **Win Level**: Eliminate ALL invaders to advance
- **Lose Game**: Invaders reach player level OR player loses all lives
- **Progressive Difficulty**: Each level increases invader speed

### **Authentic Space Invaders Rules**
- **No Power-ups**: Classic gameplay without modern enhancements
- **Fixed Firing Rate**: Limited bullet frequency (anti-spam)
- **Screen Boundaries**: Nothing can move beyond game area edges
- **Mystery UFO**: Occasional bonus ship for extra points

### **Visual Constraints**
- **Grid Alignment**: Invaders must stay in formation
- **Authentic Sprites**: Must use 👾 emoji maintaining retro feel
- **Screen Shake**: Visual feedback for impacts and destruction

### **Lives & Progression**
- **3-Life System**: Proper sequential life loss (3→2→1→Game Over)
- **Player Respawn Position**: When killed, player must respawn at middle of bottom row (X=380, Y=358)
- **Level Progression**: Invader speed increases, maintains difficulty curve
- **Authentic Scoring**: Original Space Invaders point values maintained

## 🔧 **TECHNICAL CONSTRAINTS**

### **Code Quality**
- **No Debug Artifacts**: Production-ready code only
- **Function Order**: Proper dependency order to prevent symbol resolution errors
- **Clean Architecture**: Single function definitions, no duplicates

### **Performance Requirements**
- **Mobile Optimization**: 30fps stable performance
- **Memory Management**: Particle limits, selective processing
- **Battery Efficiency**: Optimized for mobile devices

### **Compatibility**
- **Universal Browser Support**: Works across all modern browsers
- **Touch Controls**: Functional mobile/tablet controls
- **Responsive Design**: Adapts to all screen sizes while maintaining gameplay

---

**Purpose**: This document serves as the definitive reference for all constraints that must be maintained when modifying the Space Invaders game. Any changes must preserve these constraints to maintain authentic gameplay and cross-platform compatibility.
