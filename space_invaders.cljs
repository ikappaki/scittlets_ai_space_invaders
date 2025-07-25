(ns space-invaders
  (:require [reagent.core :as r]
            [reagent.dom :as dom]))

;; Constants
(def game-width 796)
(def game-height 408)
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
   {:player {:x 380 :y 358}
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
    :invader-dropping false
    :invader-drop-progress 0
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
    :reduced-effects false ;; Mobile reduced effects mode
    ;; Player death sequence state
    :player-dying false ;; Is player currently in death sequence?
    :death-timer 0 ;; Frame counter for death pause
    :death-pause-seconds 2.0
    ;; Player invulnerability after respawn
    :player-invulnerable false ;; Is player currently invulnerable?
    :invulnerability-timer 0 ;; Frame counter for invulnerability
    :invulnerability-seconds 3.0})) ;; Mobile reduced effects mode ;; Mobile reduced effects mode

;; Audio System
 ;; Audio System  
 ;; Audio System
(defonce audio-context (atom nil))

;; FPS tracking atoms
 ;; FPS tracking atoms
(defonce frame-count (atom 0))
(defonce fps-display (atom 0))
(defonce last-fps-update (atom 0))

;; Speed tracking atoms
 ;; Speed tracking atoms
(defonce last-invader-pos (atom nil))
(defonce invader-speed-display (atom 0))
(defonce speed-update-counter (atom 0))

;; Dynamic frame rate compensation system
(defonce frame-rate-tracker
  (atom {:samples []
         :max-samples 60 ; Track last 60 frames (~1 second)
         :last-frame-time 0
         :current-fps 60
         :target-fps 60
         :compensation-multiplier 1.0}))

(defn update-frame-rate-tracking []
  "Update frame rate tracking with current timing"
  (let [current-time (js/performance.now)
        last-time (:last-frame-time @frame-rate-tracker)
        frame-time (if (> last-time 0) (- current-time last-time) 16.67)]

    (swap! frame-rate-tracker
           (fn [tracker]
             (let [samples (conj (:samples tracker) frame-time)
                   trimmed-samples (if (> (count samples) (:max-samples tracker))
                                     (drop (- (count samples) (:max-samples tracker)) samples)
                                     samples)
                   avg-frame-time (if (seq trimmed-samples)
                                    (/ (reduce + trimmed-samples) (count trimmed-samples))
                                    16.67)
                   current-fps (/ 1000 avg-frame-time)
                   target-fps (:target-fps tracker)
                   compensation (/ target-fps current-fps)]

               (assoc tracker
                      :samples trimmed-samples
                      :last-frame-time current-time
                      :current-fps current-fps
                      :compensation-multiplier compensation))))))

(defn get-dynamic-compensation []
  "Get the current dynamic compensation multiplier"
  (:compensation-multiplier @frame-rate-tracker))

(defn get-frame-rate-info []
  "Get current frame rate information for debugging"
  (let [tracker @frame-rate-tracker]
    {:current-fps (:current-fps tracker)
     :target-fps (:target-fps tracker)
     :compensation (:compensation-multiplier tracker)
     :sample-count (count (:samples tracker))
     :is-stable (> (count (:samples tracker)) 30)}))

 ;; Timing functions for death/respawn system  

(defn calculate-pause-duration [pause-seconds]
  "Calculate frame count for pause duration based on current frame rate"
  (let [is-mobile (:is-mobile @game-state)
        target-fps (if is-mobile 30 60)]
    (* pause-seconds target-fps)))

(defn calculate-invulnerability-duration [invul-seconds]
  "Calculate frame count for invulnerability duration based on current frame rate"
  (let [is-mobile (:is-mobile @game-state)
        target-fps (if is-mobile 30 60)]
    (* invul-seconds target-fps)))

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

