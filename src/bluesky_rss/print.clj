(ns bluesky-rss.print
  (:require
   [duct.logger :as log]
   [integrant.core :as ig]))

(defmethod ig/init-key ::hello [_key {:keys [level name logger] :as opts}]
  (log/log logger level ::hello {:name name})
  opts)

(defmethod ig/halt-key! ::hello [_key {:keys [level name logger]}]
  (log/log logger level ::goodbye {:name name}))
