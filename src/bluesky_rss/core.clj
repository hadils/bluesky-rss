(ns bluesky-rss.core
  (:require
   [duct.logger :as log]
   [integrant.core :as ig]
   [org.httpkit.client :as http]
   [clojure.xml :as xml]
   [clojure.zip :as zip]
   [clojure.data.zip.xml :as zx]
   [muuntaja.core :as muuntaja]
   [tick.core :as t]))

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

(defn read-rss [url]
  (-> url
      fetch-feed
      parse-xml
      extract-items))

(defn login [url handle password]
  (let [response @(http/post
                   (str url "/xrpc/com.atproto.server.createSession")
                   {:headers {"Content-Type" "application/json"}
                    :body (muuntaja/encode
                           "application/json"
                           {:identifier handle
                            :password password})})]
    (->> response
         :body
         (muuntaja/decode "application/json"))))

(defn refresh-token [url refresh-jwt]
  (let [response @(http/post
                   (str url "/xrpc/com.atproto.server.refreshSession")
                   {:headers {"Content-Type" "application/json"
                              "Authorization" (str "Bearer " refresh-jwt)}})]
    (->> response
         :body
         (muuntaja/decode "application/json")
         :accessJwt)))

(defn utf8-byte-span [s pattern]
  (->> (re-seq pattern s)
       (map (fn [full]
              (let [start (-> s (.indexOf full))
                    pre-bytes (.getBytes (.substring s 0 start) "UTF-8")
                    full-bytes (.getBytes full "UTF-8")]
                {:text full
                 :byte-start (count pre-bytes)
                 :byte-end (+ (count pre-bytes) (count full-bytes))})))))

(def mention-pattern #"@[\w.-]+\.[a-z]+")
(def url-pattern #"https?://[^\s\)\]]+")

(defn resolve-handle [base-url handle]
  (let [resp @(http/get (str base-url "/xrpc/com.atproto.identity.resolveHandle")
                        {:query-params {"handle" handle}
                         :headers {"Accept" "application/json"}})]
    (when (= 200 (:status resp))
      (->> (:body resp)
           (muuntaja/decode "application/json")
           :did))))

(defn build-facets [text base-url]
  (let [mentions (utf8-byte-span text mention-pattern)
        urls (utf8-byte-span text url-pattern)]
    (vec
     (concat
      (for [{:keys [text byte-start byte-end]} mentions
            :let [handle (subs text 1) ; remove "@"
                  did (resolve-handle base-url handle)]
            :when did]
        {:index {:byteStart byte-start :byteEnd byte-end}
         :features [{:$type "app.bsky.richtext.facet#mention"
                     :did did}]})

      (for [{:keys [text byte-start byte-end]} urls]
        {:index {:byteStart byte-start :byteEnd byte-end}
         :features [{:$type "app.bsky.richtext.facet#link"
                     :uri text}]})))))

(defn post-to-bluesky
  [url jwt handle text]
  (let [response @(http/post
                   (str url "/xrpc/com.atproto.repo.createRecord")
                   {:headers {"Content-Type" "application/json"
                              "Authorization" (str "Bearer " jwt)}
                    :body (->>
                           {:repo handle
                            :collection "app.bsky.feed.post"
                            :record {:text text
                                     :$type "app.bsky.feed.post"
                                     :createdAt (str (t/now))
                                     :facets (build-facets text url)}}
                           (muuntaja/encode "application/json"))})]
    (->> response
         :body
         (muuntaja/decode "application/json"))))

(defn post-rss [rss {:keys [url handle access-jwt]}]
  (doseq [{:keys [title link]} (take 3 (first (:rss rss)))]
    (let [post (str (org.jsoup.parser.Parser/unescapeEntities title false) "\n" link)]
      (println "Post:" post)
      (post-to-bluesky url access-jwt handle post))))

(defmethod ig/init-key ::feed [_key {:keys [logger urls]}]
  (log/log logger :info ::feed urls)
  {:rss (mapv (fn [url]
                (log/log logger :info ::feed url)
                (read-rss url)) urls)})

(defmethod ig/init-key ::user [_key {:keys [logger url handle password] :as opts}]
  (log/log logger :info ::user [url handle password])
  (let [{:keys [accessJwt refreshJwt]} (login url handle password)]
    {:logger logger
     :url url
     :handle handle
     :access-jwt accessJwt
     :refresh-jwt refreshJwt}))
