(ns hsbox.movie
  (:require [clojure.stacktrace :refer [print-cause-trace]]
            [taoensso.timbre :as timbre]
            [hsbox.launch :refer [kill-csgo-process write-vdm-file]]
            [hsbox.demo :refer [get-demo-info]]
            [hsbox.obs :as obs]
            [hsbox.stats :as stats])
  (:import (hsbox.java SysTrayIcon)
           (java.util.concurrent.locks ReentrantLock)))

(timbre/refer-timbre)

(def obs-lock (ReentrantLock.))
(def obs-start (.newCondition obs-lock))
(def obs-stop (.newCondition obs-lock))

(defn notify-record [recording?]
  (try
    (.lock obs-lock)
    (if recording?
      (.signal obs-start)
      (.signal obs-stop))
    (finally
      (.unlock obs-lock))))

(defn record-round [demoid steamid round]
  (try
    ;(SysTrayIcon/openWebpage url)
    (kill-csgo-process)
    (let [demo (get stats/demos demoid)
          x (assert (:path demo))
          path (clojure.string/replace (:path demo) "\\" "/")
          demo (assoc (get-demo-info path) :path path)
          vdm-info (write-vdm-file demo steamid 0 round "round")
          x (println (:commands vdm-info))]
      (future
        (clojure.java.shell/sh "D:\\usr\\hlae\\HLAE.exe" "-csgoLauncher" "-noGui" "-autoStart"
                               "-gfxEnabled" "true" "-gfxWidth" "1920" "-gfxHeight" "1080" "-gfxFull" "true"
                               "-customLaunchOptions" (str "\"-novid +playdemo " path "@" (:tick vdm-info) "\"")))
      (obs/connect)
      (try
        (.lock obs-lock)
        (.await obs-start)
        (doall (map #(case (first %)
                      :start (future (obs/start-recording))
                      :stop (future (obs/stop-recording))
                      :sleep (Thread/sleep (* 1000 (second %))))
                    (:commands vdm-info)))
        (.await obs-stop)
        (obs/stop-recording)
        (obs/close)
        (kill-csgo-process)
        (finally
          (.unlock obs-lock))))
    (catch Throwable e (do
                         (print-cause-trace e)
                         (error e)))))

(defn make-movie [steamid plays]
  (let [big-plays (stats/get-big-plays steamid plays)]
    (doall (map #(record-round (:demoid %) steamid (:round-number %)) big-plays))
    (clojure.java.shell/sh "e:/tmp/movie/make.bat")))