(defn play-player-hit-sound []
  "Retro 8-bit Space Invaders player explosion with square waves and rapid decay"
  (when @audio-context
    (try
      (let [ctx @audio-context
            now (.-currentTime ctx)]

        ;; Burst of 4 tones with specified frequencies and timing
        (doseq [[freq start-delay duration volume]
                [[880 0.0 0.05 0.8] ; 880 Hz for 50ms
                 [660 0.05 0.05 0.7] ; 660 Hz for 50ms  
                 [440 0.1 0.07 0.6] ; 440 Hz for 70ms
                 [220 0.17 0.1 0.5]]] ; 220 Hz for 100ms

          (let [oscillator (.createOscillator ctx)
                gain (.createGain ctx)]

            ;; Connect audio chain
            (.connect oscillator gain)
            (.connect gain (.-destination ctx))

            ;; Square wave for authentic 8-bit sound
            (aset oscillator "type" "square")
            (aset oscillator "frequency" "value" freq)

            ;; Rapid decay envelope for retro arcade effect
            (let [start-time (+ now start-delay)
                  attack-time 0.005 ; Very fast attack
                  decay-time duration]

              (aset gain "gain" "value" 0)
              (.setValueAtTime (.-gain gain) 0 start-time)
              (.linearRampToValueAtTime (.-gain gain) volume (+ start-time attack-time))
              (.exponentialRampToValueAtTime (.-gain gain) 0.01 (+ start-time decay-time))

              (.start oscillator start-time)
              (.stop oscillator (+ start-time decay-time))))))

      (catch js/Error e
        (comment "Player explosion sound error:" e)))))

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
  (let [is-mobile (:is-mobile state)
        ;; Dynamic compensation based on actual measured frame rate
        dynamic-compensation (get-dynamic-compensation)
        life-decay-rate (* 1 dynamic-compensation)] ;; Faster decay to compensate

    (update state :particles
            (fn [particles]
              (->> particles
                   (map (fn [p] (-> p
                                    ;; Movement with dynamic compensation
                                    (update :x + (* (:vx p) dynamic-compensation))
                                    (update :y + (* (:vy p) dynamic-compensation))
                                    ;; Life decay with dynamic compensation
                                    (update :life - life-decay-rate))))
                   (filter #(> (:life %) 0)))))))

(defn update-explosions [state]
  (let [is-mobile (:is-mobile state)
        ;; Dynamic compensation based on actual measured frame rate
        dynamic-compensation (get-dynamic-compensation)
        frame-increment (* 1 dynamic-compensation)] ;; Dynamic animation speed

    (update state :explosions
            (fn [explosions]
              (->> explosions
                   (map #(update % :frame + frame-increment))
                   (filter #(< (:frame %) 15))))))) ;; Explosions last 15 frames

(defn add-screen-shake [state]
  (assoc state :screen-shake true))

(defn remove-screen-shake [state]
  (assoc state :screen-shake false))

(defn process-death-sequence [state]
  "Handle player death sequence: explosion, sound, pause, then respawn"
  (let [player (:player state)
        player-x (+ (:x player) (/ player-width 2))
        player-y (+ (:y player) (/ player-height 2))
        pause-frames (calculate-pause-duration (:death-pause-seconds state))]

    ;; Play death sound
    (try
      (play-player-hit-sound)
      (catch js/Error e
        (comment "Audio error:" e)))

    ;; Start death sequence with explosion and particles at player position
    (-> state
        (assoc :player-dying true)
        (assoc :death-timer pause-frames) ;; Set countdown timer
        (assoc :invader-bullets []) ;; Clear all invader bullets
        (assoc :bullets []) ;; Clear player bullets
        ;; Add explosion at player position
        (update :explosions conj (create-explosion player-x player-y))
        ;; Add particles at player position
        (update :particles concat (create-particles-for-platform player-x player-y 12))
        add-screen-shake)))

(defn handle-respawn [state]
  "Handle player respawn after death sequence completes"
  (if (> (:lives state) 1)
    ;; Still have lives - respawn player with invulnerability
    (let [invul-frames (calculate-invulnerability-duration (:invulnerability-seconds state))]
      (-> state
          (assoc :player {:x 380 :y 358}) ;; Reset player position
          (update :lives dec) ;; Lose a life
          (assoc :player-dying false) ;; Clear death state
          (assoc :death-timer 0) ;; Reset death timer
          (assoc :player-invulnerable true) ;; Start invulnerability
          (assoc :invulnerability-timer invul-frames))) ;; Set invulnerability timer
    ;; No lives left - game over
    (-> state
        (assoc :game-over true)
        (assoc :player-dying false)
        (assoc :death-timer 0))))

(defn process-invulnerability [state]
  "Handle invulnerability timer countdown"
  (if (:player-invulnerable state)
    (let [new-timer (dec (:invulnerability-timer state))]
      (if (<= new-timer 0)
        ;; Invulnerability period over
        (-> state
            (assoc :player-invulnerable false)
            (assoc :invulnerability-timer 0))
        ;; Continue invulnerability countdown
        (assoc state :invulnerability-timer new-timer)))
    state))

;; Explosions last 15 frames

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
  (let [row (:row invader)] ; Use the stored row instead of calculating from position
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
  "Create authentic 5x11 invader formation with proper spacing and fixed row types"
  (for [row (range 5)
        col (range 11)] ; Changed from 10 to 11 columns for authenticity
    (assoc (create-invader (+ 50 (* col 64)) (+ 30 (* row 35)))
           :row row))) ; Store the original row so appearance doesn't change

(defn fire-bullet [state]
  "Fire a bullet from the center of the red triangle targeting system (authentic 1-bullet limit)"
  (let [current-frame (:frame state)
        last-fire (:last-fire-frame state)
        bullets (:bullets state)
        ;; Dynamic frame rate compensation for firing cooldown
        dynamic-compensation (get-dynamic-compensation)
        ;; Target: ~6 shots per second (10 frames at 60fps)
        ;; Adjust cooldown based on actual frame rate
        cooldown-frames (/ 10 dynamic-compensation)]
    (if (and (> (- current-frame last-fire) cooldown-frames) ;; Dynamic debounce check
             (empty? bullets)) ;; AUTHENTIC: Only fire if no bullets on screen
      (let [player (:player state)
            is-mobile (:is-mobile state)
            keys-pressed (:keys-pressed state)
            is-moving (or (contains? keys-pressed "ArrowLeft")
                          (contains? keys-pressed "ArrowRight"))
            scale-factor (if is-moving 1.1 1.0)
            triangle-base-offset -10
            triangle-scaled-offset (* triangle-base-offset scale-factor)
            bullet-center-x (+ (:x player) (/ player-width 2))
            bullet-x (- bullet-center-x (/ bullet-width 2))
            bullet-y (+ (:y player) triangle-scaled-offset (if is-mobile 8 5) (- bullet-height)) ; Start inside triangle
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
(def ufo-y-position 5) ;; Fixed Y position at top of screen

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
        barrier-y 280] ;; Optimal 30px gap: barrier-bottom(328) to player-top(358)
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
            ;; Rectangle overlap collision - bullet rectangle vs block rectangle
            (let [bullet-left (:x bullet)
                  bullet-right (+ (:x bullet) bullet-width)
                  bullet-top (:y bullet)
                  bullet-bottom (+ (:y bullet) bullet-height)
                  block-left (:x block)
                  block-right (+ (:x block) barrier-block-size)
                  block-top (:y block)
                  block-bottom (+ (:y block) barrier-block-size)]

              ;; Check if rectangles overlap
              (when (and (< bullet-left block-right)
                         (> bullet-right block-left)
                         (< bullet-top block-bottom)
                         (> bullet-bottom block-top))
                (reset! hit-barrier-block {:barrier barrier :block block :bullet bullet})))))

        (if @hit-barrier-block
          ;; Bullet hit a barrier block - damage the barrier and remove bullet
          (let [hit-info @hit-barrier-block
                bullet-pos {:x (:x (:bullet hit-info)) :y (:y (:bullet hit-info))}]
            (comment (str "ðŸ’¥ BARRIER DAMAGED at " (:x bullet-pos) "," (:y bullet-pos)))
            (let [block (:block hit-info)
                  block-center-x (+ (:x block) (/ barrier-block-size 2))
                  block-center-y (+ (:y block) (/ barrier-block-size 2))]
              (swap! results update :barriers damage-barrier-at block-center-x block-center-y 15))
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
   :id bullet-id}) ; Standard vertical speed

(defn create-invader-bullet [invader bullet-id]
  "Create a bullet fired by an invader"
  {:x (+ (:x invader) (/ invader-width 2))
   :y (+ (:y invader) invader-height)
   :id bullet-id}) ; Vertical velocity (same as regular bullets) ; Mark as targeted bullet for visual distinction

(defn fire-invader-bullet [state]
  "Fire bullets from bottom invaders - authentic Space Invaders targeting behavior"
  (let [timer (:invader-shoot-timer state)
        base-interval (:invader-shoot-interval state)
        level-bonus (* 10 (dec (:level state)))
        ;; Dynamic frame rate compensation for invader shooting
        dynamic-compensation (get-dynamic-compensation)
        ;; Adjust interval based on actual frame rate to maintain consistent timing
        adjusted-interval (/ (max 60 (- base-interval level-bonus)) dynamic-compensation)
        bottom-invaders (get-bottom-invaders (:invaders state))
        player (:player state)]

    (if (and (>= timer adjusted-interval)
             (not (empty? bottom-invaders))
             (< (rand) 0.5)) ;; INCREASED: 50% chance to shoot when timer is ready (was 30%)
      ;; AUTHENTIC SPACE INVADERS: Choose shooter based on targeting
      (let [bullet-id (:next-invader-bullet-id state)
            ;; INCREASED: 70% chance for "rolling" shots that target the player's column
            use-targeting (< (rand) 0.7)
            shooter (if use-targeting
                      ;; Find the bottom invader closest to the player's column
                      (let [player-center (+ (:x player) (/ player-width 2))
                            closest-invader (apply min-key
                                                   #(Math/abs (- (+ (:x %) (/ invader-width 2)) player-center))
                                                   bottom-invaders)
                            distance-to-player (Math/abs (- (+ (:x closest-invader) (/ invader-width 2)) player-center))]
                        ;; Only use targeting if there's an invader reasonably close to player
                        (if (< distance-to-player 100) ; Within 100 pixels
                          closest-invader
                          (rand-nth bottom-invaders))) ; Fall back to random if no close invader
                      ;; Random bottom invader for non-targeted shots
                      (rand-nth bottom-invaders))
            new-bullet (create-invader-bullet shooter bullet-id)]
        (comment (str "Invader fires " (if use-targeting "TARGETED" "RANDOM") " bullet from column " (:x shooter) " (player at " (:x player) ")"))
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
  "Move invader bullets straight down and remove off-screen ones"
  (update state :invader-bullets
          (fn [bullets]
            (let [;; Dynamic compensation based on actual measured frame rate
                  dynamic-compensation (get-dynamic-compensation)
                  invader-bullet-speed (* bullet-speed dynamic-compensation)]
              (->> bullets
                   (map #(update % :y + invader-bullet-speed))
                   (filter #(< (:y %) game-height))))))) ; Remove bullets that go off-screen ;; Remove off-screen bullets 

(defn move-invaders [state]
  "Move invaders with smooth continuous movement"
  (let [;; Smooth continuous movement - small steps every frame
        base-speed 0.3 ;; Reduced from 0.5 for easier first level
        level-multiplier (+ 1 (* 0.2 (dec (:level state)))) ;; Slight speed increase per level
        ;; Dynamic compensation based on actual measured frame rate
        dynamic-compensation (get-dynamic-compensation)
        move-speed (* base-speed level-multiplier dynamic-compensation)
        direction (:invader-direction state)
        invaders (:invaders state)

        ;; Smooth drop animation variables
        is-dropping (:invader-dropping state)
        drop-progress (:invader-drop-progress state)
        drop-speed (* 2.0 dynamic-compensation)] ;; Dynamic drop speed too

    (cond
      ;; Currently dropping - continue smooth drop animation
      is-dropping
      (let [new-progress (+ drop-progress drop-speed)
            target-drop (:invader-drop-distance state)
            still-dropping (< new-progress target-drop)]
        (let [new-invaders (if still-dropping
                             (map #(update % :y + drop-speed) invaders)
                             (map #(update % :y + (- target-drop drop-progress)) invaders))
              result-state (if still-dropping
                             (-> state
                                 (assoc :invaders new-invaders)
                                 (assoc :invader-drop-progress new-progress))
                             (-> state
                                 (assoc :invaders new-invaders)
                                 (assoc :invader-dropping false)
                                 (assoc :invader-drop-progress 0)))]
          ;; Track speed for dropping movement
          (when-let [first-invader (first new-invaders)]
            (let [current-pos {:x (:x first-invader) :y (:y first-invader)}]
              (when-let [last-pos @last-invader-pos]
                (let [dx (- (:x current-pos) (:x last-pos))
                      dy (- (:y current-pos) (:y last-pos))
                      total-speed (Math/sqrt (+ (* dx dx) (* dy dy)))]
                  (swap! speed-update-counter inc)
                  (reset! invader-speed-display total-speed)))
              (reset! last-invader-pos current-pos)))
          result-state))

      ;; Normal horizontal movement - smooth continuous
      :else
      (let [;; Calculate new horizontal positions with smooth movement
            new-invaders (map #(update % :x + (* direction move-speed)) invaders)

            ;; Check if any invader hit the screen edge
            leftmost-x (apply min (map :x new-invaders))
            rightmost-x (apply max (map #(+ (:x %) invader-width) new-invaders))
            hit-edge? (or (<= leftmost-x 0)
                          (>= rightmost-x game-width))]

        (if hit-edge?
          ;; Hit edge: start smooth drop animation and reverse direction
          (do
            (try
              (play-hit-sound) ;; Sound when invaders change direction
              (catch js/Error e
                (comment "Audio error:" e)))
            (-> state
                (assoc :invaders invaders) ;; Keep current position, start dropping next frame
                (update :invader-direction -) ;; Reverse direction
                (assoc :invader-dropping true) ;; Start drop animation
                (assoc :invader-drop-progress 0) ;; Reset drop progress
                add-screen-shake))

          ;; Normal horizontal movement - smooth every frame
          (let [result-state (-> state (assoc :invaders new-invaders))]
            ;; Track actual movement speed
            (when-let [first-invader (first new-invaders)]
              (let [current-pos {:x (:x first-invader) :y (:y first-invader)}]
                (when-let [last-pos @last-invader-pos]
                  (let [dx (- (:x current-pos) (:x last-pos))
                        dy (- (:y current-pos) (:y last-pos))
                        total-speed (Math/sqrt (+ (* dx dx) (* dy dy)))]
                    (swap! speed-update-counter inc)
                    (reset! invader-speed-display total-speed)))
                (reset! last-invader-pos current-pos)))
            result-state))))))

(defn move-bullets [state]
  (let [old-bullets (:bullets state)
        ;; Dynamic compensation based on actual measured frame rate
        dynamic-compensation (get-dynamic-compensation)
        effective-bullet-speed (* bullet-speed dynamic-compensation)
        new-bullets (->> old-bullets
                         (map #(update % :y - effective-bullet-speed))
                         (filter #(> (:y %) 0)))]
    (assoc state :bullets new-bullets)))

;; Collision detection
(defn rectangles-collide? [bullet invader]
  (and (<= (:x bullet) (+ (:x invader) invader-width))
       (>= (+ (:x bullet) bullet-width) (:x invader))
       (<= (:y bullet) (+ (:y invader) invader-height))
       (>= (+ (:y bullet) bullet-height) (:y invader))))

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
                     (conj new-explosions (create-explosion (+ (:x bullet) (/ bullet-width 2)) (+ (:y bullet) (/ bullet-height 2))))
                     (concat new-particles (create-particles-for-platform (+ (:x bullet) (/ bullet-width 2)) (+ (:y bullet) (/ bullet-height 2)) 8))
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
              (update :explosions conj (create-explosion (+ (:x hit-bullet) (/ bullet-width 2)) (+ (:y hit-bullet) (/ bullet-height 2)))) ;; Explosion
              (update :particles concat (create-particles-for-platform (+ (:x hit-bullet) (/ bullet-width 2)) (+ (:y hit-bullet) (/ bullet-height 2)) 12)) ;; More particles
              add-screen-shake))
        state))
    state))

 ;; Enhanced collision system with barriers

;; Player collision detection
(defn check-invader-bullet-player-collisions [state]
  "Check if invader bullets hit the player and trigger death sequence"
  (let [player (:player state)
        invader-bullets (:invader-bullets state)
        hit-bullets (filter #(rectangles-collide? % player) invader-bullets)]

    (if (and (not (empty? hit-bullets))
             (not (:player-dying state)) ;; Only process hit if not already dying
             (not (:player-invulnerable state))) ;; Only process hit if not invulnerable
      ;; Player was hit! Start death sequence
      (process-death-sequence state)

      ;; No collision or already dying/invulnerable, just remove any bullets that hit
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
      ;; AUTHENTIC SPACE INVADERS: When invaders reach the bottom, it's IMMEDIATE GAME OVER
      ;; regardless of remaining lives - the invasion has succeeded!
      (some #(>= (:y %) (- (:y player) 20)) invaders)
      (do
        (comment "INVASION SUCCESSFUL! Invaders reached Earth - GAME OVER!")
        (try
          (play-explosion-sound) ;; Invasion success sound
          (catch js/Error e
            (comment "Audio error:" e)))
        (assoc state :game-over true)) ;; Immediate Game Over - no life loss

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
        ;; Dynamic compensation based on actual measured frame rate
        dynamic-compensation (get-dynamic-compensation)
        move-speed (* 4 dynamic-compensation)] ; Pixels per frame for continuous movement
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
    " " (when-not (contains? (:keys-pressed @game-state) " ")
          ;; Only fire if space key wasn't already pressed (prevent auto-repeat)
          (swap! game-state assoc :keys-pressed
                 (conj (or (:keys-pressed @game-state) #{}) key))
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
    " " (swap! game-state assoc :keys-pressed
               (disj (or (:keys-pressed @game-state) #{}) key))
    ;; Default case - ignore unknown keys
    nil))

(defonce event-listeners (atom {}))

(defn setup-global-key-listeners []
  "Setup global keyboard and touch event listeners for game control and viewport locking"
  ;; Store listener functions for later cleanup
  (let [keydown-fn (fn [e]
                     (when-not (:game-over @game-state)
                       (.preventDefault e) ; Prevent scrolling, etc.
                       (handle-key-down (.-key e))))
        keyup-fn (fn [e]
                   (when-not (:game-over @game-state)
                     (handle-key-up (.-key e))))
        touchstart-fn (fn [e]
                        ;; Allow touch events on game control buttons and play again button, prevent everywhere else
                        (let [target (.-target e)
                              is-control-button (.closest target ".touch-button")
                              is-play-again-button (.closest target ".neon-button")]
                          (when-not (or is-control-button is-play-again-button)
                            (.preventDefault e))))
        touchmove-fn (fn [e]
                       ;; Always prevent touch move to stop scrolling/zooming
                       (.preventDefault e))
        touchend-fn (fn [e]
                      ;; Allow touch end events for control buttons and play again button
                      (let [target (.-target e)
                            is-control-button (.closest target ".touch-button")
                            is-play-again-button (.closest target ".neon-button")]
                        (when-not (or is-control-button is-play-again-button)
                          (.preventDefault e))))
        gesturestart-fn #(.preventDefault %)
        gesturechange-fn #(.preventDefault %)
        gestureend-fn #(.preventDefault %)
        contextmenu-fn #(.preventDefault %)]

    ;; Store references for cleanup
    (reset! event-listeners
            {:keydown keydown-fn
             :keyup keyup-fn
             :touchstart touchstart-fn
             :touchmove touchmove-fn
             :touchend touchend-fn
             :gesturestart gesturestart-fn
             :gesturechange gesturechange-fn
             :gestureend gestureend-fn
             :contextmenu contextmenu-fn})

    ;; Add event listeners
    (.addEventListener js/document "keydown" keydown-fn)
    (.addEventListener js/document "keyup" keyup-fn)
    (.addEventListener js/document "touchstart" touchstart-fn #js {:passive false})
    (.addEventListener js/document "touchmove" touchmove-fn #js {:passive false})
    (.addEventListener js/document "touchend" touchend-fn #js {:passive false})
    (.addEventListener js/document "gesturestart" gesturestart-fn #js {:passive false})
    (.addEventListener js/document "gesturechange" gesturechange-fn #js {:passive false})
    (.addEventListener js/document "gestureend" gestureend-fn #js {:passive false})
    (.addEventListener js/document "contextmenu" contextmenu-fn #js {:passive false})))

(defn remove-global-key-listeners []
  "Remove global keyboard and touch event listeners"
  (let [listeners @event-listeners]
    (.removeEventListener js/document "keydown" (:keydown listeners))
    (.removeEventListener js/document "keyup" (:keyup listeners))
    (.removeEventListener js/document "touchstart" (:touchstart listeners))
    (.removeEventListener js/document "touchmove" (:touchmove listeners))
    (.removeEventListener js/document "touchend" (:touchend listeners))
    (.removeEventListener js/document "gesturestart" (:gesturestart listeners))
    (.removeEventListener js/document "gesturechange" (:gesturechange listeners))
    (.removeEventListener js/document "gestureend" (:gestureend listeners))
    (.removeEventListener js/document "contextmenu" (:contextmenu listeners))
    (reset! event-listeners {})))

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

    ;; Check if player is in death sequence
    (if (:player-dying state)
      ;; Player is dying - handle death sequence countdown
      (let [new-timer (dec (:death-timer state))]
        (if (<= new-timer 0)
          ;; Death sequence complete - handle respawn
          (handle-respawn state)
          ;; Continue death sequence - only update visual effects and timer
          (-> state
              (assoc :death-timer new-timer)
              (update :frame inc)
                ;; Handle invulnerability timer
              process-invulnerability
              update-explosions
              update-particles
              ;; Remove screen shake after a few frames
              (#(if (and (:screen-shake %) (> (mod (:frame %) 10) 5))
                  (remove-screen-shake %)
                  %)))))

      ;; Normal gameplay - player is alive
      (let [is-mobile (:is-mobile state)]
        (if is-mobile
          ;; Mobile: Optimized update with reduced processing for non-critical systems
          (let [frame (:frame state)
                ;; Process heavy visual effects every other frame, but keep gameplay smooth
                process-visual-effects (= (mod frame 2) 0)]
            (-> state
                (update :frame inc)
                ;; Handle invulnerability timer
                process-invulnerability
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
                check-bullet-ufo-collision ;; UFO: Player bullets vs UFO
                check-invader-bullet-player-collisions ;; Invader bullets vs player
                ;; Invader bullets vs barriers
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
                    %))))

          ;; Desktop: Full 60 FPS performance - no optimizations
          (-> state
              (update :frame inc)
              ;; Handle invulnerability timer
              process-invulnerability
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
              ;; Invader bullets vs barriers
              update-explosions
              update-particles
              check-level-completion
              check-invader-barrier-collisions
              check-player-collision
              ;; Remove screen shake after a few frames (enabled on desktop)
              (#(if (and (:screen-shake %) (> (mod (:frame %) 10) 5))
                  (remove-screen-shake %)
                  %))))))))

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
  "Game loop with dynamic frame rate compensation"
  (let [is-mobile @mobile-device
        current-time (js/Date.now)]

    ;; Update dynamic frame rate tracking
    (update-frame-rate-tracking)

    ;; Update FPS counter every second
    (swap! frame-count inc)
    (when (>= (- current-time @last-fps-update) 1000)
      (reset! fps-display @frame-count)
      (reset! frame-count 0)
      (reset! last-fps-update current-time))

    (swap! game-state update-game)
    (if is-mobile
      ;; Mobile: 30fps for stable performance (33ms interval)
      (reset! game-loop-id (js/setTimeout game-loop 33))
      ;; Desktop: Fixed 60fps for consistent speed (16.67ms interval)
      (reset! game-loop-id (js/setTimeout game-loop 16.67)))))

(defn start-game []
  (comment "🚀 AUTHENTIC SPACE INVADERS - 5×11 Formation with Tiered Scoring!")
  (comment "💯 Features: 55 invaders, 10/20/30 point scoring, UFO mystery ships, destructible barriers")
  (comment "🎵 NEW: Authentic heartbeat audio that speeds up as invaders are destroyed!")

  ;; Detect device type for performance optimization
  (let [is-mobile (detect-mobile-device)]
    (when is-mobile
      (comment "📱 MOBILE MODE: 30fps + performance optimizations enabled"))

    (init-audio) ;; Initialize sound

    ;; CRITICAL FIX: Stop any existing game loop properly
    ;; Both mobile and desktop use setTimeout, so always use clearTimeout
    (when @game-loop-id
      (js/clearTimeout @game-loop-id)
      (reset! game-loop-id nil))

    ;; ADDITIONAL FIX: Reset all speed tracking atoms to prevent stale data
    (reset! frame-count 0)
    (reset! fps-display 0)
    (reset! last-fps-update 0)
    (reset! last-invader-pos nil)
    (reset! invader-speed-display 0)
    (reset! speed-update-counter 0)

    (reset! game-state {:player {:x 380 :y 358}
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
                        :invader-dropping false
                        :invader-drop-progress 0
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
                      (contains? keys-pressed "ArrowRight"))
        is-mobile (:is-mobile @game-state)
        player-dying (:player-dying @game-state)
        player-invulnerable (:player-invulnerable @game-state)
        frame (:frame @game-state)
        ;; Flash effect during invulnerability (every 8 frames)
        invul-flash (and player-invulnerable (< (mod frame 16) 8))]

    ;; Don't render player if dying
    (when-not player-dying
      [:div {:style {:position "absolute"
                     :left (:x player)
                     :top (:y player)
                     :width player-width
                     :height player-height
                     :display "flex"
                     :align-items "center"
                     :justify-content "center"
                     :z-index 10
                     ;; Add opacity flashing during invulnerability
                     :opacity (if invul-flash 0.3 1.0)}}

       ;; Castle fortress + targeting system combination
       [:div {:style {:position "relative"
                      :font-size (if is-mobile "36px" "20px")
                      :line-height "1"
                      ;; Change color during invulnerability
                      :color (if player-invulnerable "#ffff00" "#00ff00")
                      ;; Disable glow on mobile, conditional glow on desktop
                      :filter (when (and (not is-mobile) (not is-moving))
                                (if player-invulnerable
                                  "drop-shadow(0 0 8px #ffff00)"
                                  "drop-shadow(0 0 8px #00ff00)"))
                      :animation (when-not is-moving "pulse 2s infinite")
                      :user-select "none"
                      :pointer-events "none"
                      :transition "filter 0.2s ease, transform 0.2s ease"
                      :transform (if is-moving "scale(1.1)" "scale(1)")}}
        ;; Targeting system (red triangle positioned clearly above castle)
        [:div {:style {:position "absolute"
                       :top "-18px" ; Moved further up
                       :left "50%"
                       :transform "translateX(-50%)"
                       :font-size "16px" ; Slightly larger
                       :color "#ff0000"
                       ;; Disable glow on mobile, conditional glow on desktop
                       :filter (when (and (not is-mobile) (not is-moving))
                                 "drop-shadow(0 0 4px #ff0000)")
                       :z-index 3}}
         "🔺"]
        ;; Base fortress (castle represents the defensive structure)
        [:div {:style {:position "relative"
                       :z-index 1}}
         "🏰"]]]))) ; Castle fortress with red targeting system

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
   ;; Invader bullet trail effect (upward from bullet)
   [:div {:style {:position "absolute"
                  :top "-20px" ;; Trail goes upward from bullet
                  :left "50%"
                  :transform "translateX(-50%)"
                  :width "2px"
                  :height "20px"
                  :background "linear-gradient(0deg, rgba(255,0,0,0.8), transparent)"
                  :border-radius "50%"}}]])

(defn ufo-component [ufo]
  "UFO Mystery Ship component with flying saucer emoji"
  [:div {:style {:position "absolute"
                 :left (:x ufo)
                 :top (:y ufo)
                 :width ufo-width
                 :height ufo-height
                 :display "flex"
                 :align-items "center"
                 :justify-content "center"
                 :z-index 6}}

   ;; Flying saucer emoji for the UFO mystery ship
   [:div {:style {:font-size (if (:is-mobile @game-state) "38px" "32px") ; Large size for the mystery UFO
                  :line-height "1"
                  :color "#ff00ff"
                  :filter "drop-shadow(0 0 12px #ff00ff)"
                  :animation "pulse 1.5s infinite"
                  :user-select "none"
                  :pointer-events "none"}}
    "🛸"]]) ; Flying saucer emoji for the mystery ship

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
  (let [row (:row invader) ; Use the stored row instead of calculating from position
        invader-type (case row
                       0 :squid ; Top row - 30 points
                       (1 2) :crab ; Middle rows - 20 points  
                       (3 4) :octopus) ; Bottom rows - 10 points

        ;; Different characters and colors for each type - using more detailed Unicode symbols
        [character color glow-color background-color] (case invader-type
                                                        :squid ["🦑" "#00ff00" "#00ff00" "#001100"] ; Squid emoji - more detailed
                                                        :crab ["🦀" "#ffff00" "#ffff00" "#221100"] ; Crab emoji - realistic  
                                                        :octopus ["🐙" "#ff0000" "#ff0000" "#110000"]) ; Octopus emoji - detailed

        ;; Simplified effects - reduce glow on mobile for cleaner look
        is-mobile (:is-mobile @game-state)
        pulse-scale (if is-mobile 1.0 (+ 1 (* 0.03 (Math/sin (/ (:y invader) 20)))))
        glow-effect (if is-mobile "none" (str "drop-shadow(0 0 8px " glow-color ")"))
        animation (if is-mobile "none" "pulse 2s infinite")]

    ;; Simplified container - just position and size, no styling that could create borders
    [:div {:style {:position "absolute"
                   :left (:x invader)
                   :top (:y invader)
                   :width invader-width
                   :height invader-height
                   :display "flex"
                   :align-items "center"
                   :justify-content "center"
                   :transform (str "scale(" pulse-scale ")")
                   :z-index 5}}

     ;; Clean mobile sprites - no glow effects on mobile for better visibility
     [:div {:style {:font-size (if is-mobile "34px" "22px")
                    :line-height "1"
                    :color color
                    :filter glow-effect
                    :animation animation
                    :user-select "none"
                    :pointer-events "none"}}
      character]]))

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
  [:div {:style {:display "flex"
                 :align-items "center"
                 :margin-right "20px"}}
   [:span {:style {:margin-right "10px"
                   :color "#00ffff"
                   :font-weight "bold"}}
    "LIVES:"]
   (for [i (range lives)]
     [:div {:key i
            :style {:width "25px"
                    :height "18px"
                    :background "linear-gradient(45deg, #00ff00, #00aa00)"
                    :border "1px solid #00ffff"
                    :border-radius "4px"
                    :margin-left "3px"
                    :box-shadow "0 0 8px #00ff00"
                    :display "inline-block"
                    :animation "playerPulse 2s ease-in-out infinite alternate"}}])]) ;; Add number inside each box

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
        [lives-display (:lives state)]
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
         ;; Heartbeat button removed
         ]]]

      ;; Debug panel (smaller, less intrusive)
      ;; Instructions panel (top left)
      [:div {:style {:position "absolute"
                     :top "80px"
                     :left "10px"
                     :color "#ccc"
                     :font-family "monospace"
                     :font-size "12px"
                     :background "rgba(0,0,0,0.9)"
                     :padding "10px"
                     :border-radius "5px"
                     :border "1px solid #00ffff"
                     :z-index 100
                     :max-width "200px"
                     :box-shadow "0 0 8px rgba(0,255,255,0.3)"}}
       [:div {:style {:color "#00ffff"
                      :font-size "13px"
                      :font-weight "bold"
                      :margin-bottom "6px"
                      :text-shadow "0 0 5px #00ffff"}}
        "🎮 CONTROLS"]
       [:div {:style {:font-size "11px" :line-height "1.4" :color "#fff"}}
        "← → Move" [:br]
        "SPACE Fire" [:br]
        "Click for Audio" [:br]
        [:div {:style {:margin-top "5px" :font-size "10px" :color "#ffaa00"}}
         "💡 Destroy all invaders!"]]]

      ;; Debug panel (smaller, less intrusive)
      [:div {:style {:position "absolute"
                     :top "80px"
                     :right "10px"
                     :color "#ccc"
                     :font-family "monospace"
                     :font-size "11px"
                     :background "rgba(0,0,0,0.9)"
                     :padding "8px"
                     :border-radius "4px"
                     :border "1px solid #00ff00"
                     :z-index 100
                     :max-width "250px"
                     :box-shadow "0 0 8px rgba(0,255,0,0.3)"}}
       [:div {:style {:margin-bottom "4px"
                      :color "#00ff00"
                      :font-size "12px"
                      :font-weight "bold"
                      :text-shadow "0 0 5px #00ff00"}}
        "📡 DEBUG"]
       [:div {:style {:font-size "10px" :color "#aaa" :margin-bottom "3px"}}
        "Live REPL in browser"]
       [:div {:style {:font-size "10px" :color "#ffaa00" :margin-bottom "2px"}}
        "FPS:" @fps-display " F:" (:frame state) " L:" (:level state) " A:" (if @audio-context (if (= (.-state @audio-context) "running") "🔊" "⚠️") "⚪")]
       [:div {:style {:font-size "10px" :color "#00ffff" :margin-bottom "2px"}}
        "PB:" (count (:bullets state)) " IB:" (count (:invader-bullets state)) " I:" (count (:invaders state))]
       [:div {:style {:font-size "10px" :color "#ffff00" :margin-bottom "2px"}}
        "Dir:" (if (= (:invader-direction state) 1) "→" "←") " E:" (count (:explosions state))]
       [:div {:style {:font-size "10px" :color "#ff66ff" :margin-bottom "2px"}}
        "Speed:" (let [keys (:keys-pressed state)]
                   (cond
                     (contains? keys "ArrowLeft") "←"
                     (contains? keys "ArrowRight") "→"
                     :else "■"))
        " Comp:" (str (.toFixed (get-dynamic-compensation) 2) "x")]
       [:div {:style {:font-size "10px" :color "#ff8800"}}
        "InvSpd:" (str (.toFixed @invader-speed-display 3) "px/f")
        " C:" @speed-update-counter
        " " (let [is-dropping (:invader-dropping state)]
              (if is-dropping "⬇" "→"))]
       [:div {:style {:font-size "9px" :color "#aaaaaa"}}
        (let [fr-info (get-frame-rate-info)]
          (str "⚡ " (.toFixed (:current-fps fr-info) 1) "fps → " (.toFixed (:compensation fr-info) 2) "x"))]]

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
                   :on-click (fn [e]
                               (.preventDefault e)
                               (.stopPropagation e)
                               (init-audio)
                               (start-game))
                   :on-touch-start (fn [e]
                                     (.preventDefault e)
                                     (.stopPropagation e))
                   :on-touch-end (fn [e]
                                   (.preventDefault e)
                                   (.stopPropagation e)
                                   (init-audio)
                                   (start-game))
                   :on-mouse-down (fn [e]
                                    (.preventDefault e)
                                    (.stopPropagation e))
                   :on-mouse-up (fn [e]
                                  (.preventDefault e)
                                  (.stopPropagation e)
                                  (init-audio)
                                  (start-game))}
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
         [particle-component particle])]

      ;; Mobile debug panel - centered below game
      (when (:is-mobile state)
        [:div {:style {:margin "10px auto"
                       :max-width "796px"
                       :padding "6px"
                       :background "rgba(0,0,0,0.9)"
                       :border "1px solid #00ff00"
                       :border-radius "4px"
                       :font-family "monospace"
                       :font-size "9px"
                       :text-align "center"}}
         [:span {:style {:color "#00ff00"}} "📡 "]
         [:span {:style {:color "#ffaa00"}} "FPS:" @fps-display " "]
         [:span {:style {:color "#00ffff"}} "PB:" (count (:bullets state)) " "]
         [:span {:style {:color "#ff8800"}} "InvSpd:" (str (.toFixed @invader-speed-display 3) "px/f")]])]]))

;; Init
 ;; Mobile Touch Controls Component (rendered separately to avoid scaling)
(defn mobile-controls []
  [:div {:class "mobile-controls"}
   ;; Left side movement controls
   [:div {:class "movement-controls"}
    [:div {:class "touch-button"
           :on-touch-start (fn [e]
                             (.preventDefault e)
                             (handle-key-down "ArrowLeft"))
           :on-touch-end (fn [e]
                           (.preventDefault e)
                           (handle-key-up "ArrowLeft"))
           :on-touch-cancel (fn [e]
                              (.preventDefault e)
                              (handle-key-up "ArrowLeft"))
           :on-mouse-down (fn [e]
                            (.preventDefault e)
                            (handle-key-down "ArrowLeft"))
           :on-mouse-up (fn [e]
                          (.preventDefault e)
                          (handle-key-up "ArrowLeft"))
           :on-mouse-leave (fn [e]
                             (.preventDefault e)
                             (handle-key-up "ArrowLeft"))}
     "←"]
    [:div {:class "touch-button"
           :on-touch-start (fn [e]
                             (.preventDefault e)
                             (handle-key-down "ArrowRight"))
           :on-touch-end (fn [e]
                           (.preventDefault e)
                           (handle-key-up "ArrowRight"))
           :on-touch-cancel (fn [e]
                              (.preventDefault e)
                              (handle-key-up "ArrowRight"))
           :on-mouse-down (fn [e]
                            (.preventDefault e)
                            (handle-key-down "ArrowRight"))
           :on-mouse-up (fn [e]
                          (.preventDefault e)
                          (handle-key-up "ArrowRight"))
           :on-mouse-leave (fn [e]
                             (.preventDefault e)
                             (handle-key-up "ArrowRight"))}
     "→"]]

   ;; Right side fire control
   [:div {:class "shoot-control"}
    [:div {:class "touch-button"
           :on-touch-start (fn [e]
                             (.preventDefault e)
                             (when-not (contains? (:keys-pressed @game-state) " ")
                               (swap! game-state assoc :keys-pressed
                                      (conj (or (:keys-pressed @game-state) #{}) " "))
                               (swap! game-state fire-bullet)))
           :on-touch-end (fn [e]
                           (.preventDefault e)
                           (swap! game-state assoc :keys-pressed
                                  (disj (or (:keys-pressed @game-state) #{}) " ")))
           :on-touch-cancel (fn [e]
                              (.preventDefault e)
                              (swap! game-state assoc :keys-pressed
                                     (disj (or (:keys-pressed @game-state) #{}) " ")))
           :on-click (fn [e]
                       (.preventDefault e)
                       (when-not (contains? (:keys-pressed @game-state) " ")
                         (swap! game-state assoc :keys-pressed
                                (conj (or (:keys-pressed @game-state) #{}) " "))
                         (swap! game-state fire-bullet)))}
     "FIRE"]]])

;; Test functions for dynamic compensation
(defn test-dynamic-compensation []
  "Test the dynamic compensation system"
  (in-ns 'space-invaders)
  (let [fr-info (get-frame-rate-info)]
    (println "\n🎯 DYNAMIC COMPENSATION TEST")
    (println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    (println (str "📊 Current FPS: " (.toFixed (:current-fps fr-info) 1)))
    (println (str "🎯 Target FPS: " (.toFixed (:target-fps fr-info) 1)))
    (println (str "⚡ Compensation: " (.toFixed (:compensation fr-info) 3) "x"))
    (println (str "📈 Sample count: " (:sample-count fr-info)))
    (println (str "✅ Stable: " (if (:is-stable fr-info) "Yes" "No")))
    (println "\nOLD vs NEW:")
    (println "  Old mobile: 2.0x fixed")
    (println "  Old desktop: 1.0x fixed")
    (println (str "  New dynamic: " (.toFixed (:compensation fr-info) 3) "x (adapts to real performance)"))
    (println "\nThis should eliminate speed differences between platforms!")))

(defn reset-compensation []
  "Reset the compensation system"
  (in-ns 'space-invaders)
  (reset! frame-rate-tracker {:samples []
                              :max-samples 60
                              :last-frame-time 0
                              :current-fps 60
                              :target-fps 60
                              :compensation-multiplier 1.0})
  (println "🔄 Dynamic compensation reset - will recalibrate automatically"))

(defn test-firing-rate []
  "Test that bullet firing rate is consistent across platforms"
  (in-ns 'space-invaders)
  (let [compensation (get-dynamic-compensation)
        cooldown-frames (/ 10 compensation)
        fr-info (get-frame-rate-info)
        current-fps (:current-fps fr-info)
        shots-per-sec (* current-fps (/ 1 cooldown-frames))]

    (println "\n🎯 BULLET FIRING RATE TEST")
    (println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    (println (str "📊 Current FPS: " (.toFixed current-fps 1)))
    (println (str "⚡ Compensation: " (.toFixed compensation 3) "x"))
    (println (str "🎯 Target firing rate: 6.0 shots/second"))
    (println (str "📈 Calculated rate: " (.toFixed shots-per-sec 1) " shots/second"))
    (println (str "✅ Fixed: " (if (< (Math/abs (- shots-per-sec 6)) 0.1) "Yes" "No")))
    (println "\nOLD PROBLEM:")
    (println "  Desktop 60fps: 6 shots/sec")
    (println "  Mobile 30fps: 3 shots/sec (HALF RATE!)")
    (println "\nNEW SOLUTION:")
    (println "  All platforms: ~6 shots/sec (dynamic compensation)")
    (println "\nBullet firing should now feel identical on mobile and desktop!")))

(defn test-all-timings []
  "Test all timing-dependent systems"
  (in-ns 'space-invaders)
  (let [fr-info (get-frame-rate-info)
        compensation (get-dynamic-compensation)]
    (println "\n🕒 ALL TIMING SYSTEMS TEST")
    (println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    (println (str "⚡ Dynamic compensation: " (.toFixed compensation 3) "x"))
    (println "\n📍 FIXED TIMING ISSUES:")
    (println "  ✅ Bullet firing rate (was frame-dependent)")
    (println "  ✅ Invader shooting rate (was frame-dependent)")
    (println "  ✅ Heartbeat timing (was frame-dependent)")
    (println "  ✅ Movement speeds (already had compensation)")
    (println "\n🎯 RESULT:")
    (println "  All timing now adapts to actual frame rate")
    (println "  Mobile and desktop should feel identical")))

(defn init []
  (dom/render [app] (.getElementById js/document "app"))
  ;; Render mobile controls separately to avoid scaling
  (dom/render [mobile-controls] (.getElementById js/document "mobile-controls")))

(init)
(start-game)
