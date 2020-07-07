(ns glam.client.ui.root
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as c :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom :refer [div]]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.fulcro.ui-state-machines :as sm]
    [glam.client.application :refer [SPA]]
    [glam.client.router :as r]
    [glam.client.ui.common :refer [loader]]
    [glam.client.ui.project.core :refer [ProjectRouter]]
    [glam.client.ui.auth :refer [Session session-join valid-session?]]
    [glam.client.ui.signup :refer [Signup]]
    [glam.client.ui.login :refer [ui-login Login]]))

(dr/defrouter TopRouter
  [this {:keys [current-state route-factory route-props]}]
  {:router-targets [Signup ProjectRouter]
   :always-render-body? false}
  (loader))

(def ui-top-router (c/factory TopRouter))

(defn menu [{:keys [session? login]}]
  [:div.ui.secondary.pointing.menu
   (conj
     (mapv #(apply r/link %) (if session?
                             [[:signup "Signup"] [:projects "Projects"]]
                             [[:signup "Signup"]]))
     (ui-login login))])

(defsc PageContainer [this {:root/keys [router login] :as props}]
  {:query         [{:root/router (c/get-query TopRouter)}
                   [::sm/asm-id ::TopRouter]
                   session-join
                   {:root/login (c/get-query Login)}]
   :ident         (fn [] [:component/id :page-container])
   :initial-state (fn [_] {:root/router             (c/get-initial-state TopRouter {})
                           :root/login              (c/get-initial-state Login {})
                           :root/signup             (c/get-initial-state Signup {})
                           [:component/id :session] (c/get-initial-state Session {})})}
  (let [session? (valid-session? props)]
    [:.ui.container
     ^:inline (menu {:session? session? :login login})
     (ui-top-router router)]))

(def ui-page-container (c/factory PageContainer))

(defsc Root [_ {:root/keys [page-container]}]
  {:query         [{:root/page-container (c/get-query PageContainer)}]
   :initial-state (fn [_] {:root/page-container (c/get-initial-state PageContainer {})})}
  ^:inline (ui-page-container page-container))
