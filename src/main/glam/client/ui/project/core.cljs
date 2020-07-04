(ns glam.client.ui.project.core
  (:require
    [com.fulcrologic.fulcro.components :as c :refer [defsc]]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr :refer [defrouter]]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [glam.client.router :as r]
    [glam.client.ui.project.projects-page :refer [ProjectsPage]]
    [glam.client.ui.project.project-detail :refer [ProjectDetail]]
    [dv.fulcro-util :as fu]))

;; top level --------------------------------------------------------------------------------
;; router for all routes under "/project/" is contained in a container component, Projects
(defrouter ProjectRouter
  [this {:keys [current-state route-factory route-props]}]
  {:route-segment  ["project"]
   :router-targets [ProjectsPage ProjectDetail]})
(def ui-project-router (c/factory ProjectRouter))

(defsc Projects
  [this {:project/keys [router]}]
  {:query         [{:project/router (c/get-query ProjectRouter)}]
   :initial-state {:project/router {}}}
  (ui-project-router))

