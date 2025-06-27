(ns space-invaders
  (:require [reagent.core :as r]
            [reagent.dom :as dom]))

;; Constants
(def game-width 796)
(def game-height 600)
(def player-width 40)
(def player-height 30)
(def invader-width 30)
(def invader-height 20)
(def bullet-width 8)
(def bullet-height 20)
(def bullet-speed 6)
(def invader-speed 0.1)

;; Enhanced game state with lives, levels, and effects
;; State with lives system
;; State with lives system
(defonce game-state
  (r/atom
   {:player {:x 380 :y 550}
    :bullets []
    :invader-bullets []
    :invaders []
    :explosions []
    :particles []
    :score 0
    :lives 3
    :level 1
    :game-over false
    :screen-shake false
    :frame 0
    :next-bullet-id 0
    :next-invader-bullet-id 0
    :last-fire-frame -10
    :invader-direction 1
    :invader-move-timer 0
    :invader-drop-distance 20
    :invader-shoot-timer 0
    :invader-shoot-interval 120
    :ufo nil ;; UFO mystery ship (nil when not active)
    :ufo-spawn-timer 0 ;; Timer for UFO spawning
    :ufo-spawn-interval 1200 ;; Frames between UFO spawns (~20 seconds at 60fps)
    :bullets-fired-this-level 0
    :keys-pressed #{}
    :barriers []
    :is-mobile (< (.-innerWidth js/window) 768) ;; Mobile detection
    :frame-skip 0 ;; Mobile frame skipping for performance
    :reduced-effects false})) ;; Mobile reduced effects mode

;; Audio System
 ;; Audio System  
(defonce audio-context (atom nil))

(defn init-audio []
  (when (and js/window (.-AudioContext js/window))
    (try
      (when (not @audio-context)
        (let [ctx (js/AudioContext.)]
          (reset! audio-context ctx)
          (comment "Audio context initialized successfully")
          (comment "Audio context state:" (.-state ctx))))
      ;; Resume context if it's suspended (Chrome autoplay policy)
      (when @audio-context
        (when (= (.-state @audio-context) "suspended")
          (.resume @audio-context)
          (comment "Audio context resumed")))
      (catch js/Error e
        (comment "Audio initialization failed:" e)))))

(defn play-sound [frequency duration]
  (when @audio-context
    (try
      ;; Resume context if suspended (required by some browsers)
      (when (= (.-state @audio-context) "suspended")
        (.resume @audio-context))

      (let [ctx @audio-context
            oscillator (.createOscillator ctx)
            gain (.createGain ctx)]
        (.connect oscillator gain)
        (.connect gain (.-destination ctx))
        (aset oscillator "frequency" "value" frequency)
        (aset gain "gain" "value" 0.15) ;; Slightly louder
        (.start oscillator)
        (.stop oscillator (+ (.-currentTime ctx) duration))
        (comment (str "Playing sound: " frequency "Hz for " duration "s")))
      (catch js/Error e
        (comment "Sound playback failed:" e)))))

(defn play-shoot-sound []
  (play-sound 440 0.1))

(defn play-hit-sound []
  (play-sound 220 0.2))

(defn play-explosion-sound []
  (play-sound 110 0.3))

(defn calculate-heartbeat-interval [invader-count]
  "Calculate heartbeat interval based on remaining invaders - fewer invaders = faster heartbeat"
  (let [base-interval 60 ; Base interval in frames (60 fps = 1 second)
        min-interval 15 ; Fastest heartbeat (quarter second)
        max-invaders 55] ; Total invaders at start
    (max min-interval
         (int (* base-interval (/ invader-count max-invaders))))))

(defn play-heartbeat-sound []
  "Play the iconic Space Invaders heartbeat - low frequency thump"
  (when @audio-context
    (try
      (when (= (.-state @audio-context) "suspended")
        (.resume @audio-context))

      (let [ctx @audio-context
            oscillator (.createOscillator ctx)
            gain (.createGain ctx)
            filter (.createBiquadFilter ctx)]

        ;; Connect audio chain: oscillator -> filter -> gain -> destination
        (.connect oscillator filter)
        (.connect filter gain)
        (.connect gain (.-destination ctx))

        ;; Configure oscillator for deep thump
        (aset oscillator "frequency" "value" 80) ; Very low frequency
        (aset oscillator "type" "sawtooth")

        ;; Configure filter for muffled effect
        (aset filter "type" "lowpass")
        (aset filter "frequency" "value" 250)

        ;; Configure envelope for sharp attack and quick decay
        (let [now (.-currentTime ctx)
              attack-time 0.01
              decay-time 0.15]
          (aset gain "gain" "value" 0)
          (.setValueAtTime (.-gain gain) 0 now)
          (.linearRampToValueAtTime (.-gain gain) 0.8 (+ now attack-time))
          (.exponentialRampToValueAtTime (.-gain gain) 0.01 (+ now decay-time))

          (.start oscillator now)
          (.stop oscillator (+ now 0.15))
          (comment "💗 Heartbeat played!")))

      (catch js/Error e
        (comment "Heartbeat sound error:" e)))))

(defn should-play-heartbeat? [state]
  "Determine if it's time to play the heartbeat based on game rhythm"
  (let [invader-count (count (:invaders state))
        heartbeat-interval (calculate-heartbeat-interval invader-count)
        frame (:frame state)
        should-play (and (> invader-count 0) ; Only when invaders remain
                         (not (:game-over state)) ; Not during game over
                         (= (mod frame heartbeat-interval) 0))] ; On rhythm
    should-play))

(defn test-heartbeat []
  "Test function to manually trigger heartbeat sound for volume testing"
  (comment "🔊 Testing heartbeat sound...")
  (play-heartbeat-sound)) ; On rhythm

(defn play-hit-sound []
  (play-sound 220 0.2))

(defn play-explosion-sound []
  (play-sound 110 0.3))

;; Game logic

;; Visual effects
(defn create-particle [x y]
  {:x x
   :y y
   :vx (- (rand 4) 2)
   :vy (- (rand 4) 2)
   :life 30
   :id (random-uuid)})

(defn create-mobile-particle [x y]
  "Lighter particle for mobile performance"
  {:x x
   :y y
   :vx (- (rand 2) 1) ;; Reduced movement
   :vy (- (rand 2) 1)
   :life 15 ;; Shorter life
   :id (random-uuid)})

