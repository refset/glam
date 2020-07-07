(ns glam.client.router
  (:require
    [cljs.spec.alpha :as s]
    [taoensso.timbre :as log]
    [clojure.string :as str]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.dom :as dom]
    [com.fulcrologic.guardrails.core :refer [>defn => | ? >def]]
    [goog.object :as g]
    [reitit.core :as r]
    [reitit.frontend :as rf]
    [reitit.frontend.easy :as rfe]
    [reitit.coercion.spec :as rss]
    [glam.client.application :refer [SPA]]
    [glam.client.prn-debug :refer [pprint-str]]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.components :as c :refer [defsc]]))

;; helpers ----------------------------------------------------------------------
(declare routes-by-name
         route-to!)

(defn map-vals [f m]
  (into {} (map (juxt key (comp f val))) m))

(defn make-routes-by-name
  "Returns a map like: {:root {:name :root :path '/'}}"
  [router]
  (let [grouped (group-by (comp :name second) (r/routes router))]
    (map-vals
      ;; takes the path string and adds it as the key :path
      (fn [[[path-str prop-map]]]
        (assoc prop-map :path path-str))
      grouped)))

(>defn url-path->vec
  "Return empty vector on no match, split url path on /"
  [path]
  [string? => (s/coll-of string? :kind vector?)]
  (let [s (->> (str/split path "/")
               (remove empty?)
               vec)]
    (if (seq s) s [])))

(defn route-segment [name]
  (if-let [segment (some-> routes-by-name name :segment last vector)]
    segment
    (throw (js/Error. (str "No matching fulcro segment for route: " (pr-str name))))))

(defn route-href [id]
  (let [conf (id routes-by-name)]
    (if-let [params (:params conf)]
      (rfe/href id params)
      (rfe/href id))))

;; routes
(def routes
  [["/"
    {:name    :signup
     :segment [""]}]

   ["/project"
    {:name    :project-router
     :segment ["project"]}]
   ["/project/"
    {:name    :projects
     :segment ["project" ""]}]
   ["/project/:id"
    {:name    :project
     :segment ["project" :id]
     :params  {:path {:id string?}}}]

   ])

(def router (rf/router routes {:data {:coercion rss/coercion}}))
(def routes-by-name (make-routes-by-name router))

(def current-fulcro-route* (atom []))
(defn current-fulcro-route [] @current-fulcro-route*)

(def redirect-loop-count (volatile! 0))
(def max-redirect-loop-count 10)

(defn on-match
  [SPA router m]
  (log/info "on-match called with: " (pr-str m))
  (let [m (or m {:path (g/get js/location "pathname")})
        {:keys [path]} m
        has-match? (rf/match-by-path router path)]
    (log/info "router, match: " (pprint-str m))
    (if-not has-match?
      ;; unknown page, redirect to root
      (do
        (log/info "No fulcro route matched the current URL, changing to the default route.")
        (route-to! :signup))

      ;; route has redirect
      (if-let [{:keys [route params]} (get-in m [:data :redirect-to])]
        (let [params (params)]
          (do (log/info "redirecting to: " route " with params " params)
              (if (> @redirect-loop-count max-redirect-loop-count)
                (do
                  (log/error (str "The route " route " hit the max redirects limit: " max-redirect-loop-count))
                  (vreset! redirect-loop-count 0))
                (do
                  (vswap! redirect-loop-count inc)
                  (js/setTimeout #(rfe/replace-state route params))))))

        (let [path (:path m)
              segments (->> (clojure.string/split path "/" -1) (drop 1) vec)
              extended-segments (conj segments "")]
          (if (or (empty? segments)
                  (and (not (rf/match-by-path router path))
                       (rf/match-by-path router (str path "/"))))
            (do
              (log/info "Didn't find a match with " (pr-str segments)
                        ", using " (pr-str extended-segments) " instead")
              (reset! current-fulcro-route* extended-segments)
              (dr/change-route! SPA extended-segments))
            (do
              (log/info "Routing to " (pr-str segments))
              (reset! current-fulcro-route* segments)
              (dr/change-route! SPA segments)))))
      ;; below doesn't seem right to me because it doesn't use parameters
      #_(let [segment (-> m :data :segment)
              fulcro-segments (cond-> segment (fn? segment) (apply [m]))
              params (:path-params m)
              ;; fill in any dynamic path segments with their values
              target-segment (mapv (fn [part] (cond->> part (keyword? part) (get params))) fulcro-segments)]
          (log/info "Invoking Fulcro change route with " (pr-str target-segment))
          (reset! current-fulcro-route* target-segment)
          (dr/change-route! SPA target-segment)))))

(defn current-route [this]
  (some-> (dr/current-route this this) first keyword))

(defn current-app-route []
  (dr/current-route SPA))

(defn current-route-from-url []
  (rf/match-by-path router (g/get js/location "pathname")))

(defn current-route-name
  "Returns the keyword name of the current route as determined by the URL path."
  []
  (some-> (current-route-from-url) :data :name))

(defn route=url?*
  [route-key params {{curr-name :name} :data curr-params :path-params}]
  (boolean
    (when-let [{:keys [name]} (routes-by-name route-key)]
      (and
        (= name curr-name)
        (= params curr-params)))))

(defn route=url?
  "predicate does the :key like :goals {:date \"2020-05-20\"}
  match current reitit match of the url"
  [route-key params]
  (route=url?* route-key params (current-route-from-url)))

(comment (route=url? :goals {:date "2020-05-12"}))

(>defn route-to!
  ([route-key]
   [keyword? => any?]
   (let [{:keys [name] :as route} (get routes-by-name route-key)]
     (when-not (route=url? route-key {})
       (log/info "Changing route to: " route)
       (rfe/push-state name))))

  ([route-key params]
   [keyword? map? => any?]
   (let [{:keys [name] :as route} (get routes-by-name route-key)]
     (when-not (route=url? route-key params)
       (log/info "Changing route to : " route)
       (log/info "push state : " name " params: " params)
       (rfe/push-state name params)))))

(defn change-route-to-default! [this]
  (route-to! :signup))

(defn change-route-after-signout! [this]
  (route-to! :signup))

(defn link
  ([route-name body]
   (link route-name {} body))
  ([route-name params body]
   (let [url (:path (rf/match-by-name router route-name params))]
     (dom/a :.item
            {:classes [(when (= route-name (current-route-name)) "active")]
             :href    url}
            body))))


(defsc ProjectListItem
  [this {:project/keys [id name slug] :as props}]
  {:query (fn [_] [:project/id :project/name :project/slug])
   :ident :project/id})
(defsc ProjectList [this {:keys [all-projects]}]
  {:initial-state {}
   :query         [{[:all-projects '_] (c/get-query ProjectListItem)}]})

(defn init! [SPA]
  (log/info "Starting router.")
  (dr/initialize! SPA)
  (rfe/start!
    router
    (partial on-match SPA router)
    {:use-fragment false}))

(comment
  (rf/match-by-path router "/project")
  (rf/match-by-path router "/project/511f274e-1dbb-435e-bf82-296e16b603ff")

  )

