(ns bluesky-rss.fetch
  "Web client to fetch RSS feed"
  (:require
   [org.httpkit.client :as http]
   [clojure.xml :as xml]
   [clojure.zip :as zip]
   [clojure.data.zip.xml :as zx]))

(defn fetch-feed [url]
  (-> @(http/get url {:as :stream})
      :body))

(defn parse-xml [stream]
  (-> stream
      (xml/parse)
      (zip/xml-zip)))

(defn extract-items [feed]
  (zx/xml-> feed
            :channel
            :item
            (fn [item]
              {:title (zx/xml1-> item :title zx/text)
               :link  (zx/xml1-> item :link zx/text)
               :desc  (zx/xml1-> item :description zx/text)
               :date  (zx/xml1-> item :pubDate zx/text)})))
