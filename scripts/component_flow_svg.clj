;; Generates the component-flow animations (SMIL SVGs) in docs/img/:
;;
;;   component-flow.svg   a click on the counter, through the flow and back
;;   repl-event-flow.svg  a REPL send-event! into a connected component's flow
;;
;;   bb scripts/component_flow_svg.clj      ; then `bb component-flow-gif` renders the GIFs
;;
;; Both are drawn after the whiteboard sketch: on the left what talks to the
;; flow (the browser; the REPL), on the right the flow — in-chan → :controller
;; → :view → out-chan — with the example's real code inside the procs. A
;; message travels the wires step by step (each scene's steps): it moves, the
;; box it reaches glows, the caption under the picture explains, next step.
;; Colours are ramon.css (tbc.polylith); the look follows multi-agent's
;; architecture video. Keep the code shown in sync with basics/counter*.clj
;; and live/live_counter*.clj.

(require '[clojure.string :as str])

;;; Palette (ramon.css) and fonts

(def PINK "#e92063")
(def BODY-BG "#f8f6f6")
(def CHROME "#0d1a15")
(def TEXT "#111827")
(def MUTED "#707683")
(def ACCENT-TEXT "#8a4a56")
(def ACCENT-BG "#fdeef0")
(def ACCENT-BORDER "#e8b4bc")
(def SESSION-BG "#fdf2f6")
(def SESSION-DIVIDER "#f3dfe3")
(def BOX-BORDER "#d9d2d5")
(def WIRE "#cfc6c9")
(def SUCCESS "#16a34a")
(def EVENT-C PINK)        ; a browser event
(def CTX-C "#7c3aed")     ; the new ctx, controller → view
(def HTML-C "#059669")    ; the render, view → browser
(def SERVER-C "#f59e0b")  ; a server event (send-event!)
(def SANS "Poppins, system-ui, -apple-system, 'Segoe UI', sans-serif")
(def MONO "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace")

(def W 1280)
(def H 720)

;;; Shared geometry: the flow on the right, the left column, the caption strip

(def left-col {:x 32 :y 96 :w 300 :h 494})
(def flow-box {:x 420 :y 96 :w 828 :h 494})
(def controller {:x 540 :y 132 :w 680 :h 196})
(def view {:x 540 :y 400 :w 680 :h 160})
(def pill {:w 112 :h 40})
(def in-chan [420 230])
(def out-chan [420 520])

;;; The scene being drawn: its length and its steps (bound per scene)

(def ^:dynamic *T* 27.0)
(def ^:dynamic *steps* [])

;;; SVG helpers

(defn esc [s] (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))
(defn fx [n] (format "%.4f" (double n)))
(defn kt [t] (fx (/ (max 0.0 (min *T* t)) *T*)))
(defn dur [] (str "dur=\"" *T* "s\" repeatCount=\"indefinite\""))

(defn anim-opacity
  "Opacity keyframes: `pts` is [[t v] …] (seconds, value), held flat before
   the first and after the last."
  [pts]
  (let [pts (concat [[0 (second (first pts))]] pts [[*T* (second (last pts))]])]
    (str "<animate attributeName=\"opacity\" values=\"" (str/join ";" (map (comp fx second) pts))
         "\" keyTimes=\"" (str/join ";" (map (comp kt first) pts)) "\" " (dur) "/>")))

(defn window
  "Visible (at `peak`) from t0 to t1, with short fades."
  ([t0 t1] (window t0 t1 1))
  ([t0 t1 peak]
   (let [f 0.25]
     (anim-opacity [[t0 0] [(+ t0 f) peak] [(- t1 f) peak] [t1 0]]))))

(defn motion
  "Moves the element along `path` between t0 and t1, at constant speed."
  [path t0 t1]
  (str "<animateMotion path=\"M " (str/join " L " (map #(str/join " " %) path))
       "\" keyPoints=\"0;0;1;1\" keyTimes=\"0;" (kt t0) ";" (kt t1) ";1\" calcMode=\"linear\" " (dur) "/>"))

(defn attrs [m] (str/join " " (for [[k v] m] (str (name k) "=\"" v "\""))))

(defn rect [{:keys [x y w h]} & {:as as}]
  (str "<rect x=\"" x "\" y=\"" y "\" width=\"" w "\" height=\"" h "\" " (attrs as) "/>"))

(defn text [x y s & {:as as}]
  (str "<text x=\"" x "\" y=\"" y "\" " (attrs as) ">" s "</text>"))

(defn grow [{:keys [x y w h]} d] {:x (- x d) :y (- y d) :w (+ w (* 2 d)) :h (+ h (* 2 d))})

(defn pill-box [[cx cy]] {:x (- cx (/ (:w pill) 2)) :y (- cy (/ (:h pill) 2)) :w (:w pill) :h (:h pill)})

(defn flip
  "Two values in the same place: `before` until t, `after` from t."
  [t before after]
  (str "<g>" (anim-opacity [[t 1] [(+ t 0.01) 0]]) before "</g>"
       "<g opacity=\"0\">" (anim-opacity [[t 0] [(+ t 0.01) 1]]) after "</g>"))

(defn appear
  "`content`, hidden until t."
  [t content]
  (str "<g opacity=\"0\">" (anim-opacity [[t 0] [(+ t 0.15) 1]]) content "</g>"))

;;; Code, as coloured spans

(def kw-c ACCENT-TEXT)
(def str-c "#16a34a")
(def num-c "#b45309")

(defn code-line
  "A mono line from [[text colour] …] segments (a bare string is plain text)."
  ([x y segs] (code-line x y segs {}))
  ([x y segs {:keys [size fill] :or {size 14 fill TEXT}}]
   (str "<text x=\"" x "\" y=\"" y "\" font-family=\"" MONO "\" font-size=\"" size "\" fill=\"" fill "\" xml:space=\"preserve\">"
        (str/join (for [s segs]
                    (if (string? s)
                      (esc s)
                      (str "<tspan fill=\"" (second s) "\">" (esc (first s)) "</tspan>"))))
        "</text>")))

;;; Drawing

(defn proc-box [{:keys [x y w] :as b} title subtitle]
  (str (rect b :rx 14 :fill "#ffffff" :stroke BOX-BORDER :stroke-width 1.5)
       (text (+ x 24) (+ y 36) (esc title) :font-size 22 :font-weight 600 :fill TEXT)
       (text (+ x w -24) (+ y 36) (esc subtitle) :font-size 13 :font-family MONO :fill MUTED :text-anchor "end")
       "<line x1=\"" (+ x 18) "\" y1=\"" (+ y 50) "\" x2=\"" (+ x w -18) "\" y2=\"" (+ y 50) "\" stroke=\"#eee8ea\" stroke-width=\"1.5\"/>"))

(defn chan-pill [at label & [dashed?]]
  (let [b (pill-box at)]
    (str (rect b :rx (/ (:h pill) 2) :fill ACCENT-BG :stroke ACCENT-BORDER :stroke-width 1.5
               :stroke-dasharray (if dashed? "6 5" "none"))
         (text (first at) (+ (second at) 6) (esc label) :text-anchor "middle" :font-size 15 :font-weight 500 :fill ACCENT-TEXT))))

(defn wire [pts & [dashed?]]
  (str "<polyline points=\"" (str/join " " (map #(str/join "," %) pts)) "\" fill=\"none\" stroke=\"" WIRE
       "\" stroke-width=\"2.5\" marker-end=\"url(#arrow)\"" (when dashed? " stroke-dasharray=\"8 6\"") "/>"))

(defn glows
  "One pink outline per target ({key [box rx]}); it pulses during every step
   whose :glow names it."
  [targets]
  (str/join
   (for [[k [b rx]] targets
         :let [ws (for [{[t0 t1] :t g :glow} *steps* :when (some #{k} g)] [t0 t1])]
         :when (seq ws)]
     (str "<rect " (attrs (let [{:keys [x y w h]} (grow b 8)] {:x x :y y :width w :height h}))
          " rx=\"" (+ rx 6) "\" fill=\"none\" stroke=\"" PINK "\" stroke-width=\"4\" opacity=\"0\">"
          (anim-opacity
           (concat [[0 0]]
                   (mapcat (fn [[t0 t1]]
                             (concat [[t0 0]]
                                     (for [t (range (+ t0 0.2) (- t1 0.2) 0.25)]
                                       [t (+ 0.3 (* 0.4 (Math/pow (Math/sin (* 2.2 (- t t0))) 2)))])
                                     [[(- t1 0.01) 0]]))
                           ws)))
          "</rect>"))))

(defn code-block
  "Code lines [[y group segs] …] at x, each group lit (a soft band of width
   `w`) during the steps whose :code names it."
  ([code x] (code-block code x 632 14))
  ([code x w size]
   (str
    (str/join
     (for [[y group _] code
           :when group
           :let [ws (for [{[t0 t1] :t c :code} *steps* :when (some #{group} c)] [t0 t1])]
           :when (seq ws)]
       (str "<rect x=\"" (- x 8) "\" y=\"" (- y (+ size 2)) "\" width=\"" w "\" height=\"" (+ size 8) "\" rx=\"5\" fill=\"" ACCENT-BG
            "\" stroke=\"" ACCENT-BORDER "\" opacity=\"0\">"
            (anim-opacity (concat [[0 0]] (mapcat (fn [[t0 t1]] [[t0 0] [(+ t0 0.3) 1] [(- t1 0.3) 1] [t1 0]]) ws)))
            "</rect>")))
    (str/join (for [[y _ segs] code] (code-line x y segs {:size size}))))))

(defn packet [{:keys [path move label color]}]
  (let [[t0 t1] move
        w (+ 28 (* 8.1 (count label)))]
    (str "<g opacity=\"0\">"
         (anim-opacity [[(- t0 0.2) 0] [t0 1] [(+ t1 0.25) 1] [(+ t1 0.5) 0]])
         (motion path t0 t1)
         "<rect x=\"" (- (/ w 2)) "\" y=\"-16\" width=\"" w "\" height=\"32\" rx=\"16\" fill=\"" color "\"/>"
         (text 0 5 (esc label) :text-anchor "middle" :font-size 13 :font-weight 600 :font-family MONO :fill "#ffffff")
         "</g>")))

(defn caption [i {[t0 t1] :t :keys [title] lines :text}]
  (str "<g opacity=\"0\">" (window t0 t1)
       "<circle cx=\"66\" cy=\"636\" r=\"15\" fill=\"" PINK "\"/>"
       (text 66 641 (inc i) :text-anchor "middle" :font-size 14 :font-weight 700 :fill "#ffffff")
       (text 94 642 (esc title) :font-size 20 :font-weight 600 :fill TEXT)
       (str/join (map-indexed (fn [j l] (text 94 (+ 668 (* 21 j)) (esc l) :font-size 15 :fill MUTED)) lines))
       "</g>"))

(defn yours-overlay
  "The opening frame: during the steps marked :yours, everything but the
   developer's two boxes (`boxes`: the controller and the view), the title
   and the caption strip fades back, and each of the two gets a tag."
  [boxes]
  (let [ws (for [{[t0 t1] :t y :yours} *steps* :when y] [t0 t1])]
    (when (seq ws)
      (let [at  (fn [peak] (anim-opacity (concat [[0 0]] (mapcat (fn [[t0 t1]] [[t0 0] [(+ t0 0.3) peak] [(- t1 0.4) peak] [t1 0]]) ws))))
            vis (at 1)]
        (str
         "<mask id=\"yours\">" (rect {:x 0 :y 0 :w W :h H} :fill "#ffffff")
         (rect {:x 0 :y 0 :w W :h 84} :fill "#000000")
         (rect {:x 0 :y 600 :w W :h 120} :fill "#000000")
         (str/join (for [b boxes] (rect (grow b 6) :rx 18 :fill "#000000")))
         "</mask>"
         "<rect x=\"0\" y=\"0\" width=\"" W "\" height=\"" H "\" fill=\"" BODY-BG "\" mask=\"url(#yours)\" opacity=\"0\">"
         (at 0.82) "</rect>"
         ;; the tags
         (str "<g opacity=\"0\">" vis
              (str/join
               (for [{:keys [x y w]} boxes]
                 (str (rect {:x (+ x w -156) :y (- y 14) :w 140 :h 28} :rx 14 :fill PINK)
                      (text (+ x w -86) (+ y 5) "you write this" :text-anchor "middle" :font-size 14 :font-weight 600 :fill "#ffffff"))))
              "</g>"))))))

(defn shift-steps
  "Every time in `steps` moved `d` seconds later."
  [d steps]
  (let [sh (fn [v] (cond (number? v) (+ v d)
                         (and (vector? v) (number? (first v))) (assoc v 0 (+ (first v) d) 1 (if (number? (second v)) (+ (second v) d) (second v)))
                         :else v))
        sh-packet #(update % :move sh)]
    (for [s steps]
      (cond-> (reduce-kv (fn [m k v] (assoc m k (if (#{:title :text :glow :code} k) v (sh v)))) {} s)
        (:packet s)  (assoc :packet (sh-packet (:packet s)))
        (:packets s) (assoc :packets (mapv sh-packet (:packets s)))))))

(defn page
  "The shared chrome around a scene's `body`: background, title, the glows
   (`glow-targets` {key [box rx]}), the moving packets (a step's :packet or
   :packets), the caption strip."
  [{:keys [title subtitle generated yours]} body glow-targets]
  (str
   "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " W " " H "\" width=\"" W "\" height=\"" H "\" font-family=\"" SANS "\">\n"
   "<!-- GENERATED by scripts/component_flow_svg.clj — edit that, not this file. " generated
   ", " *T* " s loop. `bb component-flow-gif` renders the GIF. -->\n"
   "<defs><marker id=\"arrow\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" markerWidth=\"7\" markerHeight=\"7\" orient=\"auto-start-reverse\">"
   "<path d=\"M 0 0 L 10 5 L 0 10 z\" fill=\"" WIRE "\"/></marker></defs>\n"
   (rect {:x 0 :y 0 :w W :h H} :fill BODY-BG)
   (text 32 46 (esc title) :font-size 24 :font-weight 600 :fill TEXT)
   (text 32 72 (esc subtitle) :font-size 14 :fill MUTED)
   body
   (glows glow-targets)
   (str/join (map packet (mapcat #(cond-> (vec (:packets %)) (:packet %) (conj (:packet %))) *steps*)))
   (yours-overlay yours)
   ;; caption strip
   (rect {:x 32 :y 606 :w 1216 :h 98} :rx 16 :fill "#ffffff" :stroke ACCENT-BORDER :stroke-width 1.5)
   (str/join (map-indexed caption *steps*))
   "\n</svg>\n"))

(defn frame
  "Scene 1's picture: the left column, then the flow on the right with the
   chans on its left edge."
  [{:keys [left controller-sub controller-code view-sub view-code extras glow-targets] :as scene}]
  (page scene
        (str
         left
         (rect flow-box :rx 16 :fill "#ffffff" :fill-opacity "0.55" :stroke BOX-BORDER :stroke-width 1.5)
         (text 444 136 "flow" :font-size 26 :font-weight 600 :fill TEXT)
         (proc-box controller "controller" controller-sub)
         (code-block controller-code (+ (:x controller) 24))
         (proc-box view "view" view-sub)
         (code-block view-code (+ (:x view) 24))
         (wire [[478 230] [536 230]])
         (wire [[880 330] [880 396]])
         (text 894 368 ":to-view → :from-controller" :font-size 13 :font-family MONO :fill MUTED)
         (wire [[538 520] [480 520]])
         (chan-pill in-chan "in-chan")
         (chan-pill out-chan "out-chan")
         extras)
        (merge {:flow       [flow-box 16]
                :controller [controller 14]
                :view       [view 14]
                :in-chan    [(pill-box in-chan) 20]
                :out-chan   [(pill-box out-chan) 20]}
               glow-targets)))

(defn cursor-click
  "A pointer that moves onto (x, y) and clicks at t."
  [t [x y]]
  (str "<g opacity=\"0\">" (anim-opacity [[(- t 0.8) 0] [(- t 0.6) 1] [(+ t 0.6) 1] [(+ t 0.9) 0]])
       "<animateTransform attributeName=\"transform\" type=\"translate\" values=\""
       (+ x 74) " " (+ y 46) ";" (+ x 74) " " (+ y 46) ";" x " " y ";" x " " y "\" keyTimes=\"0;"
       (kt (- t 0.6)) ";" (kt (- t 0.05)) ";1\" " (dur) "/>"
       "<path d=\"M0 0 L0 18 L5 14 L9 22 L12.5 20.5 L8.5 12.5 L14.5 12.5 Z\" fill=\"" TEXT "\" stroke=\"#ffffff\" stroke-width=\"1.4\"/></g>"))

;;; ---------------------------------------------------------------------------
;;; Scene 1 — a click on the counter (one-shot), then the idea of send-event!
;;; ---------------------------------------------------------------------------

(def counter-steps
  [{:t [0.0 3.8]
    :glow [:flow]
    :title "A component is a flow"
    :text ["Two procs between an in-chan and an out-chan: the controller runs the state machine, the view renders."
           "Both are found once, when the flow is built; every event then travels the same wires."]}
   {:t [3.8 7.8]
    :click 4.0
    :packet {:path [[334 230] [420 230] [538 230]] :move [4.3 5.3] :label "[:INCREMENT {}] + {:count 1}" :color EVENT-C}
    :glow [:in-chan]
    :title "A click puts an event on the in-chan"
    :text ["The POST carries the event and the component's signals: [:INCREMENT {}] with {\"counter1\": {\"count\": 1, \"state\": \"displayed\"}}."]}
   {:t [7.8 11.6]
    :glow [:controller]
    :code [:inc :machine]
    :title "The controller runs the state machine"
    :text [":INCREMENT → (increment ctx {}) → :memory {:count 2}."
           "Nothing is kept: the next event starts again from what the page sends."]}
   {:t [11.6 15.0]
    :packet {:path [[880 330] [880 398]] :move [11.8 12.6] :label "ctx {:memory {:count 2}}" :color CTX-C}
    :glow [:view]
    :title "The new ctx goes to the view"
    :text ["One connection inside the flow: the controller's :to-view feeds the view's :from-controller."]}
   {:t [15.0 18.4]
    :glow [:view]
    :code [:render]
    :title "The view renders"
    :text ["(counter ctx) → hiccup: the new count, and the signals the page will send back next time."]}
   {:t [18.4 23.4]
    :packet {:path [[538 520] [420 520] [334 520]] :move [18.6 19.6] :label "<div id=\"counter1\"> … 2 …" :color HTML-C}
    :glow [:out-chan :card :signals]
    :morph 19.6
    :title "Out of the out-chan, into the page"
    :text ["Datastar morphs #counter1 by id, and the signals now hold {\"counter1\": {\"count\": 2, …}}."
           "A one-shot component's flow stops here: every click builds its own."]}])

(def counter-opening
  {:t [0.0 4.6]
   :yours true
   :title "You write two things: a controller and a view"
   :text ["The controller is a state machine of plain functions; the view is a render fn, ctx → hiccup."
          "taia.ds does the rest: the route, finding your two namespaces, the flow and its chans, the signals, the morph."]})

(def card {:x 52 :y 142 :w 260 :h 210})
(def signals {:x 52 :y 404 :w 260 :h 92})

(defn counter-scene []
  (let [click-t (some :click *steps*)
        morph-t (some :morph *steps*)]
    (frame
     {:title "taia.ds — a component is a flow"
      :subtitle "clojure.core.async.flow · in-chan → controller → view → out-chan"
      :generated "A click on the counter, through the component flow"
      :yours [controller view]
      :controller-sub "basics.counter-controller"
      :controller-code
      [[210 :inc ["(defn " ["increment" kw-c] " [ctx _] (update-in ctx [" [":memory" kw-c] " " [":count" kw-c] "] inc))"]]
       [240 nil ["(def machine"]]
       [260 nil ["  {" [":id" kw-c] " " [":counter" kw-c] " " [":initial" kw-c] " " [":displayed" kw-c]]]
       [280 nil ["   " [":states" kw-c] " {" [":displayed" kw-c] " {" [":on" kw-c] " {" [":INIT" kw-c] "      {" [":actions" kw-c] " seed}"]]
       [300 :machine ["                             " [":INCREMENT" kw-c] " {" [":actions" kw-c] " increment} …}}}})"]]]
      :view-sub "basics.counter"
      :view-code
      [[478 nil ["(defn " ["counter" kw-c] " [{" [":keys" kw-c] " [component-id memory] " [":as" kw-c] " ctx}]"]]
       [498 :render ["  [" [":div.card" kw-c] " {" [":id" kw-c] " component-id " [":data-signals" kw-c] " (ds/ctx->data-signals ctx)}"]]
       [518 :render ["   [" [":p.big" kw-c] " (str (" [":count" kw-c] " memory " ["0" num-c] "))]"]]
       [538 nil ["   [" [":button" kw-c] " {" ["\"data-on:click\"" str-c] " (post component-id " ["\"increment\"" str-c] ")} " ["\"+\"" str-c] "] …])"]]]
      :glow-targets {:card    [card 12]
                     :signals [signals 12]}
      :left
      (str
       (rect left-col :rx 16 :fill "#ffffff" :stroke BOX-BORDER :stroke-width 1.5)
       (text 52 128 "BROWSER" :font-size 13 :font-weight 600 :fill MUTED :letter-spacing "0.08em")
       (rect card :rx 12 :fill "#ffffff" :stroke "#eee8ea" :stroke-width 1.5)
       (text 72 180 "Counter" :font-size 20 :font-weight 600 :fill TEXT)
       (flip morph-t
             (text 72 258 "1" :font-size 60 :font-weight 700 :fill TEXT)
             (text 72 258 "2" :font-size 60 :font-weight 700 :fill TEXT))
       (rect {:x 72 :y 282 :w 48 :h 38} :rx 19 :fill PINK)
       (text 96 307 "−" :text-anchor "middle" :font-size 20 :font-weight 600 :fill "#ffffff")
       (rect {:x 128 :y 282 :w 48 :h 38} :rx 19 :fill PINK)
       "<rect x=\"128\" y=\"282\" width=\"48\" height=\"38\" rx=\"19\" fill=\"" ACCENT-TEXT "\" opacity=\"0\">"
       (anim-opacity [[click-t 0] [(+ click-t 0.05) 1] [(+ click-t 0.35) 1] [(+ click-t 0.5) 0]]) "</rect>"
       (text 152 307 "+" :text-anchor "middle" :font-size 20 :font-weight 600 :fill "#ffffff")
       (rect {:x 184 :y 282 :w 84 :h 38} :rx 19 :fill "#eef1f5")
       (text 226 306 "Reset" :text-anchor "middle" :font-size 15 :fill TEXT)
       (cursor-click click-t [156 304])
       (text 52 392 "SIGNALS · data-signals" :font-size 13 :font-weight 600 :fill MUTED :letter-spacing "0.08em")
       (rect signals :rx 12 :fill SESSION-BG :stroke SESSION-DIVIDER :stroke-width 1.5)
       (code-line 68 432 ["{" ["\"counter1\"" str-c] ":"])
       (flip morph-t
             (code-line 68 454 [" {" ["\"count\"" str-c] ": " ["1" num-c] ","])
             (code-line 68 454 [" {" ["\"count\"" str-c] ": " ["2" num-c] ","]))
       (code-line 68 476 ["  " ["\"state\"" str-c] ": " ["\"displayed\"" str-c] "}}"])
       (text 52 552 "the signals ride every request;" :font-size 13 :fill MUTED)
       (text 52 570 "this component's slice becomes :memory" :font-size 13 :fill MUTED)
       (wire [[334 230] [362 230]])
       (wire [[362 520] [336 520]]))
      :extras ""})))

;;; ---------------------------------------------------------------------------
;;; Scene 2 — a connected component (the live counter) with two senders on one
;;; in-chan: the browser on the left, a REPL send-event! on the right
;;; ---------------------------------------------------------------------------

(def browser2 {:x 32 :y 140 :w 210 :h 450})
(def card2 {:x 46 :y 186 :w 182 :h 250})
(def repl2 {:x 998 :y 140 :w 250 :h 300})
(def flow2 {:x 310 :y 140 :w 656 :h 450})
(def controller2 {:x 386 :y 166 :w 560 :h 206})
(def view2 {:x 386 :y 410 :w 560 :h 162})
(def in-chan2 [640 108])
(def out-chan2 [310 480])
(def stream-badge {:x 50 :y 448 :w 132 :h 22})
(def bus-y 108)
(def browser-x 137)
(def repl-x 1123)

(def live-steps
  [{:t [0.0 4.6]
    :yours true
    :title "You write two things: a controller and a view"
    :text ["The controller is a state machine of plain functions; the view is a render fn, ctx → hiccup."
           "taia.ds does the rest: the routes, the flow and its chans, the SSE stream, send-event!, the morph."]}
   {:t [4.6 8.6]
    :glow [:flow :in-chan :stream]
    :title "A connected component: one in-chan, two ways in"
    :text ["The live counter page opened an SSE stream, so its flow stays up."
           "The browser and the server both send it events through the same in-chan."]}
   {:t [8.6 12.8]
    :click 9.0
    :packet {:path [[browser-x 140] [browser-x bus-y] [640 bus-y] [640 168]] :move [9.4 10.8] :label "[:INCREMENT {}] + {:count 1}" :color EVENT-C}
    :glow [:in-chan :browser]
    :code [:inc :machine]
    :title "A click: +1 into the in-chan"
    :text ["@post('/ds/nds-connect/live.live-counter/live1/increment') carries the tab's signals,"
           "{\"live1\": {\"count\": 1, \"state\": \"displayed\"}}; increment takes the count from the memory: 1 → 2."]}
   {:t [12.8 16.6]
    :packets [{:path [[666 374] [666 410]] :move [13.0 13.6] :label "ctx {:count 2}" :color CTX-C}
              {:path [[384 480] [310 480] [190 480]] :move [14.0 15.0] :label "<div#live1> 2" :color HTML-C}]
    :glow [:view :out-chan :card]
    :code [:render]
    :morph 15.0
    :title "…and the render comes back"
    :text ["The view renders the new ctx, and the POST is answered with it: the tab shows 2,"
           "and its signals now hold {\"live1\": {\"count\": 2, \"state\": \"displayed\"}}."]}
   {:t [16.6 21.2]
    :type 16.9
    :packet {:path [[repl-x 140] [repl-x bus-y] [640 bus-y] [640 168]] :move [17.8 19.2] :label "[:INCREMENT {:count 2}]" :color SERVER-C}
    :reply 18.0
    :glow [:repl :in-chan]
    :title "The REPL: the same event, into the same in-chan"
    :text ["(ds/send-event! \"live1\" \"demo-user\" \"increment\" {:count 2}): a server event has no request"
           "and no signals, so the REPL sends the memory as params. It answers true at once."]}
   {:t [21.2 24.8]
    :glow [:controller]
    :code [:inc :machine]
    :title "The same controller code runs"
    :text ["increment takes the count from the params this time: 2 → 3. Nothing is kept on the server."]}
   {:t [24.8 29.2]
    :packets [{:path [[666 374] [666 410]] :move [25.0 25.6] :label "ctx {:count 3}" :color CTX-C}
              {:path [[384 480] [310 480] [190 480]] :move [26.0 27.0] :label "<div#live1> 3" :color HTML-C}]
    :glow [:out-chan :card :stream]
    :code [:render]
    :morph 27.0
    :title "Down the open stream, into the page"
    :text ["Nobody asked this time, so the render is pushed down the page's SSE stream. The tab shows 3,"
           "and its signals hold {\"live1\": {\"count\": 3, …}} for the next click."]}
   {:t [29.2 33.4]
    :glow [:browser :repl :in-chan]
    :title "Two senders, one in-chan"
    :text ["A click and a send-event! are the same kind of message, handled by the same controller and view."
           "You wrote those two; the route, the flow, its chans and the stream are taia.ds."]}])

(defn live-scene []
  (let [click-t (some :click *steps*)
        type-t (some :type *steps*)
        reply-t (some :reply *steps*)
        [morph1 morph2] (keep :morph *steps*)
        repl-line (fn [y segs] (code-line (+ (:x repl2) 18) y segs {:size 13 :fill "#e5e7eb"}))
        c-comment "#7d8b85"
        c-str "#86efac"
        c-out "#f9a8d4"
        big (fn [n] (text 62 292 n :font-size 56 :font-weight 700 :fill TEXT))
        signals-of (fn [n]
                     (str (code-line 58 338 ["{" ["\"live1\"" str-c] ": {" ["\"count\"" str-c] ": " [(str n) num-c] ","] {:size 11})
                          (code-line 58 354 ["  " ["\"state\"" str-c] ": " ["\"displayed\"" str-c] "}}"] {:size 11})))
        cx (+ (:x controller2) 22)
        vx (+ (:x view2) 22)]
    (page
     {:title "taia.ds — a connected component: the browser and the REPL, one in-chan"
      :subtitle "a click (POST) and a server event (send-event!) enter the same flow, and run the same controller and view"
      :generated "Two senders, one in-chan: a click and a REPL send-event! into the connected live counter's flow"
      :yours [controller2 view2]}
     (str
      ;; ---- the browser (left)
      (rect browser2 :rx 16 :fill "#ffffff" :stroke BOX-BORDER :stroke-width 1.5)
      (text 50 166 "BROWSER" :font-size 13 :font-weight 600 :fill MUTED :letter-spacing "0.08em")
      (rect card2 :rx 12 :fill "#ffffff" :stroke "#eee8ea" :stroke-width 1.5)
      (text 62 220 "Live counter" :font-size 18 :font-weight 600 :fill TEXT)
      (flip morph1 (big "1") (flip morph2 (big "2") (big "3")))
      (text 58 318 "signals (data-signals)" :font-size 12 :fill MUTED)
      (flip morph1 (signals-of 1) (flip morph2 (signals-of 2) (signals-of 3)))
      (rect {:x 62 :y 366 :w 150 :h 36} :rx 18 :fill PINK)
      "<rect x=\"62\" y=\"366\" width=\"150\" height=\"36\" rx=\"18\" fill=\"" ACCENT-TEXT "\" opacity=\"0\">"
      (anim-opacity [[click-t 0] [(+ click-t 0.05) 1] [(+ click-t 0.35) 1] [(+ click-t 0.5) 0]]) "</rect>"
      (text 137 389 "+1" :text-anchor "middle" :font-size 15 :font-weight 600 :fill "#ffffff")
      (cursor-click click-t [150 386])
      (rect stream-badge :rx 11 :fill "#f0fdf4" :stroke "#bbf7d0" :stroke-width 1.2)
      "<circle cx=\"63\" cy=\"459\" r=\"4.5\" fill=\"" SUCCESS "\">"
      (anim-opacity (for [t (range 0 (+ *T* 0.01) 0.5)] [t (if (even? (int (* 2 t))) 1 0.35)])) "</circle>"
      (text 73 464 "SSE stream open" :font-size 12 :fill SUCCESS :font-weight 500)
      (text 50 530 "a click POSTs to the" :font-size 12 :fill MUTED)
      (text 50 546 "nds-connect path: it enters" :font-size 12 :fill MUTED)
      (text 50 562 "the flow the stream opened" :font-size 12 :fill MUTED)

      ;; ---- the REPL (right)
      (rect repl2 :rx 16 :fill CHROME :stroke CHROME :stroke-width 1.5)
      (text 1016 172 "REPL" :font-size 13 :font-weight 600 :fill "#9ca3af" :letter-spacing "0.08em")
      (text 1232 172 "the running app" :font-size 11 :fill c-comment :text-anchor "end")
      (repl-line 204 ["(ds/connections)"])
      (repl-line 222 [[";; => {\"live1\"" c-comment]])
      (repl-line 240 [[";;     [\"demo-user\"]}" c-comment]])
      (appear type-t (repl-line 278 ["(ds/send-event!"]))
      (appear (+ type-t 0.25) (repl-line 296 ["  " ["\"live1\"" c-str] " " ["\"demo-user\"" c-str]]))
      (appear (+ type-t 0.5) (repl-line 314 ["  " ["\"increment\"" c-str] " {" [":count" "#f9a8d4"] " " ["2" num-c] "})"]))
      (appear reply-t (repl-line 340 [[";; => true" c-out]]))
      (repl-line 392 [[";; no signals: the memory" c-comment]])
      (repl-line 410 [[";; goes as params" c-comment]])

      ;; ---- the senders' wires: one line along the top, into the in-chan
      (wire [[browser-x 140] [browser-x bus-y] [582 bus-y]])
      (wire [[repl-x 140] [repl-x bus-y] [698 bus-y]])
      (text 300 100 "POST (a click)" :font-size 12 :font-family MONO :fill MUTED)
      (text 980 100 "send-event!" :font-size 12 :font-family MONO :fill MUTED :text-anchor "end")

      ;; ---- the flow
      (rect flow2 :rx 16 :fill "#ffffff" :fill-opacity "0.55" :stroke BOX-BORDER :stroke-width 1.5)
      (text 326 176 "flow" :font-size 22 :font-weight 600 :fill TEXT)
      (proc-box controller2 "controller" "live.live-counter-controller")
      (code-block [[250 :inc ["(defn- " ["increment" kw-c] " [ctx {" [":keys" kw-c] " [count]}]"]]
                   [270 :inc ["  (assoc-in ctx [" [":memory" kw-c] " " [":count" kw-c] "]"]]
                   [290 :inc ["            (inc (or count (get-in ctx [" [":memory" kw-c] " " [":count" kw-c] "]) " ["0" num-c] "))))"]]
                   [322 nil ["(def machine"]]
                   [342 :machine ["  … {" [":INCREMENT" kw-c] " {" [":actions" kw-c] " increment} …})"]]]
                  cx 528 13)
      (proc-box view2 "view" "live.live-counter")
      (code-block [[486 nil ["(defn " ["live-counter" kw-c] " [{" [":keys" kw-c] " [component-id memory] " [":as" kw-c] " ctx}]"]]
                   [504 :render ["  [" [":div.card" kw-c] " {" [":data-signals" kw-c] " (ds/ctx->data-signals ctx) …}"]]
                   [522 :render ["   [" [":p.big" kw-c] " (str (" [":count" kw-c] " memory " ["0" num-c] "))]"]]
                   [540 nil ["   [" [":button" kw-c] " … " ["\"+1\"" str-c] "] …])"]]]
                  vx 528 13)
      (wire [[640 128] [640 164]])
      (wire [[666 374] [666 408]])
      (text 680 396 ":to-view → :from-controller" :font-size 12 :font-family MONO :fill MUTED)
      (wire [[384 480] [368 480]])
      (wire [[254 480] [244 480]])
      (chan-pill in-chan2 "in-chan")
      (chan-pill out-chan2 "out-chan"))
     {:flow       [flow2 16]
      :controller [controller2 14]
      :view       [view2 14]
      :in-chan    [(pill-box in-chan2) 20]
      :out-chan   [(pill-box out-chan2) 20]
      :browser    [browser2 16]
      :card       [card2 12]
      :repl       [repl2 16]
      :stream     [stream-badge 11]})))

;;; Write both

(defn write! [file T steps scene]
  (binding [*T* T *steps* steps]
    (spit file (scene)))
  (println "wrote" file))

(write! "docs/img/component-flow.svg" 28.6 (vec (cons counter-opening (shift-steps 4.6 counter-steps))) counter-scene)
(write! "docs/img/repl-event-flow.svg" 33.8 live-steps live-scene)
