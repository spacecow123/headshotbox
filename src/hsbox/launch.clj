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

(defn- generate-highlight-enemy-pov [demo kill]
  (let [sec-to-tick (fn [sec] (stats/seconds-to-ticks sec (:tickrate demo)))
        kill-context (sec-to-tick 5)
        after-kill-context (sec-to-tick 2)]
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
          (> (- (:tick-after kill) (:tick kill)) (sec-to-tick 1))
          {:factory   "ScreenFadeStart"
           :tick      (:tick kill)
           :duration  "1.000"
           :holdtime  "1.000"
           :FFADE_IN  "1"
           :FFADE_OUT "1"
           :r         "0"
           :g         "0"
           :b         "0"
           :a         "255"
           }))))

(defn- vdm-highlights [demo steamid]
  (let [killed-by-steamid (fn [kill] (= steamid (:attacker kill)))
        kills (mapcat #(filter killed-by-steamid (:deaths %)) (:rounds demo))
        tick-before (conj (map #(:tick %) kills) 0)
        tick-after (conj (vec (map #(:tick %) (rest kills))) (+ (:tick (last kills)) 9999))
        augmented-kills (map #(assoc %3 :tick-before % :tick-after %2) tick-before tick-after kills)
        cfg (:vdm_cfg (db/get-config))]
    (-> []
        (append-maybe (not (empty? cfg))
                      {:factory  "PlayCommands"
                       :tick     0
                       :commands (str "exec " cfg)})
        (into (mapcat #(generate-highlight-enemy-pov demo %) augmented-kills))
        (append-maybe true {:factory  "PlayCommands"
                            :tick     (+ (:tick (last augmented-kills)) (stats/seconds-to-ticks 1 (:tickrate demo)))
                            :commands (if (:vdm_quit_after_playback (db/get-config))
                                        "quit"
                                        "disconnect")}))))

(defn vdm-watch [demo steamid tick & [tick-end]]
  (let [user-id (get (:player_slots demo) steamid 0)
        cfg (:vdm_cfg (db/get-config))]
    (-> []
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
        (append-maybe (and tick-end (:vdm_quit_after_playback (db/get-config)))
                      {:factory  "PlayCommands"
                       :tick     tick-end
                       :commands "quit"}))))

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
                    (map vector (rest (range)) commands)))
       "}\n"))

(defn delete-vdm [vdm-path]
  (debug "Deleting vdm file" vdm-path)
  (io/delete-file vdm-path true))

(defn watch [local? demoid steamid round-number tick highlight]
  (let [demo (get stats/demos demoid)
        demo-path (:path demo)
        vdm-path (str (subs demo-path 0 (- (count demo-path) 4)) ".vdm")
        play-path (if local? demo-path (str "replays/" (file-name demo-path)))]
    (if (nil? demo)
      ""
      (do
        (when round-number
          (assert (<= 1 round-number (count (:rounds demo)))))
        (let [round (when round-number (nth (:rounds demo) (dec round-number)))
              tick (if (not (nil? round))
                     (+ (:tick round)
                        (stats/seconds-to-ticks 15 (:tickrate demo)))
                     tick)]
          ; VDM works only with local requests
          (when local?
            (when
              (and (not (:vdm_enabled (db/get-config)))
                   (file-exists? vdm-path))
              (delete-vdm vdm-path))
            (when (and
                    (:vdm_enabled (db/get-config))
                    (file-exists? demo-path))
              (if (and (#{"high" "low"} highlight))
                (when (file-exists? vdm-path)
                  (delete-vdm vdm-path))
                (do
                  (debug "Writing vdm file" vdm-path)
                  (spit vdm-path (generate-vdm
                                   (if (= "high_enemy" highlight)
                                     (vdm-highlights demo steamid)
                                     (vdm-watch demo steamid tick
                                                (when round (+ (:tick_end round)
                                                               (stats/seconds-to-ticks 5 (:tickrate demo)))))))))))
            (when (and (:playdemo_kill_csgo (db/get-config)))
              (if (= os-name "windows")
                (clojure.java.shell/sh "taskkill" "/im" "csgo.exe" "/F")
                (clojure.java.shell/sh "killall" "-9" "csgo_linux"))))
          {:url (str "steam://rungame/730/" steamid "/+playdemo " (url-encode play-path)
                     (when tick (str "@" tick)) " "
                     (when (#{"high" "low"} highlight) steamid)
                     (when (= highlight "low") " lowlights"))})))))

(defn delete-generated-files []
  (let [path (db/get-demo-directory)]
    (->> (clojure.java.io/as-file path)
         file-seq
         (map #(when (and (.endsWith (.getName %) ".vdm") (generated-by-hsbox %))
                (delete-vdm (.getAbsolutePath %))))
         dorun)))
