(ns hsbox.launch
  (:require [hsbox.stats :as stats])
  (:require [hsbox.util :refer [file-exists? file-name]])
  (:require [hsbox.db :as db])
  (:require [hsbox.version :refer [os-name]])
  (:require [clojure.java.io :as io])
  (:require [ring.util.codec :refer [url-encode]]))

(taoensso.timbre/refer-timbre)

(def HEADSHOTBOX-WATERMARK "// Generated by Headshot Box")

(defn generated-by-hsbox [vdm-path]
  (.startsWith (slurp vdm-path) HEADSHOTBOX-WATERMARK))

(defn- append-maybe [x pred xs]
  (if pred
    (conj x xs)
    x))

(defn- sec-to-tick [demo sec]
  (stats/seconds-to-ticks sec (:tickrate demo)))

(defn- fade-to-black [tick]
  {:factory   "ScreenFadeStart"
   :tick      tick
   :duration  "1.000"
   :holdtime  "1.000"
   :FFADE_IN  "1"
   :FFADE_OUT "1"
   :r         "0"
   :g         "0"
   :b         "0"
   :a         "255"
   })

(defn- generate-highlight-enemy-pov [demo kill]
  (let [kill-context (sec-to-tick demo 5)
        after-kill-context (sec-to-tick demo 2)]
    (-> []
        (append-maybe
          (or (= (:tick-before kill) 0)
              (> (- (:tick kill) (:tick-before kill)) (+ kill-context after-kill-context)))
          {:factory    "SkipAhead"
           :tick       (if (= 0 (:tick-before kill))
                         0
                         (+ (:tick-before kill) after-kill-context))
           :skiptotick (- (:tick kill) kill-context)
           })
        (append-maybe true
                      {:factory  "PlayCommands"
                       :tick     (max (- (:tick kill) kill-context) (:tick-before kill))
                       :commands (str "spec_player_by_accountid " (:victim kill))})
        (append-maybe
          (> (- (:tick-after kill) (:tick kill)) (sec-to-tick demo 1))
          (fade-to-black (:tick kill))))))

(defn- quit-or-disconnect []
  (if (:vdm_quit_after_playback (db/get-config))
    "quit"
    "disconnect"))