(defn create-particles-for-platform [x y count]
  "Create appropriate number of particles based on platform"
  (let [state @game-state
        is-mobile (:is-mobile state)
        particle-count (if is-mobile (max 1 (/ count 3)) count) ;; 1/3 particles on mobile
        create-fn (if is-mobile create-mobile-particle create-particle)]
    (repeatedly particle-count #(create-fn x y))))

(defn create-explosion [x y]
  {:x x :y y :frame 0 :id (random-uuid)})

(defn update-particles [state]
  (update state :particles
          (fn [particles]
            (->> particles
                 (map (fn [p] (-> p
                                  (update :x + (:vx p))
                                  (update :y + (:vy p))
                                  (update :life dec))))
                 (filter #(> (:life %) 0))))))

(defn update-explosions [state]
  (update state :explosions
          (fn [explosions]
            (->> explosions
                 (map #(update % :frame inc))
                 (filter #(< (:frame %) 15)))))) ;; Explosions last 15 frames

(defn add-screen-shake [state]
  (assoc state :screen-shake true))

(defn remove-screen-shake [state]
  (assoc state :screen-shake false))

(defn update-explosions [state]
  (update state :explosions
          (fn [explosions]
            (->> explosions
                 (map #(update % :frame inc))
                 (filter #(< (:frame %) 15)))))) ;; Explosions last 15 frames

;; Game logic
(defn create-invader [x y]
  {:x x :y y :id (random-uuid)})

(defn get-invader-row [invader]
  "Determine which row an invader is in based on Y position"
  (let [base-y 30
        row-height 35
        row (int (/ (- (:y invader) base-y) row-height))]
    (max 0 (min 4 row))))

(defn get-invader-score [invader]
  "Calculate score for destroying an invader based on authentic tiered scoring"
  (let [row (get-invader-row invader)]
    (case row
      0 30 ; Top row (small invaders)
      1 20 ; Second row (medium invaders)  
      2 20 ; Third row (medium invaders)
      3 10 ; Fourth row (large invaders)
      4 10 ; Bottom row (large invaders)
      10))) ; Default fallback

(defn create-invader [x y]
  {:x x :y y :id (random-uuid)})

(defn get-invader-row [invader]
  "Determine which row an invader is in based on Y position"
  (let [base-y 30
        row-height 35
        row (int (/ (- (:y invader) base-y) row-height))]
    (max 0 (min 4 row))))

(defn get-invader-score [invader]
  "Calculate score for destroying an invader based on authentic tiered scoring"
  (let [row (get-invader-row invader)]
    (case row
      0 30 ; Top row (small invaders)
      1 20 ; Second row (medium invaders)  
      2 20 ; Third row (medium invaders)
      3 10 ; Fourth row (large invaders)
      4 10 ; Bottom row (large invaders)
      10))) ; Default fallback

(defn initialize-invaders [level]
  "Create authentic 5x11 invader formation with proper spacing"
  (for [row (range 5)
        col (range 11)] ; Changed from 10 to 11 columns for authenticity
    (create-invader (+ 50 (* col 64)) (+ 30 (* row 35)))))

(defn initialize-invaders [level]
  "Create authentic 5x11 invader formation with proper spacing"
  (for [row (range 5)
        col (range 11)] ; Changed from 10 to 11 columns for authenticity
    (create-invader (+ 50 (* col 64)) (+ 30 (* row 35)))))

(defn fire-bullet [state]
  "Fire a bullet only if no bullets are currently on screen (authentic 1-bullet limit)"
  (let [current-frame (:frame state)
        last-fire (:last-fire-frame state)
        bullets (:bullets state)]
    (if (and (> (- current-frame last-fire) 10) ;; Debounce check
             (empty? bullets)) ;; AUTHENTIC: Only fire if no bullets on screen
      (let [player (:player state)
            bullet-x (+ (:x player) (/ player-width 2))
            bullet-y (- (:y player) 30)
            bullet-id (:next-bullet-id state)
            new-bullet {:x bullet-x :y bullet-y :id bullet-id}
            bullets-fired (inc (:bullets-fired-this-level state))]

        (try
          (play-shoot-sound) ;; Sound effect
          (catch js/Error e
            (comment "Audio error:" e)))
        (-> state
            (update :bullets conj new-bullet)
            (update :next-bullet-id inc)
            (assoc :last-fire-frame current-frame)
            (assoc :bullets-fired-this-level bullets-fired))) ;; Track bullets for UFO scoring
      (do

        state))))

 ;; UFO Mystery Ship System
(def ufo-width 40)
(def ufo-height 20)
(def ufo-speed 2)
(def ufo-y-position 50) ;; Fixed Y position at top of screen

(defn create-ufo [direction]
  "Create a UFO mystery ship that moves across the top of the screen"
  (let [start-x (if (= direction :left-to-right) -50 (+ game-width 10))
        speed (if (= direction :left-to-right) ufo-speed (- ufo-speed))]
    {:x start-x
     :y ufo-y-position
     :direction direction
     :speed speed
     :id (random-uuid)}))

(defn should-spawn-ufo? [state]
  "Check if it's time to spawn a UFO (authentic timing based on game state)"
  (and (nil? (:ufo state)) ;; No UFO currently active
       (not (empty? (:invaders state))) ;; Invaders still on screen
       (>= (:ufo-spawn-timer state) (:ufo-spawn-interval state)) ;; Timer ready
       (< (rand) 0.7))) ;; 70% chance when ready

(defn spawn-ufo [state]
  "Spawn a UFO mystery ship if conditions are met"
  (if (should-spawn-ufo? state)
    (let [direction (if (< (rand) 0.5) :left-to-right :right-to-left)
          new-ufo (create-ufo direction)]

      (-> state
          (assoc :ufo new-ufo)
          (assoc :ufo-spawn-timer 0))) ;; Reset spawn timer
    ;; Not spawning, just increment timer
    (update state :ufo-spawn-timer inc)))

(defn move-ufo [state]
  "Move the UFO across the screen and remove when off-screen"
  (if-let [ufo (:ufo state)]
    (let [new-x (+ (:x ufo) (:speed ufo))
          off-screen? (or (< new-x -60) (> new-x (+ game-width 20)))]
      (if off-screen?
        (do

          (assoc state :ufo nil)) ;; Remove UFO
        (assoc-in state [:ufo :x] new-x))) ;; Update position
    state))

(defn calculate-ufo-score [bullets-fired]
  "Calculate UFO score based on authentic Space Invaders algorithm"
  ;; Authentic scoring: 300 points for 23rd shot, then every 15th shot
  (cond
    (= bullets-fired 23) 300
    (and (> bullets-fired 23) (= (mod (- bullets-fired 23) 15) 0)) 300
    :else (rand-nth [50 100 150 300])))

 ;; Destructible Barriers System
(def barrier-width 80)
(def barrier-height 48)
(def barrier-block-size 8)
(def barrier-count 4)

(defn create-barrier-block [x y]
  "Create a single barrier block"
  {:x x
   :y y
   :destroyed false
   :id (random-uuid)})

(defn create-barrier [barrier-x barrier-y barrier-id]
  "Create a barrier with classic Space Invaders shape"
  (let [blocks-per-row (/ barrier-width barrier-block-size)
        blocks-per-col (/ barrier-height barrier-block-size)]
    {:id barrier-id
     :x barrier-x
     :y barrier-y
     :blocks (vec (for [row (range blocks-per-col)
                        col (range blocks-per-row)
                        :let [x (+ barrier-x (* col barrier-block-size))
                              y (+ barrier-y (* row barrier-block-size))
                              ;; Create classic barrier shape with openings at bottom
                              is-bottom-opening? (and (>= row 4) ;; Bottom 2 rows  
                                                      (or (and (>= col 3) (<= col 6)) ;; Center opening
                                                          (and (>= col 0) (<= col 1)) ;; Left opening
                                                          (and (>= col 8) (<= col 9)))) ;; Right opening
                              should-create? (not is-bottom-opening?)]
                        :when should-create?]
                    (create-barrier-block x y)))}))

(defn initialize-barriers []
  "Create 4 barriers evenly spaced across the screen"
  (let [barrier-spacing (/ (- game-width (* barrier-count barrier-width)) (inc barrier-count))
        barrier-y 430] ;; Position above player
    (vec (for [i (range barrier-count)
               :let [barrier-x (+ barrier-spacing (* i (+ barrier-width barrier-spacing)))]]
           (create-barrier barrier-x barrier-y i)))))

(defn damage-barrier-at [barriers x y damage-radius]
  "Damage barrier blocks within radius of impact point"
  (mapv (fn [barrier]
          (update barrier :blocks
                  (fn [blocks]
                    (mapv (fn [block]
                            (if (and (not (:destroyed block))
                                     (<= (+ (Math/pow (- (:x block) x) 2)
                                            (Math/pow (- (:y block) y) 2))
                                         (* damage-radius damage-radius)))
                              (assoc block :destroyed true)
                              block))
                          blocks))))
        barriers))

(defn check-bullet-barrier-collisions [bullets barriers]
  "Check if bullets hit barriers and damage them - WORKING VERSION"
  (let [results (atom {:bullets [] :barriers barriers :hits []})]

    (doseq [bullet bullets]
      (let [current-barriers (:barriers @results)
            hit-barrier-block (atom nil)]

        ;; Find which specific block was hit - use simple point-in-rectangle check
        (doseq [barrier current-barriers
                :when (nil? @hit-barrier-block)]
          (doseq [block (:blocks barrier)
                  :when (and (nil? @hit-barrier-block)
                             (not (:destroyed block)))]
            ;; Simple point collision check - bullet center vs block bounds
            (let [bullet-center-x (+ (:x bullet) 4) ; bullet center
                  bullet-center-y (+ (:y bullet) 4) ; bullet center
                  block-left (:x block)
                  block-right (+ (:x block) barrier-block-size)
                  block-top (:y block)
                  block-bottom (+ (:y block) barrier-block-size)]

              ;; Check if bullet center is inside block
              (when (and (>= bullet-center-x block-left)
                         (<= bullet-center-x block-right)
                         (>= bullet-center-y block-top)
                         (<= bullet-center-y block-bottom))
                (reset! hit-barrier-block {:barrier barrier :block block :bullet bullet})))))

        (if @hit-barrier-block
          ;; Bullet hit a barrier block - damage the barrier and remove bullet
          (let [hit-info @hit-barrier-block
                bullet-pos {:x (:x (:bullet hit-info)) :y (:y (:bullet hit-info))}]
            (comment (str "ðŸ’¥ BARRIER DAMAGED at " (:x bullet-pos) "," (:y bullet-pos)))
            (swap! results update :barriers damage-barrier-at (:x bullet-pos) (:y bullet-pos) 10)
            (swap! results update :hits conj bullet-pos))
          ;; Bullet missed all barriers - keep it
          (swap! results update :bullets conj bullet))))

    @results))

(defn process-barrier-collisions [state]
  "Process all bullet-barrier collisions for both player and invader bullets with effects"
  (let [player-bullets (:bullets state)
        invader-bullets (:invader-bullets state)
        barriers (:barriers state)]

    ;; Debug: Log when we have bullets to process
    (when (seq player-bullets)
      (comment (str "PROCESSING " (count player-bullets) " PLAYER bullets vs barriers")))
    (when (seq invader-bullets)
      (comment (str "PROCESSING " (count invader-bullets) " INVADER bullets vs barriers")))

    (let [player-result (check-bullet-barrier-collisions player-bullets barriers)
          surviving-player-bullets (:bullets player-result)
          barriers-after-player (:barriers player-result)
          player-hits (:hits player-result)

          ;; Check invader bullets vs barriers  
          invader-result (check-bullet-barrier-collisions invader-bullets barriers-after-player)
          surviving-invader-bullets (:bullets invader-result)
          final-barriers (:barriers invader-result)
          invader-hits (:hits invader-result)

          ;; Create particles and effects for all hits
          all-hits (concat player-hits invader-hits)
          ;; Create MORE particles for invader bullet hits to make them more visible
          player-particles (mapcat (fn [hit-pos]
                                     (repeatedly 4 #(create-particle (:x hit-pos) (:y hit-pos))))
                                   player-hits)
          invader-particles (mapcat (fn [hit-pos]
                                      (repeatedly 8 #(create-particle (:x hit-pos) (:y hit-pos))))
                                    invader-hits)
          all-particles (concat player-particles invader-particles)]

      ;; Debug: Log collision results
      (when (seq player-hits)
        (comment (str "✅ PLAYER BULLET HITS: " (count player-hits) " at positions: " player-hits)))
      (when (seq invader-hits)
        (comment (str "ðŸ”¥ INVADER BULLET HITS: " (count invader-hits) " at positions: " invader-hits)))

      ;; Play sound if any barriers were hit
      (when (seq all-hits)
        (try
          (play-hit-sound) ;; Barrier hit sound
          (catch js/Error e
            (comment "Audio error:" e))))

      (-> state
          (assoc :bullets surviving-player-bullets)
          (assoc :invader-bullets surviving-invader-bullets)
          (assoc :barriers final-barriers)
          (update :particles concat all-particles))))) ;; Random for other shots

;; Invader shooting system
(defn get-bottom-invaders [invaders]
  "Get the bottom-most invader in each column (only these can shoot)"
  (->> invaders
       (group-by :x) ;; Group by x position (column)
       (map (fn [[x column-invaders]]
              (apply max-key :y column-invaders))) ;; Get lowest (highest y) invader
       (filter identity)))

(defn create-invader-bullet [invader bullet-id]
  "Create a bullet fired by an invader"
  {:x (+ (:x invader) (/ invader-width 2))
   :y (+ (:y invader) invader-height)
   :id bullet-id})

(defn fire-invader-bullet [state]
  "Randomly fire bullets from bottom invaders"
  (let [timer (:invader-shoot-timer state)
        interval (max 60 (- (:invader-shoot-interval state)
                            (* 10 (dec (:level state))))) ;; Faster shooting each level
        bottom-invaders (get-bottom-invaders (:invaders state))]

    (if (and (>= timer interval)
             (not (empty? bottom-invaders))
             (< (rand) 0.3)) ;; 30% chance to shoot when timer is ready
      ;; Fire a bullet from random bottom invader
      (let [shooter (rand-nth bottom-invaders)
            bullet-id (:next-invader-bullet-id state)
            new-bullet (create-invader-bullet shooter bullet-id)]
        (comment (str "Invader fires bullet " bullet-id " at " (:x new-bullet) "," (:y new-bullet)))
        (try
          (play-hit-sound) ;; Different sound for invader shooting
          (catch js/Error e
            (comment "Audio error:" e)))
        (-> state
            (update :invader-bullets conj new-bullet)
            (update :next-invader-bullet-id inc)
            (assoc :invader-shoot-timer 0)))

      ;; No shooting, just increment timer
      (update state :invader-shoot-timer inc))))

(defn move-invader-bullets [state]
  "Move invader bullets downward and remove off-screen ones"
  (update state :invader-bullets
          (fn [bullets]
            (let [;; Compensate for mobile 30fps vs desktop 60fps
                  frame-rate-multiplier (if (:is-mobile state) 2.0 1.0)
                  invader-bullet-speed (* 5 frame-rate-multiplier)]
              (->> bullets
                   (map #(update % :y + invader-bullet-speed))
                   (filter #(< (:y %) game-height))))))) ;; Remove off-screen bullets 

(defn move-invaders [state]
  (let [;; Smooth continuous movement - small steps every frame
        base-speed 0.5 ;; Base speed in pixels per frame
        level-multiplier (+ 1 (* 0.2 (dec (:level state)))) ;; Slight speed increase per level
        ;; Compensate for mobile 30fps vs desktop 60fps
        frame-rate-multiplier (if (:is-mobile state) 2.0 1.0) ;; 2x speed on mobile to compensate for 30fps
        move-speed (* base-speed level-multiplier frame-rate-multiplier)
        direction (:invader-direction state)
        invaders (:invaders state)]

    ;; Move invaders every frame for smooth movement
    (let [;; Calculate new horizontal positions with smooth movement
          new-invaders (map #(update % :x + (* direction move-speed)) invaders)

          ;; Check if any invader hit the screen edge
          leftmost-x (apply min (map :x new-invaders))
          rightmost-x (apply max (map #(+ (:x %) invader-width) new-invaders))
          hit-edge? (or (<= leftmost-x 0)
                        (>= rightmost-x game-width))]

      (if hit-edge?
        ;; Hit edge: drop down and reverse direction
        (do
          (try
            (play-hit-sound) ;; Sound when invaders change direction
            (catch js/Error e
              (comment "Audio error:" e)))
          (-> state
              (assoc :invaders (map #(update % :y + (:invader-drop-distance state)) invaders))
              (update :invader-direction -) ;; Reverse direction
              add-screen-shake))

        ;; Normal horizontal movement - smooth every frame
        (-> state
            (assoc :invaders new-invaders))))))

(defn move-bullets [state]
  (let [old-bullets (:bullets state)
        ;; Compensate for mobile 30fps vs desktop 60fps
        frame-rate-multiplier (if (:is-mobile state) 2.0 1.0)
        effective-bullet-speed (* bullet-speed frame-rate-multiplier)
        new-bullets (->> old-bullets
                         (map #(update % :y - effective-bullet-speed))
                         (filter #(> (:y %) 0)))]
    (assoc state :bullets new-bullets)))

;; Collision detection
(defn rectangles-collide? [bullet invader]
  (and (< (:x bullet) (+ (:x invader) invader-width))
       (> (+ (:x bullet) bullet-width) (:x invader))
       (< (:y bullet) (+ (:y invader) invader-height))
       (> (+ (:y bullet) bullet-height) (:y invader))))

(defn check-bullet-invader-collisions [state]
  (let [bullets (:bullets state)
        invaders (:invaders state)]

    ;; Debug: Log when checking bullet-invader collisions
    (when (seq bullets)
      (comment (str "Checking " (count bullets) " bullets vs " (count invaders) " invaders")))

    (loop [remaining-bullets bullets
           remaining-invaders invaders
           surviving-bullets []
           new-explosions []
           new-particles []
           score-increase 0]
      (if (empty? remaining-bullets)
        (-> state
            (assoc :bullets surviving-bullets)
            (assoc :invaders remaining-invaders)
            (update :explosions concat new-explosions)
            (update :particles concat new-particles)
            (update :score + score-increase)
            add-screen-shake)
        (let [bullet (first remaining-bullets)
              hit-invader (first (filter #(rectangles-collide? bullet %) remaining-invaders))]
          (if hit-invader
            (let [invader-score (get-invader-score hit-invader)
                  row (get-invader-row hit-invader)]
              (comment (str "AUTHENTIC SCORING! Hit invader in row " row " for " invader-score " points"))
              (try
                (play-hit-sound)
                (catch js/Error e
                  (comment "Audio error:" e)))
              ;; Bullet hit invader - remove both, add effects with authentic scoring
              (recur (rest remaining-bullets)
                     (remove #{hit-invader} remaining-invaders)
                     surviving-bullets ;; Don't add this bullet to surviving
                     (conj new-explosions (create-explosion (:x hit-invader) (:y hit-invader)))
                     (concat new-particles (create-particles-for-platform (:x hit-invader) (:y hit-invader) 8))
                     (+ score-increase invader-score))) ; Use authentic tiered scoring
            ;; Bullet missed invaders - keep bullet
            (recur (rest remaining-bullets)
                   remaining-invaders
                   (conj surviving-bullets bullet)
                   new-explosions
                   new-particles
                   score-increase)))))))

(defn check-bullet-ufo-collision [state]
  "Check if player bullet hits UFO and award points"
  (if-let [ufo (:ufo state)]
    (let [bullets (:bullets state)
          hit-bullet (first (filter #(rectangles-collide? % ufo) bullets))]
      (if hit-bullet
        (let [ufo-score (calculate-ufo-score (:bullets-fired-this-level state))]
          (comment (str "UFO HIT! Score: " ufo-score " points (bullet #" (:bullets-fired-this-level state) ")"))
          (try
            (play-explosion-sound) ;; UFO hit sound
            (catch js/Error e
              (comment "Audio error:" e)))
          (-> state
              (assoc :ufo nil) ;; Remove UFO
              (update :bullets #(remove #{hit-bullet} %)) ;; Remove bullet
              (update :score + ufo-score) ;; Add score
              (update :explosions conj (create-explosion (:x ufo) (:y ufo))) ;; Explosion
              (update :particles concat (create-particles-for-platform (+ (:x ufo) 20) (+ (:y ufo) 10) 12)) ;; More particles
              add-screen-shake))
        state))
    state))

 ;; Enhanced collision system with barriers

;; Player collision detection
(defn check-invader-bullet-player-collisions [state]
  "Check if invader bullets hit the player"
  (let [player (:player state)
        invader-bullets (:invader-bullets state)
        hit-bullets (filter #(rectangles-collide? % player) invader-bullets)]

    (if (not (empty? hit-bullets))
      ;; Player was hit!
      (do
        (comment (str "Player hit by invader bullet! Lives: " (dec (:lives state))))
        (try
          (play-explosion-sound) ;; Player hit sound
          (catch js/Error e
            (comment "Audio error:" e)))
        (if (> (:lives state) 1)
          ;; Lose a life, reset level
          (-> state
              (update :lives dec)
              (assoc :invader-bullets []) ;; Clear all invader bullets
              (assoc :bullets []) ;; Clear player bullets
              (assoc :invaders (initialize-invaders (:level state)))
              (assoc :barriers (initialize-barriers)) ;; Reset barriers
              (assoc :invader-direction 1)
              (assoc :invader-move-timer 0)
              (assoc :invader-shoot-timer 0)
              add-screen-shake)
          ;; Game over
          (do
            (comment "Game Over! Player destroyed!")
            (assoc state :game-over true))))

      ;; No collision, just remove any bullets that hit
      (update state :invader-bullets
              (fn [bullets]
                (remove #(rectangles-collide? % player) bullets))))))

(defn check-level-completion [state]
  (if (empty? (:invaders state))
    (do
      (comment (str "Level " (:level state) " complete! All 55 invaders destroyed with authentic scoring!"))
      (try
        (play-explosion-sound) ;; Victory sound
        (catch js/Error e
          (comment "Audio error:" e)))
      (-> state
          (update :level inc)
          (assoc :invaders (initialize-invaders (inc (:level state))))
          (update :score + (* 100 (:level state))) ;; Level completion bonus
          (assoc :invader-direction 1) ;; Reset movement direction
          (assoc :invader-move-timer 0) ;; Reset movement timer
          (assoc :bullets-fired-this-level 0))) ;; Reset bullet count for UFO scoring
    state))

(defn check-invader-barrier-collisions [state]
  "Check if invaders hit barriers and damage them"
  (let [invaders (:invaders state)
        barriers (:barriers state)
        collisions (atom [])]

    ;; Check each invader against each barrier
    (doseq [invader invaders]
      (doseq [barrier barriers]
        (doseq [block (:blocks barrier)]
          (when (and (not (:destroyed block))
                     ;; Fixed collision detection - use >= for boundary edges
                     (<= (:x invader) (+ (:x block) barrier-block-size))
                     (>= (+ (:x invader) invader-width) (:x block))
                     (<= (:y invader) (+ (:y block) barrier-block-size))
                     (>= (+ (:y invader) invader-height) (:y block)))
            ;; Collision detected! Store it
            (swap! collisions conj {:x (:x block) :y (:y block)})))))

    (if (seq @collisions)
      ;; Process all collisions
      (let [updated-barriers (reduce (fn [barriers collision]
                                       (damage-barrier-at barriers
                                                          (:x collision)
                                                          (:y collision)
                                                          15)) ;; Larger damage radius for invaders
                                     barriers
                                     @collisions)
            ;; Create particles for visual feedback
            new-particles (mapcat (fn [collision]
                                    (create-particles-for-platform (:x collision) (:y collision) 6))
                                  @collisions)]

        ;; Play sound effect
        (try
          (play-hit-sound)
          (catch js/Error e
            (comment "Audio error:" e)))

        (-> state
            (assoc :barriers updated-barriers)
            (update :particles concat new-particles)
            add-screen-shake))

      ;; No collisions
      state)))

(defn check-player-collision [state]
  (let [invaders (:invaders state)
        player (:player state)]
    (cond
      (some #(>= (:y %) (- (:y player) 50)) invaders)
      (if (> (:lives state) 1)
        (do
          (comment (str "Lost a life! Lives remaining: " (dec (:lives state))))
          (try
            (play-explosion-sound) ;; Death sound
            (catch js/Error e
              (comment "Audio error:" e)))
          (-> state
              (update :lives dec)
              (assoc :invaders (initialize-invaders (:level state)))
              (assoc :bullets [])
              (assoc :barriers (initialize-barriers)) ;; Reset barriers - FIXED!
              (assoc :invader-direction 1) ;; Reset movement direction
              (assoc :invader-move-timer 0))) ;; Reset movement timer
        (do
          (comment "Game Over! No lives remaining!")
          (assoc state :game-over true)))

      :else state)))

(defn move-player
  ([state direction]
   ;; Single keypress movement (larger steps)
   (let [player (:player state)
         new-x (case direction
                 :left (max 0 (- (:x player) 12))
                 :right (min (- game-width player-width) (+ (:x player) 12))
                 (:x player))]
     (assoc-in state [:player :x] new-x)))

  ([state direction speed]
   ;; Continuous movement (smaller steps per frame)
   (let [player (:player state)
         new-x (case direction
                 :left-continuous (max 0 (- (:x player) speed))
                 :right-continuous (min (- game-width player-width) (+ (:x player) speed))
                 (:x player))]
     (assoc-in state [:player :x] new-x))))

(defn process-continuous-movement [state]
  "Process continuous movement while keys are held down"
  (let [keys (:keys-pressed state)
        ;; Compensate for mobile 30fps vs desktop 60fps
        frame-rate-multiplier (if (:is-mobile state) 2.0 1.0)
        move-speed (* 4 frame-rate-multiplier)] ; Pixels per frame for continuous movement
    (cond
      (contains? keys "ArrowLeft")
      (move-player state :left-continuous move-speed)

      (contains? keys "ArrowRight")
      (move-player state :right-continuous move-speed)

      :else state)))

(defn handle-key-down [key]
  "Handle key press events - add keys to pressed set for continuous movement"
  (case key
    "ArrowLeft" (swap! game-state assoc :keys-pressed
                       (conj (or (:keys-pressed @game-state) #{}) key))
    "ArrowRight" (swap! game-state assoc :keys-pressed
                        (conj (or (:keys-pressed @game-state) #{}) key))
    " " (do
          (swap! game-state fire-bullet))
    ;; Default case - ignore unknown keys
    nil))

(defn handle-key-up [key]
  "Handle key release events - remove keys from pressed set"
  (case key
    "ArrowLeft" (swap! game-state assoc :keys-pressed
                       (disj (or (:keys-pressed @game-state) #{}) key))
    "ArrowRight" (swap! game-state assoc :keys-pressed
                        (disj (or (:keys-pressed @game-state) #{}) key))
    ;; Default case - ignore unknown keys
    nil))

(defn setup-global-key-listeners []
  "Setup global keyboard event listeners for continuous movement"
  (.addEventListener js/document "keydown"
                     (fn [e]
                       (when-not (:game-over @game-state)
                         (.preventDefault e) ; Prevent scrolling, etc.
                         (handle-key-down (.-key e)))))
  (.addEventListener js/document "keyup"
                     (fn [e]
                       (when-not (:game-over @game-state)
                         (handle-key-up (.-key e))))))

(defn remove-global-key-listeners []
  "Remove global keyboard event listeners"
  (.removeEventListener js/document "keydown" handle-key-down)
  (.removeEventListener js/document "keyup" handle-key-up))

(defn move-player
  ([state direction]
   ;; Single keypress movement (larger steps)
   (let [player (:player state)
         new-x (case direction
                 :left (max 0 (- (:x player) 12))
                 :right (min (- game-width player-width) (+ (:x player) 12))
                 (:x player))]
     (assoc-in state [:player :x] new-x)))

  ([state direction speed]
   ;; Continuous movement (smaller steps per frame)
   (let [player (:player state)
         new-x (case direction
                 :left-continuous (max 0 (- (:x player) speed))
                 :right-continuous (min (- game-width player-width) (+ (:x player) speed))
                 (:x player))]
     (assoc-in state [:player :x] new-x))))

(defn update-game [state]
  (if (:game-over state)
    state
    (let [is-mobile (:is-mobile state)]
      (if is-mobile
        ;; Mobile: Optimized update with reduced processing for non-critical systems
        (let [frame (:frame state)
              ;; Process heavy visual effects every other frame, but keep gameplay smooth
              process-visual-effects (= (mod frame 2) 0)]
          (-> state
              (update :frame inc)
              ;; Always process ALL essential gameplay for smooth experience
              process-continuous-movement
              move-bullets
              move-invader-bullets
              move-invaders ;; Always process invader movement
              fire-invader-bullet ;; Always process invader shooting
              spawn-ufo ;; Always process UFO spawning
              move-ufo ;; Always process UFO movement
              process-barrier-collisions ;; Always process collisions
              check-bullet-invader-collisions
              check-bullet-ufo-collision
              check-invader-bullet-player-collisions
              check-level-completion
              check-invader-barrier-collisions
              check-player-collision

              ;; Only optimize visual effects processing
              (#(if process-visual-effects
                  (-> % update-explosions update-particles)
                  %))

              ;; Limit particles on mobile for memory management
              (#(if (> (count (:particles %)) 20)
                  (assoc % :particles (take 15 (:particles %)))
                  %))

              ;; Play heartbeat less frequently on mobile (every 4th frame)
              (#(do (when (and (should-play-heartbeat? %)
                               (= (mod frame 4) 0))
                      (play-heartbeat-sound))
                    %))))

        ;; Desktop: Full 60 FPS performance - no optimizations
        (-> state
            (update :frame inc)
            ;; Play heartbeat audio on rhythm
            (#(do (when (should-play-heartbeat? %)
                    (play-heartbeat-sound))
                  %))
            process-continuous-movement
            move-bullets
            move-invader-bullets
            move-invaders
            fire-invader-bullet
            spawn-ufo ;; UFO: Check if UFO should spawn
            move-ufo ;; UFO: Move existing UFO
            process-barrier-collisions ;; BARRIERS: Process bullet-barrier collisions first
            check-bullet-invader-collisions ;; Player bullets vs invaders  
            check-bullet-ufo-collision ;; UFO: Player bullets vs UFO
            check-invader-bullet-player-collisions ;; Invader bullets vs player
            update-explosions
            update-particles
            check-level-completion
            check-invader-barrier-collisions
            check-player-collision
            ;; Remove screen shake after a few frames (enabled on desktop)
            (#(if (and (:screen-shake %) (> (mod (:frame %) 10) 5))
                (remove-screen-shake %)
                %)))))))

;; Game loop with adaptive frame rate for mobile performance
(defonce game-loop-id (atom nil))
(defonce mobile-device (atom false))

(defn detect-mobile-device []
  "Detect if device is mobile/touch-based for performance optimization"
  (let [user-agent (.-userAgent js/navigator)
        has-touch (> (.-maxTouchPoints js/navigator) 0)
        pointer-coarse (and (.matchMedia js/window "(pointer: coarse)")
                            (.-matches (.matchMedia js/window "(pointer: coarse)")))
        ;; Only consider screen size for mobile if we also have touch capabilities
        small-screen-with-touch (and has-touch (< (.-innerWidth js/window) 768))
        ;; Check user agent for mobile indicators as additional verification
        mobile-user-agent (or (.includes user-agent "Mobile")
                              (.includes user-agent "Android")
                              (.includes user-agent "iPhone")
                              (.includes user-agent "iPad"))]
    (reset! mobile-device (and (or has-touch pointer-coarse mobile-user-agent)
                               (or small-screen-with-touch pointer-coarse mobile-user-agent)))
    (comment (str "📱 Device detection: Mobile=" @mobile-device
                  " | Touch=" has-touch
                  " | PointerCoarse=" pointer-coarse
                  " | SmallScreen=" (< (.-innerWidth js/window) 768)
                  " | MobileUA=" mobile-user-agent))
    @mobile-device))

(defn game-loop []
  "Game loop with adaptive frame rate - 30fps mobile, 60fps desktop"
  (let [is-mobile @mobile-device]
    (swap! game-state update-game)
    (if is-mobile
      ;; Mobile: 30fps for stable performance (33ms interval)
      (reset! game-loop-id (js/setTimeout game-loop 33))
      ;; Desktop: 60fps for smooth gameplay
      (reset! game-loop-id (js/requestAnimationFrame game-loop)))))

(defn start-game []
  (comment "🚀 AUTHENTIC SPACE INVADERS - 5×11 Formation with Tiered Scoring!")
  (comment "💯 Features: 55 invaders, 10/20/30 point scoring, UFO mystery ships, destructible barriers")
  (comment "🎵 NEW: Authentic heartbeat audio that speeds up as invaders are destroyed!")

  ;; Detect device type for performance optimization
  (let [is-mobile (detect-mobile-device)]
    (when is-mobile
      (comment "📱 MOBILE MODE: 30fps + performance optimizations enabled"))

    (init-audio) ;; Initialize sound
    (reset! game-state {:player {:x 380 :y 550}
                        :bullets []
                        :invader-bullets []
                        :invaders (initialize-invaders 1)
                        :explosions []
                        :particles []
                        :score 0
                        :lives 3
                        :level 1
                        :game-over false
                        :screen-shake false
                        :frame 0
                        :next-bullet-id 0
                        :next-invader-bullet-id 0
                        :last-fire-frame -10
                        :invader-direction 1
                        :invader-move-timer 0
                        :invader-drop-distance 20
                        :invader-shoot-timer 0
                        :invader-shoot-interval 120
                        :ufo nil ;; UFO mystery ship
                        :ufo-spawn-timer 0 ;; UFO spawn timer
                        :ufo-spawn-interval 1200 ;; UFO spawn interval
                        :bullets-fired-this-level 0
                        :barriers (initialize-barriers)
                        :keys-pressed #{}
                        :is-mobile is-mobile ;; Use the sophisticated mobile detection
                        :frame-skip 0 ;; Mobile frame skipping
                        :reduced-effects is-mobile}) ;; Enable reduced effects on mobile

    ;; Stop any existing game loop
    (when @game-loop-id
      (if is-mobile
        (js/clearTimeout @game-loop-id)
        (js/cancelAnimationFrame @game-loop-id)))

    (setup-global-key-listeners)
    (game-loop)))

;; Enhanced Visual Components
;; Enhanced Components with visual improvements
;; Components with improved visuals
;; Enhanced Visual Components with advanced effects
(defn player-component []
  (let [player (:player @game-state)
        keys-pressed (:keys-pressed @game-state)
        is-moving (or (contains? keys-pressed "ArrowLeft")
                      (contains? keys-pressed "ArrowRight"))]
    [:div {:style {:position "absolute"
                   :left (:x player)
                   :top (:y player)
                   :width player-width
                   :height player-height
                   :background "linear-gradient(45deg, #00ff00, #00aa00)"
                   :border "2px solid #00ffff"
                   :border-radius "8px 8px 2px 2px"
                   :box-shadow (if is-moving
                                 "0 0 25px #00ff00, inset 0 0 15px rgba(255,255,255,0.4)"
                                 "0 0 20px #00ff00, inset 0 0 10px rgba(255,255,255,0.3)")
                   ;; Remove flickering animation during movement
                   :animation (when-not is-moving "playerPulse 2s ease-in-out infinite alternate")
                   :transition "box-shadow 0.2s ease"
                   :z-index 10}}
     ;; Cockpit window
     [:div {:style {:width "12px" :height "8px"
                    :background "rgba(255,255,255,0.8)"
                    :margin "8px auto 0"
                    :border-radius "2px"
                    :border "1px solid #00ffff"}}]
     ;; Engine glow - more stable, less flickering
     [:div {:style {:position "absolute"
                    :bottom "-5px"
                    :left "50%"
                    :transform "translateX(-50%)"
                    :width "6px"
                    :height "8px"
                    :background (if is-moving
                                  "linear-gradient(180deg, #00ff00, #ffff00)"
                                  "linear-gradient(180deg, #00ff00, transparent)")
                    :border-radius "50%"
                    :opacity (if is-moving 1 0.7)
                    :transition "background 0.2s ease, opacity 0.2s ease"
                    ;; Remove the flickering bulletGlow animation
                    :box-shadow (when is-moving "0 0 8px #00ff00")}}]]))

(defn bullet-component [bullet]
  [:div {:style {:position "absolute"
                 :left (:x bullet)
                 :top (:y bullet)
                 :width bullet-width
                 :height bullet-height
                 :background "linear-gradient(0deg, #ffff00, #ffaa00)"
                 :border "1px solid #fff"
                 :border-radius "50%"
                 :box-shadow "0 0 15px #ffff00"
                 :animation "bulletGlow 0.5s ease-in-out infinite alternate"
                 :z-index 5}}
   ;; Bullet trail effect
   [:div {:style {:position "absolute"
                  :top "100%"
                  :left "50%"
                  :transform "translateX(-50%)"
                  :width "2px"
                  :height "20px"
                  :background "linear-gradient(180deg, rgba(255,255,0,0.8), transparent)"
                  :border-radius "50%"}}]])

(defn invader-bullet-component [bullet]
  [:div {:style {:position "absolute"
                 :left (:x bullet)
                 :top (:y bullet)
                 :width bullet-width
                 :height bullet-height
                 :background "linear-gradient(180deg, #ff0000, #aa0000)" ;; Red for enemy bullets
                 :border "1px solid #fff"
                 :border-radius "50%"
                 :box-shadow "0 0 15px #ff0000"
                 :animation "bulletGlow 0.5s ease-in-out infinite alternate"
                 :z-index 5}}
   ;; Invader bullet trail effect (downward)
   [:div {:style {:position "absolute"
                  :top "-20px" ;; Trail goes upward from bullet
                  :left "50%"
                  :transform "translateX(-50%)"
                  :width "2px"
                  :height "20px"
                  :background "linear-gradient(0deg, rgba(255,0,0,0.8), transparent)"
                  :border-radius "50%"}}]])

(defn ufo-component [ufo]
  "UFO Mystery Ship component with classic design and glow effects"
  [:div {:style {:position "absolute"
                 :left (:x ufo)
                 :top (:y ufo)
                 :width ufo-width
                 :height ufo-height
                 :background "linear-gradient(45deg, #ff00ff, #aa00aa)"
                 :border "2px solid #ffff00"
                 :border-radius "50% 50% 20% 20%"
                 :box-shadow "0 0 25px #ff00ff, inset 0 0 15px rgba(255,255,255,0.4)"
                 :animation "pulse 1.5s infinite"
                 :z-index 6}}
   ;; UFO dome/cockpit
   [:div {:style {:position "absolute"
                  :top "2px"
                  :left "50%"
                  :transform "translateX(-50%)"
                  :width "24px"
                  :height "8px"
                  :background "linear-gradient(45deg, #ffff00, #ffaa00)"
                  :border "1px solid #fff"
                  :border-radius "50%"
                  :box-shadow "0 0 8px #ffff00"}}]
   ;; UFO lights
   [:div {:style {:position "absolute"
                  :bottom "2px"
                  :left "6px"
                  :width "4px"
                  :height "4px"
                  :background "#00ffff"
                  :border-radius "50%"
                  :box-shadow "0 0 6px #00ffff"
                  :animation "bulletGlow 0.8s infinite alternate"}}]
   [:div {:style {:position "absolute"
                  :bottom "2px"
                  :right "6px"
                  :width "4px"
                  :height "4px"
                  :background "#00ffff"
                  :border-radius "50%"
                  :box-shadow "0 0 6px #00ffff"
                  :animation "bulletGlow 0.8s infinite alternate"}}]])

(defn barrier-component [barrier]
  "Destructible barrier component with pixel-perfect blocks"
  [:div {:key (:id barrier)
         :style {:position "absolute"
                 :left (:x barrier)
                 :top (:y barrier)
                 :width barrier-width
                 :height barrier-height
                 :z-index 3}}
   ;; Render each undestroyed block of the barrier
   (for [block (:blocks barrier)
         :when (not (:destroyed block))]
     [:div {:key (:id block)
            :style {:position "absolute"
                    :left (- (:x block) (:x barrier)) ;; Relative to barrier position
                    :top (- (:y block) (:y barrier))
                    :width barrier-block-size
                    :height barrier-block-size
                    :background "linear-gradient(45deg, #00ff00, #00aa00)"
                    :border "1px solid #00ffff"
                    :border-radius "1px"
                    :box-shadow "0 0 4px rgba(0,255,0,0.3)"
                    :z-index 3}}])])

(defn invader-component [invader]
  (let [pulse-scale (+ 1 (* 0.05 (Math/sin (/ (:y invader) 20))))]
    [:div {:style {:position "absolute"
                   :left (:x invader)
                   :top (:y invader)
                   :width invader-width
                   :height invader-height
                   :background "linear-gradient(45deg, #ff0000, #cc0000)"
                   :border "2px solid #fff"
                   :border-radius "4px"
                   :box-shadow "0 0 15px #ff0000"
                   :animation "pulse 2s infinite"
                   :transform (str "scale(" pulse-scale ")")
                   :z-index 5}}
     ;; Invader eyes
     [:div {:style {:display "flex"
                    :justify-content "space-around"
                    :margin-top "3px"}}
      [:div {:style {:width "3px" :height "3px"
                     :background "#fff" :border-radius "50%"}}]
      [:div {:style {:width "3px" :height "3px"
                     :background "#fff" :border-radius "50%"}}]]
     ;; Antenna
     [:div {:style {:position "absolute"
                    :top "-4px"
                    :left "50%"
                    :transform "translateX(-50%)"
                    :width "2px"
                    :height "6px"
                    :background "linear-gradient(180deg, #fff, #ff0000)"}}]]))

(defn explosion-component [explosion]
  (let [frame (:frame explosion)
        size (+ 20 (* frame 4))
        opacity (max 0 (- 1 (/ frame 15)))]
    [:div {:key (:id explosion)
           :style {:position "absolute"
                   :left (- (:x explosion) (/ size 2))
                   :top (- (:y explosion) (/ size 2))
                   :width size
                   :height size
                   :background "radial-gradient(circle, #ffff00 0%, #ff6600 30%, #ff0000 60%, transparent 100%)"
                   :border-radius "50%"
                   :opacity opacity
                   :animation "explosion 0.8s ease-out"
                   :z-index 15}}]))

(defn particle-component [particle]
  (let [opacity (/ (:life particle) 30)]
    [:div {:key (:id particle)
           :style {:position "absolute"
                   :left (:x particle)
                   :top (:y particle)
                   :width "3px"
                   :height "3px"
                   :background "#ffff00"
                   :border-radius "50%"
                   :opacity opacity
                   :box-shadow "0 0 6px #ffff00"
                   :z-index 8}}]))

(defn lives-display [lives]
  [:div {:style {:display "flex" :align-items "center" :margin-right "20px"}}
   [:span {:style {:margin-right "10px" :color "#00ffff" :font-weight "bold"}} "LIVES:"]
   (for [i (range lives)]
     [:div {:key i
            :style {:width "25px"
                    :height "18px"
                    :background "linear-gradient(45deg, #00ff00, #00aa00)"
                    :border "1px solid #00ffff"
                    :border-radius "4px"
                    :margin-left "3px"
                    :box-shadow "0 0 8px #00ff00"
                    :animation "playerPulse 2s ease-in-out infinite alternate"}}])])

;; Gun barrel

(defn app []
  (let [state @game-state
        shake-class (when (:screen-shake state) "screen-shake")]
    [:div {:style {:background-color "transparent" :min-height "auto" :padding "0"}}
     [:div {:class shake-class
            :style {:transition "transform 0.1s ease"}}

      ;; Enhanced HUD
      [:div {:class "mobile-header"
             :style {:display "flex"
                     :justify-content "space-between"
                     :align-items "center"
                     :padding "15px 30px"
                     :background "linear-gradient(90deg, rgba(0,255,255,0.1), rgba(0,255,255,0.3), rgba(0,255,255,0.1))"
                     :border-bottom "2px solid #00ffff"
                     :box-shadow "0 0 20px rgba(0,255,255,0.5)"
                     :width "100%"
                     :box-sizing "border-box"
                     :overflow "visible"}}
       [:div {:style {:display "flex" :flex-direction "column" :align-items "flex-start" :min-width "300px"}}
        [:h1 {:class "score-display"
              :style {:color "#00ffff"
                      :margin "0"
                      :font-size "28px"
                      :text-shadow "0 0 15px #00ffff"
                      :white-space "nowrap"
                      :line-height "1.1"}}
         "SPACE INVADERS"]
        [:div {:class "subtitle"
               :style {:color "#ffaa00"
                       :font-size "12px"
                       :font-weight "bold"
                       :text-shadow "0 0 8px #ffaa00"
                       :margin-top "2px"
                       :margin-left "0"
                       :white-space "nowrap"}}
         "A "
         [:a {:href "https://babashka.org/scittle/"
              :target "_blank"
              :rel "noopener noreferrer"
              :style {:color "#00ffff"
                      :text-decoration "none"
                      :text-shadow "0 0 8px #00ffff"
                      :cursor "pointer"
                      :white-space "nowrap"
                      :display "inline"
                      :border-bottom "1px solid #00ffff"
                      :padding-bottom "1px"}}
          "Scittle"]
         " game built with "
         [:a {:href "https://ikappaki.github.io/scittlets/"
              :target "_blank"
              :rel "noopener noreferrer"
              :style {:color "#00ffff"
                      :text-decoration "none"
                      :text-shadow "0 0 8px #00ffff"
                      :cursor "pointer"
                      :white-space "nowrap"
                      :display "inline"
                      :border-bottom "1px solid #00ffff"
                      :padding-bottom "1px"}}
          "Scittlets"]
         " and "
         [:a {:href "https://github.com/bhauman/clojure-mcp"
              :target "_blank"
              :rel "noopener noreferrer"
              :style {:color "#00ffff"
                      :text-decoration "none"
                      :text-shadow "0 0 8px #00ffff"
                      :cursor "pointer"
                      :white-space "nowrap"
                      :display "inline"
                      :border-bottom "1px solid #00ffff"
                      :padding-bottom "1px"}}
          "Clojure MCP"]]]

       [:div {:class "mobile-stats"
              :style {:display "flex" :align-items "center" :gap "30px"}}
        [:div {:style {:color "#ffff00" :font-size "18px" :font-weight "bold"}}
         "LEVEL " (:level state)]
        [:div {:class "score-display"
               :style {:color "#00ff00" :font-size "20px" :font-weight "bold"}}
         "SCORE: " (:score state)]
        [:div {:class "mobile-lives"}
         [lives-display (:lives state)]]
        ;; Audio test button
        [:div {:class "audio-controls"
               :style {:display "flex" :gap "5px"}}
         [:button {:style {:padding "5px 10px"
                           :font-size "12px"
                           :background "#444"
                           :color "#fff"
                           :border "1px solid #00ffff"
                           :border-radius "3px"
                           :cursor "pointer"}
                   :on-click #(do
                                (init-audio)
                                (js/setTimeout play-shoot-sound 100))}
          "🔊 SHOT"]
         [:button {:style {:padding "5px 10px"
                           :font-size "12px"
                           :background "#600"
                           :color "#fff"
                           :border "1px solid #ff6666"
                           :border-radius "3px"
                           :cursor "pointer"}
                   :on-click #(do
                                (init-audio)
                                (js/setTimeout test-heartbeat 100))}
          "💗 HEART"]]]]

      ;; Debug panel (smaller, less intrusive)
      ;; Instructions panel (top left)
      [:div {:style {:position "absolute"
                     :top "80px"
                     :left "10px"
                     :color "#888"
                     :font-family "monospace"
                     :font-size "10px"
                     :background "rgba(0,0,0,0.8)"
                     :padding "8px"
                     :border-radius "3px"
                     :border "1px solid #333"
                     :z-index 100
                     :max-width "150px"}}
       [:div {:style {:color "#00ffff"
                      :font-size "9px"
                      :font-weight "bold"
                      :margin-bottom "4px"}}
        "🎮 CONTROLS"]
       [:div {:style {:font-size "9px" :line-height "1.3"}}
        "← → Move" [:br]
        "SPACE Fire" [:br]
        "Click to start audio"]]

      ;; Debug panel (smaller, less intrusive)
      [:div {:style {:position "absolute"
                     :top "80px"
                     :right "10px"
                     :color "#666"
                     :font-family "monospace"
                     :font-size "9px"
                     :background "rgba(0,0,0,0.7)"
                     :padding "4px"
                     :border-radius "3px"
                     :z-index 100
                     :max-width "200px"}}
       [:div {:style {:margin-bottom "2px"
                      :color "#888"
                      :font-size "8px"}}
        "Live REPL development in browser"]
       [:div {:style {:font-size "8px"}}
        "F:" (:frame state) " PB:" (count (:bullets state)) " IB:" (count (:invader-bullets state)) " I:" (count (:invaders state)) " E:" (count (:explosions state))]
       [:div {:style {:font-size "8px"}}
        "Audio: " (if @audio-context
                    (if (= (.-state @audio-context) "running") "🔊" "⚠️")
                    "⚪")
        " | Dir: " (if (= (:invader-direction state) 1) "→" "←")
        " | ST: " (:invader-shoot-timer state)]]

      ;; Game over overlay with enhanced styling
      (when (:game-over state)
        [:div {:class "game-over-overlay"
               :style {:position "fixed"
                       :top 0
                       :left 0
                       :width "100%"
                       :height "100%"
                       :background "radial-gradient(circle, rgba(255,0,0,0.8), rgba(0,0,0,0.9))"
                       :display "flex"
                       :flex-direction "column"
                       :justify-content "center"
                       :align-items "center"
                       :color "#fff"
                       :font-family "Courier New"
                       :z-index 1000
                       :animation "fadeInOut 3s infinite"}}
         [:h1 {:style {:font-size "64px"
                       :margin "20px"
                       :text-shadow "0 0 30px #ff0000"
                       :animation "pulse 2s infinite"}}
          "GAME OVER"]
         [:p {:style {:font-size "28px" :color "#00ffff"}}
          "LEVEL REACHED: " (:level state)]
         [:p {:style {:font-size "32px" :color "#ffff00"}}
          "FINAL SCORE: " (:score state)]
         [:button {:class "neon-button"
                   :style {:padding "20px 40px"
                           :font-size "20px"
                           :background "linear-gradient(45deg, #00ff00, #00aa00)"
                           :color "#000"
                           :border "2px solid #00ffff"
                           :border-radius "8px"
                           :cursor "pointer"
                           :margin-top "30px"
                           :font-weight "bold"
                           :text-transform "uppercase"
                           :box-shadow "0 0 20px #00ff00"}
                   :on-click #(do (init-audio) (start-game))}
          "PLAY AGAIN"]])

      ;; Main game area
      [:div {:class "game-area"
             :style {:position "relative"
                     :width game-width
                     :height game-height
                     :background-color "#000"
                     :border "4px solid #00ffff"
                     :box-shadow "0 0 30px #00ffff, inset 0 0 30px rgba(0,255,255,0.1)"
                     :margin "20px auto"
                     :overflow "hidden"}
             :tab-index 0
             :on-key-down (fn [e]
                            ;; Key handling now done via global listeners for better responsiveness
                            nil)
             :on-click (fn [e]
                         (.focus (.-target e))
                         (init-audio))}

       ;; Grid overlay effect
       [:div {:style {:position "absolute"
                      :top 0
                      :left 0
                      :right 0
                      :bottom 0
                      :background "linear-gradient(90deg, transparent 49%, rgba(0,255,255,0.05) 50%, transparent 51%), linear-gradient(0deg, transparent 49%, rgba(0,255,255,0.05) 50%, transparent 51%)"
                      :background-size "50px 50px"
                      :pointer-events "none"
                      :z-index 1}}]

       ;; Game entities
       [player-component]

       ;; Render player bullets with enhanced effects
       (for [bullet (:bullets state)]
         ^{:key (:id bullet)}
         [bullet-component bullet])

       ;; Render invader bullets
       (for [bullet (:invader-bullets state)]
         ^{:key (str "inv-" (:id bullet))}
         [invader-bullet-component bullet])

       ;; Render UFO mystery ship (if active)
       (when-let [ufo (:ufo state)]
         ^{:key (:id ufo)}
         [ufo-component ufo])

       ;; Render barriers (destructible cover)
       (for [barrier (:barriers state)]
         ^{:key (:id barrier)}
         [barrier-component barrier])

       ;; Render invaders with enhanced effects
       (for [invader (:invaders state)]
         ^{:key (:id invader)}
         [invader-component invader])

       ;; Render explosions
       (for [explosion (:explosions state)]
         ^{:key (:id explosion)}
         [explosion-component explosion])

       ;; Render particles
       (for [particle (:particles state)]
         ^{:key (:id particle)}
         [particle-component particle])]]]))

;; Init
 ;; Mobile Touch Controls Component (rendered separately to avoid scaling)
(defn mobile-controls []
  [:div {:class "mobile-controls"}
   ;; Left side movement controls
   [:div {:class "movement-controls"}
    [:div {:class "touch-button"
           :on-touch-start (fn [e]
                             (.preventDefault e)
                             (.stopPropagation e)
                             (handle-key-down "ArrowLeft"))
           :on-touch-end (fn [e]
                           (.preventDefault e)
                           (.stopPropagation e)
                           (handle-key-up "ArrowLeft"))
           :on-touch-cancel (fn [e]
                              (.preventDefault e)
                              (.stopPropagation e)
                              (handle-key-up "ArrowLeft"))
           :on-mouse-down (fn [e]
                            (.preventDefault e)
                            (.stopPropagation e)
                            (handle-key-down "ArrowLeft"))
           :on-mouse-up (fn [e]
                          (.preventDefault e)
                          (.stopPropagation e)
                          (handle-key-up "ArrowLeft"))
           :on-mouse-leave (fn [e]
                             (.preventDefault e)
                             (.stopPropagation e)
                             (handle-key-up "ArrowLeft"))}
     "←"]
    [:div {:class "touch-button"
           :on-touch-start (fn [e]
                             (.preventDefault e)
                             (.stopPropagation e)
                             (handle-key-down "ArrowRight"))
           :on-touch-end (fn [e]
                           (.preventDefault e)
                           (.stopPropagation e)
                           (handle-key-up "ArrowRight"))
           :on-touch-cancel (fn [e]
                              (.preventDefault e)
                              (.stopPropagation e)
                              (handle-key-up "ArrowRight"))
           :on-mouse-down (fn [e]
                            (.preventDefault e)
                            (.stopPropagation e)
                            (handle-key-down "ArrowRight"))
           :on-mouse-up (fn [e]
                          (.preventDefault e)
                          (.stopPropagation e)
                          (handle-key-up "ArrowRight"))
           :on-mouse-leave (fn [e]
                             (.preventDefault e)
                             (.stopPropagation e)
                             (handle-key-up "ArrowRight"))}
     "→"]]

   ;; Right side fire control
   [:div {:class "shoot-control"}
    [:div {:class "touch-button"
           :on-touch-start (fn [e]
                             (.preventDefault e)
                             (.stopPropagation e)
                             (swap! game-state fire-bullet))
           :on-click (fn [e]
                       (.preventDefault e)
                       (.stopPropagation e)
                       (swap! game-state fire-bullet))}
     "FIRE"]]])

(defn init []
  (dom/render [app] (.getElementById js/document "app"))
  ;; Render mobile controls separately to avoid scaling
  (dom/render [mobile-controls] (.getElementById js/document "mobile-controls")))

(init)
(start-game)