(defn- vdm-highlights [demo steamid]
  (let [killed-by-steamid (fn [kill] (= steamid (:attacker kill)))
        kills (mapcat #(filter killed-by-steamid (:deaths %)) (:rounds demo))
        tick-before (conj (map #(:tick %) kills) 0)
        tick-after (conj (vec (map #(:tick %) (rest kills))) (+ (:tick (last kills)) 9999))
        augmented-kills (map #(assoc %3 :tick-before % :tick-after %2) tick-before tick-after kills)
        cfg (:vdm_cfg (db/get-config))]
    {:tick 0
     :vdm  (-> []
               (append-maybe (not (empty? cfg))
                             {:factory  "PlayCommands"
                              :tick     0
                              :commands (str "exec " cfg)})
               (into (mapcat #(generate-highlight-enemy-pov demo %) augmented-kills))
               (append-maybe true {:factory  "PlayCommands"
                                   :tick     (+ (:tick (last augmented-kills)) (stats/seconds-to-ticks 1 (:tickrate demo)))
                                   :commands (quit-or-disconnect)}))}))

(defn- generate-pov [demo round steamid]
  (let [death (first (filter #(= (:victim %) steamid) (:deaths round)))
        tick-jump (if death
                    (+ (:tick death) (sec-to-tick demo 3))
                    (+ (:tick_end round) (sec-to-tick demo 5)))]
    (if (nil? (:next-round-tick round))
      [{:factory  "PlayCommands"
        :tick     tick-jump
        :commands (quit-or-disconnect)}]
      [(fade-to-black (- tick-jump (sec-to-tick demo 1)))
       {:factory    "SkipAhead"
        :tick       tick-jump
        :skiptotick (+ (:next-round-tick round) (sec-to-tick demo 15))
        }])))

(defn vdm-pov [demo steamid]
  (let [cfg (:vdm_cfg (db/get-config))
        rounds (filter #(get (:players %) steamid) (:rounds demo))
        tick-after (conj (vec (map #(:tick %) (rest rounds))) nil)
        augmented-rounds (map #(assoc %2 :next-round-tick %) tick-after rounds)]
    {:tick 0
     :vdm  (-> []
               (append-maybe (not (empty? cfg))
                             {:factory  "PlayCommands"
                              :tick     0
                              :commands (str "exec " cfg)})
               (append-maybe true {:factory  "PlayCommands"
                                   :tick     0
                                   :commands (str "spec_player_by_accountid " steamid)})
               (append-maybe true
                             {:factory    "SkipAhead"
                              :tick       0
                              :skiptotick (+ (:tick (first augmented-rounds)) (sec-to-tick demo 15))
                              })
               (into (mapcat #(generate-pov demo % steamid) augmented-rounds)))}))

(defn vdm-round-highlights [demo steamid round-number]
  (let [round (nth (:rounds demo) (dec round-number))
        kills (filter #(= (:attacker %) steamid) (:deaths round))
        _ (assert (not (empty? kills)))
        context-before-kill 2
        close-kills 2
        context-after-kill 1
        start-tick (:tick round)
        kill-pairs (map vector kills (rest kills))
        userid (get-in round [:userid steamid])]
    {:tick     start-tick
     :vdm      (-> [{:factory  "PlayCommands"
                     :tick     start-tick
                     :commands (str "spec_player_by_accountid " steamid)}
                    {:factory  "PlayCommands"
                     :tick     start-tick
                     :commands (str "mirv_deathmsg block !" userid " *")}
                    {:factory  "PlayCommands"
                     :tick     start-tick
                     :commands (str "mirv_deathmsg highLightId " userid)}
                    {:factory  "PlayCommands"
                     :tick     start-tick
                     :commands (str "mirv_deathmsg cfg noticeLifeTime 200")}
                    {:factory  "PlayCommands"
                     :tick     (+ (:tick (last kills)) (sec-to-tick demo (inc context-after-kill)))
                     :commands "disconnect"}]
                   (append-maybe true
                                 {:factory  "PlayCommands"
                                  :tick     start-tick
                                  :commands "exec movie"})
                   ; TODO more context for nades
                   ; TODO slowmo for jumpshot
                   (into (filter identity
                                 (apply concat
                                        (map
                                          #(if (or (>= (:penetrated %) 1) (:smoke %))
                                            [{:factory  "PlayCommands"
                                              :tick     (- (:tick %) (sec-to-tick demo 1))
                                              :commands "spec_show_xray 1"}
                                             {:factory  "PlayCommands"
                                              :tick     (+ (:tick %) (sec-to-tick demo (* 0.8 context-after-kill)))
                                              :commands "spec_show_xray 0"}])
                                          kills)))))
     :commands (:commands
                 (reduce
                   #(let [a (:tick (first %2))
                          b (:tick (second %2))]
                     (if (> (- b a) (sec-to-tick demo (+ context-after-kill context-before-kill close-kills)))
                       (let [sleep-start-tick (+ a (sec-to-tick demo context-after-kill))
                             sleep-ticks (- (- b (sec-to-tick demo context-before-kill)) sleep-start-tick)]
                         (-> %
                             (assoc :commands (conj (:commands %)
                                                    [:sleep (* (:tickrate demo) (- sleep-start-tick (:last-tick %)))]
                                                    [:stop]
                                                    [:sleep (* (:tickrate demo) sleep-ticks)]
                                                    [:start]))
                             (assoc :last-tick (- b (sec-to-tick demo context-before-kill)))))
                       %))
                   {:commands [[:sleep (- (* (:tickrate demo) (- (:tick (first kills)) start-tick)) context-before-kill 15)]
                               [:start]]
                    :last-tick (- (:tick (first kills)) (sec-to-tick demo context-before-kill))}
                   kill-pairs))}))

(defn vdm-watch [demo steamid tick tick-end]
  (let [user-id (get (:player_slots demo) steamid 0)
        cfg (:vdm_cfg (db/get-config))]
    {:tick tick
     :vdm  (-> []
               ; spec_player seems to be working more often than spec_player_by_accountid
               (append-maybe (:player_slots demo)
                             {:factory  "PlayCommands"
                              :tick     (or tick 0)
                              :commands (str "spec_player " (inc user-id))})
               ; but spec_player_by_accountid works without player_slots so we'll keep both
               (append-maybe true {:factory  "PlayCommands"
                                   :tick     (or tick 0)
                                   :commands (str "spec_player_by_accountid " steamid)})
               ; spec_lock also, cause why not? (doesn't seem to work though)
               ;(append-maybe true {:factory  "PlayCommands"
               ;                    :tick     (or tick 0)
               ;                    :commands (str "spec_lock_to_accountid " steamid)})
               (append-maybe (not (empty? cfg))
                             {:factory  "PlayCommands"
                              :tick     (or tick 0)
                              :commands (str "exec " cfg)})
               (append-maybe tick-end
                             {:factory  "PlayCommands"
                              :tick     tick-end
                              :commands (quit-or-disconnect)}))}))

(defn generate-command [number command]
  (let [line (fn [key value] (str "\t\t" key " \"" value "\"\n"))
        content (apply str (map #(line (name (first %)) (second %))
                                (-> command
                                    (assoc :starttick (:tick command)
                                           :name ".")
                                    (dissoc :tick))))]
    (str "\t\"" number "\"\n"
         "\t{\n"
         content
         "\t}\n")))

(defn generate-vdm [commands]
  (str HEADSHOTBOX-WATERMARK
       "\ndemoactions\n{\n"
       (apply str
              (mapv #(generate-command (first %) (second %))
                    (map vector (rest (range)) (sort #(compare (:tick %) (:tick %2)) commands))))
       "}\n"))

(defn delete-vdm [vdm-path]
  (debug "Deleting vdm file" vdm-path)
  (io/delete-file vdm-path true))

(defn kill-csgo-process []
  (if (= os-name "windows")
    (clojure.java.shell/sh "taskkill" "/im" "csgo.exe" "/F")
    (clojure.java.shell/sh "killall" "-9" "csgo_linux")))

(defn write-vdm-file
  "Write VDM file if needed and return start tick"
  [demo steamid tick round-number highlight]
  (let [demo-path (:path demo)
        vdm-path (str (subs demo-path 0 (- (count demo-path) 4)) ".vdm")
        config (db/get-config)]
    (when
      (and (not (:vdm_enabled config))
           (file-exists? vdm-path))
      (delete-vdm vdm-path))
    (when (and
            (:vdm_enabled config)
            (file-exists? demo-path))
      (if (and (#{"high" "low"} highlight))
        (when (file-exists? vdm-path)
          (delete-vdm vdm-path))
        (do
          (debug "Writing vdm file" vdm-path)
          (let [vdm-info (case highlight
                           "high_enemy" (vdm-highlights demo steamid)
                           "pov" (vdm-pov demo steamid)
                           "round" (vdm-round-highlights demo steamid round-number)
                           (vdm-watch demo steamid tick
                                      (when round-number (+ (:tick_end (nth (:rounds demo) (dec round-number)))
                                                            (stats/seconds-to-ticks 5 (:tickrate demo))))))]
            (spit vdm-path (generate-vdm (:vdm vdm-info)))
            vdm-info))))))

(defn watch [local? demoid steamid round-number tick highlight]
  (let [demo (get stats/demos demoid)
        demo-path (:path demo)
        play-path (if local? demo-path (str "replays/" (file-name demo-path)))]
    (if (nil? demo)
      ""
      (do
        (when round-number
          (assert (<= 1 round-number (count (:rounds demo)))))
        (let [round (when round-number (nth (:rounds demo) (dec round-number)))
              tick (if round
                     (+ (:tick round)
                        (stats/seconds-to-ticks 15 (:tickrate demo)))
                     tick)
              tick (if local?
                     (:tick (write-vdm-file demo steamid tick round-number highlight))
                     tick)]
          (when (and local? (:playdemo_kill_csgo (db/get-config)))
            (kill-csgo-process))
          {:url (str "steam://rungame/730/" steamid "/+playdemo " (url-encode play-path)
                     (when tick (str "@" tick)) " "
                     (when (#{"high" "low"} highlight) steamid)
                     (when (= highlight "low") " lowlights"))
           :tick tick
           :path demo-path})))))

(defn delete-generated-files []
  (let [path (db/get-demo-directory)]
    (->> (clojure.java.io/as-file path)
         file-seq
         (map #(when (and (.endsWith (.getName %) ".vdm") (generated-by-hsbox %))
                (delete-vdm (.getAbsolutePath %))))
         dorun)))
